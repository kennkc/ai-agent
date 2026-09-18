package com.agent.body.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 热层缓存（R3-01 HOT · R3-08 缓存优先策略）
 *
 * <p>缓存键 = {@code knowledge:search:{tenant}:{query 指纹}}，值 = 检索结果 JSON（TTL 可配，默认 1h）。
 * 另外维护查询访问计数（供 {@link TierRouter} 计算热度）。
 *
 * <p>降级语义：Redis 不可用时 {@link #available()} 为 false，检索链路继续走 Qdrant（缓存是可选项），
 * 但**不允许**把"未命中"伪装成"命中"——命中率统计与 {@code cacheBackend} 字段如实上报。
 */
@Component
public class HotCacheStore {

    private static final Logger log = LoggerFactory.getLogger(HotCacheStore.class);
    private static final String PREFIX = "knowledge:search:";
    private static final String ACCESS_PREFIX = "knowledge:access:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private volatile boolean available = true;

    public HotCacheStore(StringRedisTemplate redis, ObjectMapper objectMapper,
                         @Value("${app.body.cache.ttl-seconds:3600}") long ttlSeconds) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofSeconds(Math.max(ttlSeconds, 1));
    }

    public boolean available() { return available; }

    public String backend() { return available ? "redis" : "unavailable"; }

    /** 查询指纹：归一化问题（去空白/小写/去尾部标点），保证同一问题的不同书写命中同一缓存 */
    public String fingerprint(String tenantId, String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase()
                .replaceAll("\\s+", "").replaceAll("[？?。.!！]+$", "");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (Exception e) {
            return Integer.toHexString(normalized.hashCode());
        }
    }

    /** 缓存键带租户前缀：既保证隔离，又支持按租户批量失效 */
    private String cacheKey(String tenantId, String query) {
        return PREFIX + tenantId + ":" + fingerprint(tenantId, query);
    }

    private String accessKey(String tenantId, String query) {
        return ACCESS_PREFIX + tenantId + ":" + fingerprint(tenantId, query);
    }

    public <T> Optional<T> get(String tenantId, String query, TypeReference<T> type) {
        if (!available) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        try {
            String raw = redis.opsForValue().get(cacheKey(tenantId, query));
            if (raw == null) {
                misses.incrementAndGet();
                return Optional.empty();
            }
            hits.incrementAndGet();
            return Optional.of(objectMapper.readValue(raw, type));
        } catch (Exception e) {
            markUnavailable(e);
            misses.incrementAndGet();
            return Optional.empty();
        }
    }

    public void put(String tenantId, String query, Object value) {
        if (!available) return;
        try {
            redis.opsForValue().set(cacheKey(tenantId, query),
                    objectMapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            markUnavailable(e);
        }
    }

    /** 记录一次问题访问（热度计算的频率输入） */
    public void recordAccess(String tenantId, String query) {
        if (!available) return;
        try {
            redis.opsForValue().increment(accessKey(tenantId, query));
        } catch (Exception e) {
            markUnavailable(e);
        }
    }

    public long accessCount(String tenantId, String query) {
        if (!available) return 0;
        try {
            String value = redis.opsForValue().get(accessKey(tenantId, query));
            return value == null ? 0 : Long.parseLong(value);
        } catch (Exception e) {
            markUnavailable(e);
            return 0;
        }
    }

    public void invalidateTenant(String tenantId) {
        if (!available) return;
        try {
            var cacheKeys = redis.keys(PREFIX + tenantId + ":*");
            if (cacheKeys != null && !cacheKeys.isEmpty()) {
                redis.delete(cacheKeys);
            }
            var accessKeys = redis.keys(ACCESS_PREFIX + tenantId + ":*");
            if (accessKeys != null && !accessKeys.isEmpty()) {
                redis.delete(accessKeys);
            }
        } catch (Exception e) {
            markUnavailable(e);
        }
    }

    public long hits() { return hits.get(); }

    public long misses() { return misses.get(); }

    /** 命中率（R3-08 验收 ≥ 30%）：以缓存查询次数为分母 */
    public double hitRate() {
        long total = hits.get() + misses.get();
        return total == 0 ? 0.0 : (double) hits.get() / total;
    }

    private void markUnavailable(Exception e) {
        if (available) {
            available = false;
            log.warn("Redis 热层不可用，缓存优先策略降级（检索仍可用）：{}", e.getMessage());
        }
    }
}
