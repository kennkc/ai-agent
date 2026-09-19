package com.agent.session.fsm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * R-C04 会话统计（活跃会话 / 状态分布 / 意图分布）—— 大脑视图的真实数据源。
 *
 * <p>断言重点：统计**来源于会话真相本身**（Redis Hash），活跃数剔除 CLOSED，
 * 意图分布取会话最近一轮用户消息，且 SCAN 失败时降级为空统计而不是抛异常。
 */
@SuppressWarnings("unchecked")
class SessionStatsTest {

    private StringRedisTemplate redis;
    private Map<String, Map<String, String>> hashes;
    private Map<String, List<String>> lists;
    private ObjectMapper mapper;

    /** 覆写 SCAN：单测不连真 Redis，直接给定键集合（真 SCAN 由集成/端到端覆盖）。 */
    static class StoreWithKeys extends SessionStore {
        private final Set<String> keys;

        StoreWithKeys(StringRedisTemplate redis, ObjectMapper mapper, Set<String> keys) {
            super(redis, mapper);
            this.keys = keys;
        }

        @Override
        protected Set<String> scanSessionKeys(int maxSessions) {
            return keys;
        }
    }

    @BeforeEach
    void setUp() {
        hashes = new LinkedHashMap<>();
        lists = new LinkedHashMap<>();
        mapper = new ObjectMapper();

        redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        ListOperations<String, String> list = mock(ListOperations.class);
        when(redis.opsForHash()).thenReturn((HashOperations) hash);
        when(redis.opsForList()).thenReturn((ListOperations) list);
        // 注意：Lambda 里直接 getArgument(0) 会被推断成 char[] 重载并 ClassCastException，
        // 必须先取 Object 再 String.valueOf（与 SessionStoreTest 同一坑）。
        when(hash.entries(anyString())).thenAnswer(inv -> {
            Object key = inv.getArgument(0);
            return new LinkedHashMap<Object, Object>(hashes.getOrDefault(String.valueOf(key), Map.of()));
        });
        when(list.range(anyString(), anyLong(), anyLong())).thenAnswer(inv -> {
            Object key = inv.getArgument(0);
            return lists.getOrDefault(String.valueOf(key), List.of());
        });
    }

    private void session(String id, String tenant, String status, int messageCount) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("session_id", id);
        fields.put("tenant_id", tenant);
        fields.put("status", status);
        fields.put("message_count", String.valueOf(messageCount));
        hashes.put(SessionStore.SESSION_PREFIX + id, fields);
    }

    private void message(String id, String role, String intent) throws Exception {
        lists.put(SessionStore.SESSION_PREFIX + id + SessionStore.MESSAGE_SUFFIX,
                List.of(mapper.writeValueAsString(Map.of("role", role, "content", "内容", "intent", intent))));
    }

    @Test
    void stats_counts_active_sessions_and_excludes_closed() throws Exception {
        session("s1", "t1", "ACTIVE", 4);
        session("s2", "t1", "CLOSED", 2);
        message("s1", "user", "知识问答");
        message("s2", "user", "汇总报告");

        StoreWithKeys store = new StoreWithKeys(redis, mapper, Set.of(
                SessionStore.SESSION_PREFIX + "s1", SessionStore.SESSION_PREFIX + "s2"));
        Map<String, Object> stats = store.stats("t1", 500);

        assertEquals(1, stats.get("active_sessions"));
        assertEquals(2, stats.get("scanned_sessions"));
        assertEquals(6, stats.get("messages"));
        Map<String, Integer> byStatus = (Map<String, Integer>) stats.get("by_status");
        assertEquals(1, byStatus.get("ACTIVE"));
        assertEquals(1, byStatus.get("CLOSED"));
        Map<String, Integer> byIntent = (Map<String, Integer>) stats.get("by_intent");
        assertEquals(1, byIntent.get("知识问答"));
        assertEquals(1, byIntent.get("汇总报告"));
        assertTrue(String.valueOf(stats.get("note")).contains("SCAN"));
    }

    @Test
    void stats_filters_by_tenant() {
        session("s1", "t1", "ACTIVE", 1);
        session("s3", "t2", "ACTIVE", 1);
        StoreWithKeys store = new StoreWithKeys(redis, mapper, Set.of(
                SessionStore.SESSION_PREFIX + "s1", SessionStore.SESSION_PREFIX + "s3"));
        Map<String, Object> stats = store.stats("t1", 500);
        assertEquals("t1", stats.get("tenant_id"));
        assertEquals(1, stats.get("active_sessions"));
    }

    @Test
    void stats_degrades_to_empty_when_scan_unavailable() {
        // 未打桩 execute(...)：RedisCallback 返回 null，统计应降级为空集合而不是抛异常
        SessionStore store = new SessionStore(redis, mapper);
        Map<String, Object> stats = store.stats("t1", 500);
        assertEquals(0, stats.get("active_sessions"));
        assertEquals(0, stats.get("scanned_sessions"));
        assertNotNull(stats.get("by_status"));
    }
}
