package com.agent.session.controller;

import com.agent.session.common.BizException;
import com.agent.session.common.ErrorCode;
import com.agent.session.fsm.SessionFsm;
import com.agent.session.fsm.SessionStore;
import com.agent.session.kafka.KafkaEventPublisher;
import com.agent.session.bus.BusProxy;
import com.agent.session.orchestration.BodyClient;
import com.agent.session.orchestration.BrainClient;
import com.agent.session.orchestration.NlpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.*;


@RestController
@RequestMapping("/api/session")
public class SessionController {
    private static final String SESSION_PREFIX = "session:";
    private static final String MESSAGE_SUFFIX = ":messages";
    private static final Duration SESSION_TTL = Duration.ofHours(2);
    private final StringRedisTemplate redisTemplate;
    private final BusProxy busProxy;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final NlpClient nlpClient;
    private final BodyClient bodyClient;
    /** R4-06 大脑层客户端（规划→检索→生成→来源标注），不可用时降级本地直出 */
    private final BrainClient brainClient;
    private final ObjectMapper objectMapper;
    /** R4-02 会话持久化（Redis Hash + 多轮上下文）；R4-01 状态迁移由 SessionFsm 裁决 */
    private final SessionStore sessionStore;

    public SessionController(StringRedisTemplate redisTemplate, BusProxy busProxy,
                             KafkaEventPublisher kafkaEventPublisher, NlpClient nlpClient,
                             BodyClient bodyClient, ObjectMapper objectMapper) {
        this(redisTemplate, busProxy, kafkaEventPublisher, nlpClient, bodyClient, objectMapper,
                new SessionStore(redisTemplate, objectMapper));
    }

    /** 兼容构造（无大脑层客户端）：大脑层视为不可用，走本地检索直出。 */
    public SessionController(StringRedisTemplate redisTemplate, BusProxy busProxy,
                             KafkaEventPublisher kafkaEventPublisher, NlpClient nlpClient,
                             BodyClient bodyClient, ObjectMapper objectMapper, SessionStore sessionStore) {
        this(redisTemplate, busProxy, kafkaEventPublisher, nlpClient, bodyClient, objectMapper,
                sessionStore, null);
    }

    public SessionController(StringRedisTemplate redisTemplate, BusProxy busProxy,
                             KafkaEventPublisher kafkaEventPublisher, NlpClient nlpClient,
                             BodyClient bodyClient, ObjectMapper objectMapper, SessionStore sessionStore,
                             BrainClient brainClient) {
        this.redisTemplate = redisTemplate;
        this.busProxy = busProxy;
        this.kafkaEventPublisher = kafkaEventPublisher;
        this.nlpClient = nlpClient;
        this.bodyClient = bodyClient;
        this.objectMapper = objectMapper;
        this.sessionStore = sessionStore;
        this.brainClient = brainClient;
    }

    @PostMapping
    public Map<String, String> create(@RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        long now = System.currentTimeMillis();
        String sessionId = UUID.randomUUID().toString();
        String key = SESSION_PREFIX + sessionId;
        redisTemplate.opsForHash().put(key, "tenant_id", tenantId);
        redisTemplate.opsForHash().put(key, "status",
                SessionFsm.transition(SessionFsm.State.NEW, SessionFsm.Event.CREATE).name());
        redisTemplate.opsForHash().put(key, "created_at", String.valueOf(now));
        redisTemplate.opsForHash().put(key, "updated_at", String.valueOf(now));
        redisTemplate.opsForHash().put(key, "last_activity_at", String.valueOf(now));
        redisTemplate.opsForHash().put(key, "message_count", "0");
        redisTemplate.expire(key, SESSION_TTL);
        String busStatus = busProxy.request("lifeform.rpc.sense.ping", Map.of("from", "session-manager", "session_id", sessionId));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("session_id", sessionId); event.put("tenant_id", tenantId); event.put("timestamp", now);
        event.put("bus_status", busStatus == null ? "direct" : "nats");
        kafkaEventPublisher.publish("session", "created", sessionId, event);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("session_id", sessionId); result.put("status", "ACTIVE");
        result.put("bus_channel", busStatus == null ? "direct" : "nats");
        result.put("bus_reply", busStatus == null ? "" : busStatus);
        return result;
    }

    @GetMapping("/{sessionId}")
    public Map<Object, Object> get(@PathVariable String sessionId,
                                   @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return requireOwnedSession(sessionId, tenantId);
    }

    @DeleteMapping("/{sessionId}")
    public Map<String, String> close(@PathVariable String sessionId,
                                     @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        requireOwnedSession(sessionId, tenantId);
        try {
            sessionStore.close(sessionId);
        } catch (SessionFsm.IllegalTransitionException e) {
            throw new BizException(ErrorCode.AGENT_CONFLICT, "session cannot be closed: " + sessionId);
        }
        return Map.of("session_id", sessionId, "status", "CLOSED");
    }

    @PostMapping("/{sessionId}/ask")
    public Map<String, Object> ask(@PathVariable String sessionId,
                                   @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                   @RequestBody AskRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new BizException(ErrorCode.AGENT_BAD_REQUEST, "question must not be blank");
        }
        requireOwnedSession(sessionId, tenantId);
        try {
            sessionStore.transition(sessionId, SessionFsm.Event.MESSAGE);
        } catch (SessionFsm.IllegalTransitionException e) {
            throw new BizException(ErrorCode.AGENT_CONFLICT, "session is closed: " + sessionId);
        }
        long started = System.currentTimeMillis();
        Map<String, Object> intentResult = nlpClient.recognize(request.question(), sessionId, tenantId);
        String intent = String.valueOf(intentResult.getOrDefault("intent", "闲聊"));
        double confidence = intentResult.get("confidence") instanceof Number n ? n.doubleValue() : 0.5;

