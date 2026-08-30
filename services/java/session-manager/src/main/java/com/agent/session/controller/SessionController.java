package com.agent.session.controller;

// DEBT-004: 会话存储简化（VS1 用 Redis Hash 直存）— 触发点: P4 升级 Redis 持久化+乐观锁(version)

import com.agent.session.common.BizException;
import com.agent.session.common.ErrorCode;
import com.agent.session.kafka.KafkaEventPublisher;
import com.agent.session.nats.NatsClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 会话管理接口（Phase 1：创建会话 → NATS 总线调用 + Kafka 事件发布）
 * R1-03 总线代理：session 创建经 NATS 请求 sense 总线健康（lifeform.rpc.sense.ping）
 * R1-04 NATS 同步调用、R1-05 Kafka 异步事件、R1-08 统一错误码
 */
@RestController
@RequestMapping("/api/session")
public class SessionController {

    private static final String SESSION_PREFIX = "session:";
    private static final Duration SESSION_TTL = Duration.ofHours(2);

    private final StringRedisTemplate redisTemplate;
    private final NatsClient natsClient;
    private final KafkaEventPublisher kafkaEventPublisher;

    public SessionController(StringRedisTemplate redisTemplate,
                             NatsClient natsClient,
                             KafkaEventPublisher kafkaEventPublisher) {
        this.redisTemplate = redisTemplate;
        this.natsClient = natsClient;
        this.kafkaEventPublisher = kafkaEventPublisher;
    }

    /** 创建会话（经总线：NATS 同步探活 + Kafka 事件通知） */
    @PostMapping
    public Map<String, String> create(@RequestParam(defaultValue = "default") String tenantId) {
        String sessionId = UUID.randomUUID().toString();
        redisTemplate.opsForHash().put(SESSION_PREFIX + sessionId, "tenant_id", tenantId);
        redisTemplate.opsForHash().put(SESSION_PREFIX + sessionId, "status", "ACTIVE");
        redisTemplate.expire(SESSION_PREFIX + sessionId, SESSION_TTL);

        // R1-04 NATS 同步调用：请求 sense 总线健康（生命体神经信号）
        String busStatus = natsClient.request("lifeform.rpc.sense.ping",
                "{\"from\":\"session-manager\",\"session_id\":\"" + sessionId + "\"}");

        // R1-05 Kafka 异步事件：会话创建事件
        Map<String, Object> event = new HashMap<>();
        event.put("session_id", sessionId);
        event.put("tenant_id", tenantId);
        event.put("timestamp", String.valueOf(System.currentTimeMillis()));
        event.put("bus_status", busStatus == null ? "direct" : "nats");
        kafkaEventPublisher.publish("session", "created", sessionId, event);

        Map<String, String> result = new HashMap<>();
        result.put("session_id", sessionId);
        result.put("status", "ACTIVE");
        result.put("bus_channel", busStatus == null ? "direct" : "nats");
        result.put("bus_reply", busStatus == null ? "" : busStatus);
        return result;
    }

    /** 查询会话 */
    @GetMapping("/{sessionId}")
    public Map<Object, Object> get(@PathVariable String sessionId) {
        Map<Object, Object> session = redisTemplate.opsForHash().entries(SESSION_PREFIX + sessionId);
        if (session.isEmpty()) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND, "会话不存在: " + sessionId);
        }
        return session;
    }

    /** 结束会话 */
    @DeleteMapping("/{sessionId}")
    public Map<String, String> close(@PathVariable String sessionId) {
        Boolean existed = redisTemplate.delete(SESSION_PREFIX + sessionId);
        if (existed == null || !existed) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND, "会话不存在: " + sessionId);
        }
        return Map.of("session_id", sessionId, "status", "CLOSED");
    }
}
