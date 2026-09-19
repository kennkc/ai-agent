package com.agent.session.fsm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;

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
/**
 * 注册为 Spring Bean：{@code SessionController} 需要注入它。
 * 注意本类有**两个构造**，Spring 在存在多个构造且均未标注时无法选择，
 * 故在默认构造上显式标注 {@code @Autowired}（这一约束只在**运行态**暴露，
 * 单元测试手工 new 是抓不到的）。
 */
@Component
public class SessionStore {

    public static final String SESSION_PREFIX = "session:";
    public static final String MESSAGE_SUFFIX = ":messages";
    public static final Duration SESSION_TTL = Duration.ofHours(2);
    public static final int DEFAULT_CONTEXT_TURNS = 10;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final int contextTurns;

    @Autowired
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
     * R-C04 会话统计：活跃会话数 / 状态分布 / 意图分布。
     *
     * <p>数据源是**会话真相本身**（Redis Hash），不是二次汇总表 —— BFF 只需转发，
     * 避免出现"第二份会话真相"。口径如实写在返回体里（采样上限与统计维度）。
     *
     * @param tenantId   租户（为空则统计全部租户）
     * @param maxSessions 扫描上限（防止长会话量下把 Redis 打满）
     */
    public Map<String, Object> stats(String tenantId, int maxSessions) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        Map<String, Integer> byIntent = new LinkedHashMap<>();
        int scanned = 0;
        int active = 0;
        int messages = 0;
        Set<String> keys = scanSessionKeys(maxSessions);
        for (String sessionKey : keys) {
            scanned++;
            Map<String, String> session = load(sessionKey.substring(SESSION_PREFIX.length()));
            if (session.isEmpty()) {
                continue;
            }
            String owner = String.valueOf(session.getOrDefault("tenant_id", ""));
            if (tenantId != null && !tenantId.isBlank() && !tenantId.equals(owner)) {
                continue;
            }
            String status = String.valueOf(session.getOrDefault("status", "UNKNOWN"));
            byStatus.merge(status, 1, Integer::sum);
            if (!"CLOSED".equalsIgnoreCase(status)) {
                active++;
            }
            try {
                messages += Integer.parseInt(String.valueOf(session.getOrDefault("message_count", "0")));
            } catch (NumberFormatException ignored) {
                // 单条脏数据不影响整体统计
            }
            // 意图分布取该会话**最近一轮用户消息**的意图（会话维度的"当前在聊什么"）
            List<Map<String, Object>> recent = context(sessionKey.substring(SESSION_PREFIX.length()), 1);
            if (!recent.isEmpty()) {
                String intent = String.valueOf(recent.get(0).getOrDefault("intent", "")).trim();
                if (!intent.isEmpty() && !"null".equals(intent)) {
                    byIntent.merge(intent, 1, Integer::sum);
                }
            }
        }
        result.put("tenant_id", tenantId == null || tenantId.isBlank() ? "*" : tenantId);
        result.put("active_sessions", active);
        result.put("scanned_sessions", scanned);
        result.put("scan_limit", maxSessions);
        result.put("messages", messages);
        result.put("by_status", byStatus);
        result.put("by_intent", byIntent);
        result.put("ttl_hours", SESSION_TTL.toHours());
        result.put("note", "统计口径：SCAN session:* 全量 Hash 实时汇总（非预聚合表）；意图分布按会话最近一轮用户消息统计");
        return result;
    }

    /** SCAN 会话键（排除消息列表键），失败时返回空集合（降级：统计为空而不是报错）。
     *  <p>protected 便于单测覆写键集合（避免单测依赖真实 Redis 的 SCAN）。 */
    protected Set<String> scanSessionKeys(int maxSessions) {
        Set<String> keys = new HashSet<>();
        try {
            Set<String> scanned = redis.execute((RedisCallback<Set<String>>) connection -> {
                Set<String> out = new HashSet<>();
                ScanOptions options = ScanOptions.scanOptions().match(SESSION_PREFIX + "*").count(500).build();
                try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                    while (cursor.hasNext() && out.size() < maxSessions) {
                        out.add(new String(cursor.next(), StandardCharsets.UTF_8));
                    }
                } catch (Exception e) {
                    return out;
                }
                return out;
            });
            if (scanned != null) {
                keys.addAll(scanned);
            }
        } catch (Exception e) {
            // 统计失败不阻断业务：返回空集合，由调用方标注 degraded
            keys.clear();
        }
        keys.removeIf(key -> key.endsWith(MESSAGE_SUFFIX));
        return keys;
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
