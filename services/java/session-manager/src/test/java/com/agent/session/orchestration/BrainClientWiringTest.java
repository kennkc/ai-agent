package com.agent.session.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.agent.session.bus.BusProxy;
import com.agent.session.controller.SessionController;
import com.agent.session.fsm.SessionStore;
import com.agent.session.kafka.KafkaEventPublisher;
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

/**
 * R4-06 端到端接线：{@code /ask} 经 {@link BrainClient} 走大脑层。
 *
 * <p>重点验证三件事：
 * <ol>
 *   <li>大脑层可用时，回答/来源/缺口/决策链/生成器标识**原样回传**；</li>
 *   <li>大脑层不可用时降级本地检索直出，但**必须标注 degraded + 原因**，不假装成功；</li>
 *   <li>多轮上下文被真正传给大脑层（不是空数组）。</li>
 * </ol>
 */
class BrainClientWiringTest {

    @SuppressWarnings("unchecked")
    private HashOperations<String, Object, Object> hashOps() {
        return mock(HashOperations.class);
    }

    private Map<Object, Object> sessionRecord() {
        Map<Object, Object> stored = new HashMap<>();
        stored.put("tenant_id", "tenant-a");
        stored.put("status", "ACTIVE");
        return stored;
    }

    @Test
    void askUsesBrainLayerAndReturnsSourcesGapChain() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hash = hashOps();
        @SuppressWarnings("unchecked") ListOperations<String, String> list = mock(ListOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        when(redis.opsForList()).thenReturn(list);
        when(hash.entries("session:s1")).thenReturn(sessionRecord());
        when(list.range(anyString(), anyLong(), anyLong())).thenReturn(List.of(
                "{\"role\":\"user\",\"content\":\"上一轮\"}"));

        NlpClient nlp = mock(NlpClient.class);
        when(nlp.recognize(anyString(), anyString(), anyString(), any(RequestBudget.class)))
                .thenReturn(Map.of("intent", "知识问答", "confidence", 0.9));

        BrainClient brain = mock(BrainClient.class);
        when(brain.ask(eq("你好"), eq("s1"), eq("tenant-a"), eq("知识问答"), anyDouble(), anyList(),
                any(RequestBudget.class)))
                .thenReturn(new BrainClient.BrainAnswer(true, "大脑层回答",
                        List.of(Map.of("title", "设计文档", "chunk_id", "c1")),
                        Map.of("coverage", 0.93, "sufficient", true),
                        List.of(Map.of("step", "plan"), Map.of("step", "generate")),
                        "template", true, List.of("llm_template_backend"), "dec-1"));

        SessionController controller = new SessionController(redis, mock(BusProxy.class),
                mock(KafkaEventPublisher.class), nlp, mock(BodyClient.class), new ObjectMapper(),
                new SessionStore(redis, new ObjectMapper()), brain);

        Map<String, Object> result = controller.ask("s1", "tenant-a", new SessionController.AskRequest("你好"));

        assertEquals("大脑层回答", result.get("answer"));
        assertEquals("知识问答", result.get("intent"));
        assertEquals("template", result.get("generator"));
        assertEquals("dec-1", result.get("decision_id"));
        assertEquals(true, result.get("degraded"));
        assertEquals(List.of("llm_template_backend"), result.get("degraded_reasons"));
        assertEquals(1, ((List<?>) result.get("sources")).size());
        assertEquals(2, ((List<?>) result.get("chain")).size());
        assertEquals(true, ((Map<?, ?>) result.get("gap")).get("sufficient"));
        verify(brain).ask(eq("你好"), eq("s1"), eq("tenant-a"), eq("知识问答"), anyDouble(), anyList(),
                any(RequestBudget.class));
    }

