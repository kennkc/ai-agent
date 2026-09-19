package com.agent.session.fsm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * R4-02 会话持久化：Redis Hash（{@code session:{id}}）+ 消息列表（{@code session:{id}:messages}）+ TTL。
 *
 * <p>职责边界：
 * <ul>
 *   <li>只负责**存储与状态迁移的持久化**，不含业务编排（编排在 {@code SessionController}）；</li>
 *   <li>状态迁移的合法性由 {@link SessionFsm} 裁决，本类只负责把结果写回 Redis；</li>
 *   <li>多轮上下文取**最近 K 轮**（默认 10），超出窗口的老消息仍在列表里，只是不进 Prompt。</li>
 * </ul>
 *
 * <p>「服务重启会话可恢复」由 Redis 承载：进程重启只是重新读 Hash，不依赖内存态。
 *
 * <p>DEBT-014: 会话消息列表为**全量保留 + 读取时截断**（无压缩），长会话会持续增长 ——
 * 触发点: 多轮上下文超过 100 轮或引入会话压缩策略（设计文档 §3.1 预留）。
 */
public class SessionStore {

    public static final String SESSION_PREFIX = "session:";
    public static final String MESSAGE_SUFFIX = ":messages";
    public static final Duration SESSION_TTL = Duration.ofHours(2);
    public static final int DEFAULT_CONTEXT_TURNS = 10;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final int contextTurns;

    public SessionStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this(redis, objectMapper, DEFAULT_CONTEXT_TURNS);
    }

    public SessionStore(StringRedisTemplate redis, ObjectMapper objectMapper, int contextTurns) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.contextTurns = contextTurns;
    }

    // ─────────── 生命周期 ───────────

    /** 创建会话：写 Hash 并置 ACTIVE（NEW --create--> ACTIVE）。 */
    public String create(String tenantId) {
        long now = System.currentTimeMillis();
        String sessionId = UUID.randomUUID().toString();
        String key = key(sessionId);
        SessionFsm.State state = SessionFsm.transition(SessionFsm.State.NEW, SessionFsm.Event.CREATE);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("session_id", sessionId);
        fields.put("tenant_id", tenantId == null ? "default" : tenantId);
        fields.put("status", state.name());
        fields.put("created_at", String.valueOf(now));
        fields.put("updated_at", String.valueOf(now));
        fields.put("last_activity_at", String.valueOf(now));
        fields.put("message_count", "0");
        redis.opsForHash().putAll(key, fields);
        redis.expire(key, SESSION_TTL);
        return sessionId;
    }

    /** 读取会话快照（不存在时字段为空）。 */
    public Map<String, String> load(String sessionId) {
        Map<Object, Object> entries = redis.opsForHash().entries(key(sessionId));
        Map<String, String> out = new LinkedHashMap<>();
        entries.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        return out;
    }

    /** 状态迁移并持久化；返回迁移后的状态。 */
    public SessionFsm.State transition(String sessionId, SessionFsm.Event event) {
        Map<String, String> current = load(sessionId);
        if (current.isEmpty()) {
            throw new SessionFsm.IllegalTransitionException(null, event, "session not found: " + sessionId);
        }
        SessionFsm.State from = parseState(current.get("status"));
        SessionFsm.State next = SessionFsm.transition(from, event);
        String key = key(sessionId);
        redis.opsForHash().put(key, "status", next.name());
        redis.opsForHash().put(key, "updated_at", String.valueOf(System.currentTimeMillis()));
        if (event == SessionFsm.Event.MESSAGE) {
            redis.opsForHash().put(key, "last_activity_at", String.valueOf(System.currentTimeMillis()));
        }
        redis.expire(key, SESSION_TTL);
        return next;
    }

    /** 关闭会话（终态）：删除消息列表，Hash 保留为 CLOSED 痕迹并短期保留。 */
    public void close(String sessionId) {
        transition(sessionId, SessionFsm.Event.CLOSE);
        redis.delete(key(sessionId) + MESSAGE_SUFFIX);
    }

    // ─────────── 消息与上下文 ───────────

    /** 追加一条消息，并同步刷新活跃时间与消息计数。 */
    public void appendMessage(String sessionId, String role, String content, String intent,
                              String source, long latencyMs) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", UUID.randomUUID().toString());
        message.put("role", role);
        message.put("content", content);
        message.put("intent", intent);
        message.put("source", source);
        message.put("latency_ms", latencyMs);
        message.put("created_at", System.currentTimeMillis());
        String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize message", e);
        }
        String listKey = key(sessionId) + MESSAGE_SUFFIX;
        redis.opsForList().rightPush(listKey, payload);
        redis.expire(listKey, SESSION_TTL);
        String key = key(sessionId);
        Long size = redis.opsForList().size(listKey);
        redis.opsForHash().put(key, "message_count", String.valueOf(size == null ? 0 : size));
        redis.opsForHash().put(key, "last_activity_at", String.valueOf(System.currentTimeMillis()));
        redis.expire(key, SESSION_TTL);
    }

    /** 最近 K 轮上下文（按时间正序，供 Prompt 组装使用）。 */
    public List<Map<String, Object>> context(String sessionId, int turns) {
        int limit = turns <= 0 ? contextTurns : turns;
        List<String> raw = redis.opsForList().range(key(sessionId) + MESSAGE_SUFFIX, -limit, -1);
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String item : raw) {
            try {
                out.add(objectMapper.readValue(item, new TypeReference<Map<String, Object>>() {
                }));
            } catch (Exception ignored) {
                // 单条损坏不影响整体上下文（降级：跳过该条）
            }
        }
        return out;
    }

    public List<Map<String, Object>> context(String sessionId) {
        return context(sessionId, contextTurns);
    }

    // ─────────── 工具 ───────────

    public static String key(String sessionId) {
        return SESSION_PREFIX + sessionId;
    }

    /**
     * 解析持久化状态。
     *
     * <p>缺省值取 {@code ACTIVE} 而非 {@code NEW}：Hash 里存在记录即代表会话已创建过，
     * 没有 {@code status} 字段只可能是 R4-01 之前写入的旧数据（Redis TTL 2h，属过渡期遗留），
     * 按活跃会话兼容处理比判成非法迁移更符合语义。
     */
    private static SessionFsm.State parseState(String value) {
        if (value == null || value.isBlank()) {
            return SessionFsm.State.ACTIVE;
        }
        try {
            return SessionFsm.State.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return SessionFsm.State.ACTIVE;
        }
    }
}
