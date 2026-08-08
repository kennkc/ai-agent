package com.agent.session.controller;

// DEBT-004: 会话存储简化（VS1 用 Redis Hash 直存）— 触发点: P4 升级 Redis 持久化+乐观锁(version)

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 会话管理接口（Phase 0 最小可用：创建/查询会话）
 */
@RestController
@RequestMapping("/api/session")
public class SessionController {

    private static final String SESSION_PREFIX = "session:";
    private static final Duration SESSION_TTL = Duration.ofHours(2);

    private final StringRedisTemplate redisTemplate;

    public SessionController(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 创建会话 */
    @PostMapping
    public Map<String, String> create(@RequestParam(defaultValue = "default") String tenantId) {
        String sessionId = UUID.randomUUID().toString();
        redisTemplate.opsForHash().put(SESSION_PREFIX + sessionId, "tenant_id", tenantId);
        redisTemplate.opsForHash().put(SESSION_PREFIX + sessionId, "status", "ACTIVE");
        redisTemplate.expire(SESSION_PREFIX + sessionId, SESSION_TTL);
        return Map.of("session_id", sessionId, "status", "ACTIVE");
    }

    /** 查询会话 */
    @GetMapping("/{sessionId}")
    public Map<Object, Object> get(@PathVariable String sessionId) {
        return redisTemplate.opsForHash().entries(SESSION_PREFIX + sessionId);
    }

    /** 结束会话 */
    @DeleteMapping("/{sessionId}")
    public Map<String, String> close(@PathVariable String sessionId) {
        redisTemplate.delete(SESSION_PREFIX + sessionId);
        return Map.of("session_id", sessionId, "status", "CLOSED");
    }
}