    @Test
    void contextIsPassedToBrainLayer() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hash = hashOps();
        @SuppressWarnings("unchecked") ListOperations<String, String> list = mock(ListOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        when(redis.opsForList()).thenReturn(list);
        when(hash.entries("session:s1")).thenReturn(sessionRecord());
        when(list.range(anyString(), anyLong(), anyLong())).thenReturn(List.of(
                "{\"role\":\"user\",\"content\":\"第一轮问题\"}"));

        BrainClient brain = mock(BrainClient.class);
        when(brain.ask(anyString(), anyString(), anyString(), anyString(), anyDouble(), anyList(),
                any(RequestBudget.class)))
                .thenReturn(new BrainClient.BrainAnswer(true, "答", List.of(), Map.of(), List.of(),
                        "template", true, List.of(), "d"));

        SessionController controller = new SessionController(redis, mock(BusProxy.class),
                mock(KafkaEventPublisher.class), mock(NlpClient.class), mock(BodyClient.class),
                new ObjectMapper(), new SessionStore(redis, new ObjectMapper()), brain);
        controller.ask("s1", "tenant-a", new SessionController.AskRequest("继续"));

        @SuppressWarnings("unchecked") org.mockito.ArgumentCaptor<List<Map<String, Object>>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(brain).ask(anyString(), anyString(), anyString(), anyString(), anyDouble(), captor.capture(),
                any(RequestBudget.class));
        assertEquals(1, captor.getValue().size());                     // 上下文真的传了
        assertEquals("第一轮问题", captor.getValue().get(0).get("content"));
    }

    @Test
    void fallsBackToLocalRetrievalAndMarksDegradedWhenBrainUnavailable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hash = hashOps();
        @SuppressWarnings("unchecked") ListOperations<String, String> list = mock(ListOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        when(redis.opsForList()).thenReturn(list);
        when(hash.entries("session:s1")).thenReturn(sessionRecord());
        when(list.range(anyString(), anyLong(), anyLong())).thenReturn(List.of());

        NlpClient nlp = mock(NlpClient.class);
        when(nlp.recognize(anyString(), anyString(), anyString(), any(RequestBudget.class)))
                .thenReturn(Map.of("intent", "知识问答", "confidence", 0.8));
        BodyClient body = mock(BodyClient.class);
        when(body.retrieve(eq("问题"), eq("tenant-a"), any(RequestBudget.class)))
                .thenReturn(List.of(Map.of("title", "Doc", "content", "本地命中片段")));
        BrainClient brain = mock(BrainClient.class);
        when(brain.ask(anyString(), anyString(), anyString(), anyString(), anyDouble(), anyList(),
                any(RequestBudget.class)))
                .thenReturn(new BrainClient.BrainAnswer(false, "", List.of(), Map.of(), List.of(),
                        "", false, List.of("brain_unavailable: connection refused"), ""));

        SessionController controller = new SessionController(redis, mock(BusProxy.class),
                mock(KafkaEventPublisher.class), nlp, body, new ObjectMapper(),
                new SessionStore(redis, new ObjectMapper()), brain);

        Map<String, Object> result = controller.ask("s1", "tenant-a", new SessionController.AskRequest("问题"));

        assertTrue(String.valueOf(result.get("answer")).contains("本地命中片段"));
        assertEquals("local-retrieval", result.get("generator"));       // 生成器如实标注
        assertEquals(true, result.get("degraded"));
        assertEquals(List.of("brain_unavailable: connection refused"), result.get("degraded_reasons"));
    }

    @Test
    void withoutBrainClientAskStillWorksAndMarksNotConfigured() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hash = hashOps();
        @SuppressWarnings("unchecked") ListOperations<String, String> list = mock(ListOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        when(redis.opsForList()).thenReturn(list);
        when(hash.entries("session:s1")).thenReturn(sessionRecord());
        BodyClient body = mock(BodyClient.class);
        when(body.retrieve(anyString(), anyString(), any(RequestBudget.class)))
                .thenReturn(List.of(Map.of("content", "片段")));

        // 走「无大脑层客户端」的兼容构造
        SessionController controller = new SessionController(redis, mock(BusProxy.class),
                mock(KafkaEventPublisher.class), mock(NlpClient.class), body, new ObjectMapper(),
                new SessionStore(redis, new ObjectMapper()));

        Map<String, Object> result = controller.ask("s1", "tenant-a", new SessionController.AskRequest("问题"));
        assertEquals("local-retrieval", result.get("generator"));
        assertEquals(List.of("brain_client_not_configured"), result.get("degraded_reasons"));
    }
}
