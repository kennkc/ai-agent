package com.agent.session.controller;

import com.agent.session.common.BizException;
import com.agent.session.common.ErrorCode;
import com.agent.session.kafka.KafkaEventPublisher;
import com.agent.session.bus.BusProxy;
import com.agent.session.orchestration.BodyClient;
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
    private final ObjectMapper objectMapper;

    public SessionController(StringRedisTemplate redisTemplate, BusProxy busProxy,
                             KafkaEventPublisher kafkaEventPublisher, NlpClient nlpClient,
                             BodyClient bodyClient, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.busProxy = busProxy;
        this.kafkaEventPublisher = kafkaEventPublisher;
        this.nlpClient = nlpClient;
        this.bodyClient = bodyClient;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public Map<String, String> create(@RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        long now = System.currentTimeMillis();
        String sessionId = UUID.randomUUID().toString();
        String key = SESSION_PREFIX + sessionId;
        redisTemplate.opsForHash().put(key, "tenant_id", tenantId);
        redisTemplate.opsForHash().put(key, "status", "ACTIVE");
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
        redisTemplate.delete(SESSION_PREFIX + sessionId);
        redisTemplate.delete(SESSION_PREFIX + sessionId + MESSAGE_SUFFIX);
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
        long started = System.currentTimeMillis();
        Map<String, Object> intentResult = nlpClient.recognize(request.question(), sessionId, tenantId);
        List<Map<String, Object>> chunks = bodyClient.retrieve(request.question(), tenantId);
        String answer = buildAnswer(chunks);
        long latency = System.currentTimeMillis() - started;
        appendMessage(sessionId, "user", request.question(), String.valueOf(intentResult.getOrDefault("intent", "闲聊")), "user", 0);
        appendMessage(sessionId, "assistant", answer, String.valueOf(intentResult.getOrDefault("intent", "闲聊")), "llm", latency);
        touchSession(sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session_id", sessionId); result.put("intent", intentResult.getOrDefault("intent", "闲聊"));
        result.put("answer", answer); result.put("citations", chunks);
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

    public record AskRequest(String question) {}
}


