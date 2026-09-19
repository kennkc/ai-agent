package com.agent.session.fsm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * R4-02 会话持久化：Hash 字段 / TTL / 状态迁移落库 / 多轮上下文窗口。
 *
 * <p>用 Mockito 对 Redis 操作打桩（内存 Map 承载），断言的是**写侧行为**：
 * 写了哪些字段、状态如何迁移、窗口是否只取最近 K 轮。
 */
class SessionStoreTest {

    private StringRedisTemplate redis;
    private Map<String, Map<String, String>> hashes;   // key -> fields
    private Map<String, List<String>> lists;           // key -> messages
    private List<String> expiredKeys;
    private List<String> deletedKeys;
    private SessionStore store;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        hashes = new HashMap<>();
        lists = new HashMap<>();
        expiredKeys = new ArrayList<>();
        deletedKeys = new ArrayList<>();

        redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        ListOperations<String, String> list = mock(ListOperations.class);

        when(redis.opsForHash()).thenReturn((HashOperations) hash);
        when(redis.opsForList()).thenReturn((ListOperations) list);

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            Object hashKey = inv.getArgument(1);      // 先取 Object：直接 String.valueOf(getArgument(n))
            Object hashValue = inv.getArgument(2);    // 会被推断成 char[] 重载而 ClassCastException
            hashes.computeIfAbsent(key, k -> new LinkedHashMap<>())
                    .put(String.valueOf(hashKey), String.valueOf(hashValue));
            return null;
        }).when(hash).put(anyString(), any(), any());

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            Map<?, ?> values = inv.getArgument(1);
            Map<String, String> bucket = hashes.computeIfAbsent(key, k -> new LinkedHashMap<>());
            values.forEach((k, v) -> bucket.put(String.valueOf(k), String.valueOf(v)));
            return null;
        }).when(hash).putAll(anyString(), anyMap());

        when(hash.entries(anyString())).thenAnswer(inv ->
                new LinkedHashMap<Object, Object>(hashes.getOrDefault(inv.getArgument(0), Map.of())));

        doAnswer(inv -> {
            lists.computeIfAbsent(inv.getArgument(0), k -> new ArrayList<>()).add(inv.getArgument(1));
            return 1L;
        }).when(list).rightPush(anyString(), anyString());

        when(list.range(anyString(), anyLong(), anyLong())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            long start = inv.getArgument(1);
            List<String> items = lists.getOrDefault(key, List.of());
            int from = start < 0 ? Math.max(0, (int) (items.size() + start)) : (int) start;
            return new ArrayList<>(items.subList(Math.min(from, items.size()), items.size()));
        });

        when(list.size(anyString())).thenAnswer(inv ->
                (long) lists.getOrDefault(inv.<String>getArgument(0), List.of()).size());

        doAnswer(inv -> { expiredKeys.add(inv.getArgument(0)); return true; })
                .when(redis).expire(anyString(), any(Duration.class));
        doAnswer(inv -> { deletedKeys.add(inv.getArgument(0)); return true; })
                .when(redis).delete(anyString());

        store = new SessionStore(redis, new ObjectMapper(), 10);
    }

    @Test
    void createWritesHashWithActiveStatusAndTtl() {
        String id = store.create("tenant-a");
        Map<String, String> snapshot = store.load(id);
        assertEquals("tenant-a", snapshot.get("tenant_id"));
        assertEquals("ACTIVE", snapshot.get("status"));   // NEW --create--> ACTIVE
        assertEquals(id, snapshot.get("session_id"));
        assertEquals("0", snapshot.get("message_count"));
        assertTrue(expiredKeys.contains("session:" + id));
    }

    @Test
    void transitionPersistsNextStateAndCanRecover() {
        String id = store.create("t");
        assertEquals(SessionFsm.State.IDLE, store.transition(id, SessionFsm.Event.IDLE_TTL));
        assertEquals(SessionFsm.State.TIMEOUT, store.transition(id, SessionFsm.Event.TIMEOUT_TTL));
        assertEquals("TIMEOUT", store.load(id).get("status"));
        assertEquals(SessionFsm.State.ACTIVE, store.transition(id, SessionFsm.Event.MESSAGE));
        assertEquals("ACTIVE", store.load(id).get("status"));
    }

    @Test
    void transitionOnMissingSessionIsRejected() {
        assertThrows(SessionFsm.IllegalTransitionException.class,
                () -> store.transition("not-exist", SessionFsm.Event.MESSAGE));
    }

    @Test
    void closedSessionRejectsFurtherTransitionsAndDropsMessages() {
        String id = store.create("t");
        store.appendMessage(id, "user", "hi", "闲聊", "user", 1);
        store.close(id);
        assertEquals("CLOSED", store.load(id).get("status"));
        assertThrows(SessionFsm.IllegalTransitionException.class,
                () -> store.transition(id, SessionFsm.Event.MESSAGE));
        assertTrue(deletedKeys.contains("session:" + id + ":messages"));
    }

    @Test
    void appendMessageUpdatesCountAndKeepsOrder() {
        String id = store.create("t");
        store.appendMessage(id, "user", "第一轮问题", "知识问答", "user", 12);
        store.appendMessage(id, "assistant", "第一轮回答", "知识问答", "llm", 30);
        assertEquals("2", store.load(id).get("message_count"));
        List<String> items = lists.get("session:" + id + ":messages");
        assertEquals(2, items.size());
        assertTrue(items.get(0).contains("第一轮问题"));
    }

    @Test
    void contextReturnsOnlyLastKTurns() {
        String id = store.create("t");
        for (int i = 1; i <= 12; i++) {
            store.appendMessage(id, "user", "第" + i + "轮", "知识问答", "user", 0);
        }
        List<Map<String, Object>> ctx = store.context(id, 5);
        assertEquals(5, ctx.size());
        assertEquals("第8轮", ctx.get(0).get("content"));   // 最近 5 轮：8~12
        assertEquals("第12轮", ctx.get(4).get("content"));
    }

    @Test
    void contextDefaultsToConfiguredWindow() {
        String id = store.create("t");
        for (int i = 1; i <= 12; i++) {
            store.appendMessage(id, "user", "m" + i, "知识问答", "user", 0);
        }
        assertEquals(10, store.context(id).size());
    }

    @Test
    void corruptedMessageIsSkippedNotFatal() {
        String id = store.create("t");
        lists.computeIfAbsent("session:" + id + ":messages", k -> new ArrayList<>()).add("{not-json");
        store.appendMessage(id, "user", "正常消息", "知识问答", "user", 0);
        List<Map<String, Object>> ctx = store.context(id, 10);
        assertEquals(1, ctx.size());                        // 损坏条目被跳过，不整体失败
        assertEquals("正常消息", ctx.get(0).get("content"));
    }

    @Test
    void legacySessionWithoutStatusIsTreatedAsActive() {
        // R4-01 之前写入的 Hash 没有 status 字段：会话存在即视为活跃，不判非法迁移
        String id = "legacy-1";
        hashes.put("session:" + id, new LinkedHashMap<>(Map.of("tenant_id", "t")));
        assertEquals(SessionFsm.State.ACTIVE, store.transition(id, SessionFsm.Event.MESSAGE));
        assertEquals("ACTIVE", store.load(id).get("status"));
    }

    @Test
    void messageRefreshExtendsTtl() {
        String id = store.create("t");
        expiredKeys.clear();
        store.appendMessage(id, "user", "继续聊", "知识问答", "user", 0);
        assertTrue(expiredKeys.contains("session:" + id));
        assertTrue(expiredKeys.contains("session:" + id + ":messages"));
    }
}
