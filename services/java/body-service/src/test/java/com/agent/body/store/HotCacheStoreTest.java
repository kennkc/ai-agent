package com.agent.body.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * R3-08 缓存优先策略验收：指纹归一化有效、Redis 不可用时诚实降级（不伪造命中）。
 */
class HotCacheStoreTest {

    private HotCacheStore withRedis(ValueOperations<String, String> ops) {
        StringRedisTemplate template = Mockito.mock(StringRedisTemplate.class);
        when(template.opsForValue()).thenReturn(ops);
        return new HotCacheStore(template, new ObjectMapper(), 3600);
    }

    @Test
    void fingerprintNormalizesQuestionWording() {
        HotCacheStore store = withRedis(Mockito.mock(ValueOperations.class));
        assertEquals(store.fingerprint("t1", "什么是躯体层？"),
                store.fingerprint("t1", "  什么是躯体层  "));
        assertEquals(store.fingerprint("t1", "What Is Body?"),
                store.fingerprint("t1", "what is body?"));
        // 不同问题不应撞键
        assertNotEquals(store.fingerprint("t1", "躯体层"), store.fingerprint("t1", "大脑层"));
    }

    @Test
    void cacheHitReturnsStoredValue() {
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
        when(ops.get(anyString())).thenReturn("[\"hit-1\"]");
        HotCacheStore store = withRedis(ops);

        Optional<List<String>> value = store.get("t1", "问题", new TypeReference<>() { });
        assertTrue(value.isPresent());
        assertEquals(List.of("hit-1"), value.get());
        assertEquals(0, store.misses());
        assertEquals(1, store.hits());
        assertEquals(1.0, store.hitRate(), 1e-9);
    }

    @Test
    void redisFailureDegradesHonestlyInsteadOfFakingHits() {
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
        when(ops.get(anyString())).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
        HotCacheStore store = withRedis(ops);

        Optional<String> value = store.get("t1", "问题", new TypeReference<>() { });
        assertTrue(value.isEmpty(), "Redis 不可用时必须返回未命中");
        assertFalse(store.available(), "可用性须如实置为 false");
        assertEquals("unavailable", store.backend());
        assertEquals(0, store.hits(), "不得把失败伪装成命中");
        assertEquals(0.0, store.hitRate(), 1e-9);
    }

    @Test
    void writeIsSkippedWhenRedisUnavailable() {
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
        when(ops.get(anyString())).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
        HotCacheStore store = withRedis(ops);
        store.get("t1", "问题", new TypeReference<>() { });
        // 降级后写入直接跳过，不抛异常
        assertDoesNotThrow(() -> store.put("t1", "问题", "value"));
        assertDoesNotThrow(() -> store.recordAccess("t1", "问题"));
        assertEquals(0, store.accessCount("t1", "问题"));
    }

    @Test
    void hitRateStaysZeroBeforeAnyQuery() {
        HotCacheStore store = withRedis(Mockito.mock(ValueOperations.class));
        assertEquals(0.0, store.hitRate(), 1e-9);
    }
}
