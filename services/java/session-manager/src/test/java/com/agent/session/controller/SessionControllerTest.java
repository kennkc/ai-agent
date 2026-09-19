package com.agent.session.controller;

import com.agent.session.common.BizException;
import com.agent.session.common.ErrorCode;
import com.agent.session.kafka.KafkaEventPublisher;
import com.agent.session.bus.BusProxy;
import com.agent.session.orchestration.BodyClient;
import com.agent.session.orchestration.NlpClient;
import com.agent.session.orchestration.RequestBudget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionControllerTest {
    @SuppressWarnings("unchecked")
    private HashOperations<String, Object, Object> hashOps() { return mock(HashOperations.class); }

    @Test
    void createUsesVerifiedTenantHeader() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hash = hashOps();
        when(redis.opsForHash()).thenReturn(hash);
        BusProxy bus = mock(BusProxy.class);
        when(bus.request(anyString(), anyMap())).thenReturn("pong");
        SessionController controller = new SessionController(redis, bus, mock(KafkaEventPublisher.class),
                mock(NlpClient.class), mock(BodyClient.class), new ObjectMapper());
        Map<String, String> result = controller.create("tenant-a");
        assertNotNull(result.get("session_id"));
        verify(hash).put(anyString(), eq("tenant_id"), eq("tenant-a"));
    }

    @Test
    void rejectsCrossTenantRead() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hash = hashOps();
        when(redis.opsForHash()).thenReturn(hash);
        Map<Object, Object> stored = new HashMap<>();
        stored.put("tenant_id", "tenant-a");
        when(hash.entries("session:s1")).thenReturn(stored);
        SessionController controller = new SessionController(redis, mock(BusProxy.class), mock(KafkaEventPublisher.class),
                mock(NlpClient.class), mock(BodyClient.class), new ObjectMapper());
        BizException ex = assertThrows(BizException.class, () -> controller.get("s1", "tenant-b"));
        assertEquals(ErrorCode.AGENT_FORBIDDEN, ex.errorCode());
    }

    @Test
    void askReturnsAnswerFromLocalRetrieval() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hash = hashOps();
        @SuppressWarnings("unchecked") ListOperations<String, String> list = mock(ListOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        when(redis.opsForList()).thenReturn(list);
        Map<Object, Object> stored = new HashMap<>();
        stored.put("tenant_id", "tenant-a");
        when(hash.entries("session:s1")).thenReturn(stored);
        NlpClient nlp = mock(NlpClient.class);
        // 三跳（意图 / 大脑 / 检索）现在共享同一个 RequestBudget，故签名多一个预算参数
        when(nlp.recognize(eq("hello"), eq("s1"), eq("tenant-a"), any(RequestBudget.class)))
                .thenReturn(Map.of("intent", "knowledge"));
        BodyClient body = mock(BodyClient.class);
        when(body.retrieve(eq("hello"), eq("tenant-a"), any(RequestBudget.class)))
                .thenReturn(List.of(Map.of("title", "Doc", "content", "hello world")));
        SessionController controller = new SessionController(redis, mock(BusProxy.class), mock(KafkaEventPublisher.class), nlp, body, new ObjectMapper());
        Map<String, Object> result = controller.ask("s1", "tenant-a", new SessionController.AskRequest("hello"));
        assertEquals("knowledge", result.get("intent"));
        assertTrue(String.valueOf(result.get("answer")).contains("hello"));
        verify(list, times(2)).rightPush(anyString(), anyString());
    }
}