        // R4-06：优先走大脑层（规划→检索→生成→来源标注），不可用时降级本地检索直出
        BrainClient.BrainAnswer brain = brainClient == null ? null
                : brainClient.ask(request.question(), sessionId, tenantId, intent, confidence,
                        sessionStore.context(sessionId));
        List<String> degradedReasons = new ArrayList<>();
        String answer;
        List<Map<String, Object>> citations;
        Map<String, Object> gap = Map.of();
        List<Map<String, Object>> chain = List.of();
        String generator = "none";
        String decisionId = "";
        if (brain != null && brain.available()) {
            answer = brain.answer();
            citations = brain.sources().isEmpty() ? bodyClient.retrieve(request.question(), tenantId) : brain.sources();
            gap = brain.gap();
            chain = brain.chain();
            generator = brain.generator();
            decisionId = brain.decisionId();
            degradedReasons.addAll(brain.degradedReasons());
        } else {
            if (brain != null) {
                degradedReasons.addAll(brain.degradedReasons());
            } else {
                degradedReasons.add("brain_client_not_configured");
            }
            List<Map<String, Object>> chunks = bodyClient.retrieve(request.question(), tenantId);
            answer = buildAnswer(chunks);
            citations = chunks;
            generator = "local-retrieval";
        }
        long latency = System.currentTimeMillis() - started;
        appendMessage(sessionId, "user", request.question(), intent, "user", 0);
        appendMessage(sessionId, "assistant", answer, intent, generator, latency);
        touchSession(sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session_id", sessionId); result.put("intent", intent);
        result.put("answer", answer); result.put("citations", citations);
        // 降级可见：来源/缺口/决策链/生成器标识一律回传，前端据此决定是否打「降级」标记
        result.put("sources", citations);
        result.put("gap", gap);
        result.put("chain", chain);
        result.put("generator", generator);
        result.put("decision_id", decisionId);
        result.put("degraded", !degradedReasons.isEmpty());
        result.put("degraded_reasons", degradedReasons);
        result.put("latency_ms", latency);
        return result;
    }

    private Map<Object, Object> requireOwnedSession(String sessionId, String tenantId) {
        Map<Object, Object> session = redisTemplate.opsForHash().entries(SESSION_PREFIX + sessionId);
        if (session.isEmpty()) throw new BizException(ErrorCode.AGENT_NOT_FOUND, "session not found: " + sessionId);
        Object owner = session.get("tenant_id");
        if (owner == null || !tenantId.equals(String.valueOf(owner))) throw new BizException(ErrorCode.AGENT_FORBIDDEN, "session does not belong to tenant");
        return session;
    }

    private void touchSession(String sessionId) {
        String key = SESSION_PREFIX + sessionId;
        long now = System.currentTimeMillis();
        Long count = redisTemplate.opsForList().size(key + MESSAGE_SUFFIX);
        redisTemplate.opsForHash().put(key, "updated_at", String.valueOf(now));
        redisTemplate.opsForHash().put(key, "last_activity_at", String.valueOf(now));
        redisTemplate.opsForHash().put(key, "message_count", String.valueOf(count == null ? 0 : count));
        redisTemplate.expire(key, SESSION_TTL);
        redisTemplate.expire(key + MESSAGE_SUFFIX, SESSION_TTL);
    }

    private void appendMessage(String sessionId, String role, String content, String intent, String source, long latencyMs) {
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("id", UUID.randomUUID().toString()); message.put("role", role); message.put("content", content);
            message.put("intent", intent); message.put("source", source); message.put("latency_ms", latencyMs);
            message.put("created_at", System.currentTimeMillis());
            redisTemplate.opsForList().rightPush(SESSION_PREFIX + sessionId + MESSAGE_SUFFIX, objectMapper.writeValueAsString(message));
        } catch (Exception e) {
            throw new BizException(ErrorCode.AGENT_INTERNAL_ERROR, "failed to persist message");
        }
    }

    private String buildAnswer(List<Map<String, Object>> chunks) {
        if (chunks.isEmpty()) return "当前知识库未找到足够信息，请补充文档后重试。";
        String content = String.valueOf(chunks.get(0).getOrDefault("content", ""));
        String source = String.valueOf(chunks.get(0).getOrDefault("title", "knowledge"));
        String preview = content.length() > 240 ? content.substring(0, 240) + "..." : content;
        return "根据知识库《" + source + "》检索结果：" + preview;
    }

    /**
     * R4-02 多轮上下文：取最近 K 轮消息（默认 10），供大脑层 Prompt 组装 / 前端回放。
     */
    @GetMapping("/{sessionId}/context")
    public Map<String, Object> context(@PathVariable String sessionId,
                                       @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                       @RequestParam(value = "turns", defaultValue = "10") int turns) {
        Map<Object, Object> session = requireOwnedSession(sessionId, tenantId);
        List<Map<String, Object>> messages = sessionStore.context(sessionId, turns);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session_id", sessionId);
        result.put("status", String.valueOf(session.getOrDefault("status", "UNKNOWN")));
        result.put("turns", turns <= 0 ? SessionStore.DEFAULT_CONTEXT_TURNS : turns);
        result.put("returned", messages.size());
        result.put("messages", messages);
        return result;
    }

    public record AskRequest(String question) {}
}


