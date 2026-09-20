package com.agent.collab.delivery;

import com.agent.collab.common.BizException;
import com.agent.collab.common.ErrorCode;
import com.agent.collab.domain.CollabDomain;
import com.agent.collab.domain.DomainRepository;
import com.agent.collab.nats.NatsConnection;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R-MC01-02 单元测试：发布前置校验、投递状态机、重投与死信。
 */
class DeliveryTest {

    private CollabDomain domain(String state) {
        return new CollabDomain("dom-1", "t1", "demo", state, 8, java.time.Instant.now(), null);
    }

    // ─────────── 主题命名与关闭域拒绝 ───────────

    @Test
    void subjectRejectsUnknownMessageType() {
        BizException e = assertThrows(BizException.class,
                () -> MessagePublisher.subject("dom-1", "unknown-type", "m1"));
        assertEquals(ErrorCode.AGENT_COLLAB_HEARTBEAT_INVALID, e.code());
        assertEquals(400, e.code().httpStatus());
    }

    @Test
    void subjectFollowsDomainConvention() {
        assertEquals("collab.dom-1.dispatch.agent-a", MessagePublisher.subject("dom-1", "dispatch", "agent-a"));
        assertEquals("collab.dom-1.heartbeat.-", MessagePublisher.subject("dom-1", "heartbeat", null));
    }

    @Test
    void publishToClosedDomainIsRejectedWith409() {
        DomainRepository domains = mock(DomainRepository.class);
        when(domains.find("t1", "dom-1")).thenReturn(Optional.of(domain(CollabDomain.STATE_CLOSED)));
        MessagePublisher publisher = new MessagePublisher(mock(NatsConnection.class), domains);

        BizException e = assertThrows(BizException.class,
                () -> publisher.publish("t1", "dom-1", "dispatch", "m1", "req-1", "payload"));
        assertEquals(ErrorCode.AGENT_COLLAB_DOMAIN_CLOSED, e.code());
        assertEquals(409, e.code().httpStatus());
    }

    @Test
    void publishToMissingOrCrossTenantDomainIs404() {
        DomainRepository domains = mock(DomainRepository.class);
        when(domains.find(anyString(), anyString())).thenReturn(Optional.empty());
        MessagePublisher publisher = new MessagePublisher(mock(NatsConnection.class), domains);

        BizException e = assertThrows(BizException.class,
                () -> publisher.publish("other", "dom-1", "dispatch", "m1", "req-1", "payload"));
        assertEquals(ErrorCode.AGENT_COLLAB_DOMAIN_NOT_FOUND, e.code());
    }

    // ─────────── 投递状态机 ───────────

    @Test
    void firstClaimProcessesAndMarksProcessed() {
        IdempotencyRepository repo = mock(IdempotencyRepository.class);
        MessagePublisher publisher = mock(MessagePublisher.class);
        when(repo.available()).thenReturn(true);
        when(repo.claim("dom-1", "req-1", "m1", 4))
                .thenReturn(new IdempotencyRepository.ClaimResult(
                        IdempotencyRepository.ClaimState.CLAIMED, 1));
        IdempotentConsumer consumer = new IdempotentConsumer(repo, publisher, 3);
        int[] executed = {0};

        IdempotentConsumer.DeliveryResult result = consumer.deliver(
                "t1", "dom-1", "req-1", "m1", "p", payload -> executed[0]++);

        assertEquals(IdempotentConsumer.DeliveryState.PROCESSED, result.state());
        assertEquals(1, executed[0]);
        verify(repo).markProcessed("dom-1", "req-1");
    }

    @Test
    void alreadyProcessedIsAcknowledgedWithoutSideEffect() {
        IdempotencyRepository repo = mock(IdempotencyRepository.class);
        when(repo.available()).thenReturn(true);
        when(repo.claim("dom-1", "req-1", "m1", 4))
                .thenReturn(new IdempotencyRepository.ClaimResult(
                        IdempotencyRepository.ClaimState.PROCESSED, 1));
        IdempotentConsumer consumer = new IdempotentConsumer(repo, mock(MessagePublisher.class), 3);

        IdempotentConsumer.DeliveryResult result = consumer.deliver(
                "t1", "dom-1", "req-1", "m1", "p", payload -> {
                    throw new AssertionError("重复消息不能执行业务");
                });

        assertEquals(IdempotentConsumer.DeliveryState.DUPLICATE, result.state());
        verify(repo, never()).markProcessed(anyString(), anyString());
    }

    @Test
    void retryableFailureMarksFailedAndSurfacesAttempts() {
        IdempotencyRepository repo = mock(IdempotencyRepository.class);
        when(repo.available()).thenReturn(true);
        when(repo.claim("dom-1", "req-x", "m1", 4))
                .thenReturn(new IdempotencyRepository.ClaimResult(
                        IdempotencyRepository.ClaimState.RETRY, 1));
        IdempotentConsumer consumer = new IdempotentConsumer(repo, mock(MessagePublisher.class), 3);

        BizException e = assertThrows(BizException.class,
                () -> consumer.deliver("t1", "dom-1", "req-x", "m1", "p", payload -> {
                    throw new IllegalStateException("boom");
                }));

        assertTrue(e.getMessage().contains("消息处理失败"));
        verify(repo).markFailed("dom-1", "req-x", "boom");
        verify(repo, never()).markDeadLetter(anyString(), anyString(), anyString());
    }

    @Test
    void finalFailurePublishesDeadLetterAndMarksDlq() {
        IdempotencyRepository repo = mock(IdempotencyRepository.class);
        MessagePublisher publisher = mock(MessagePublisher.class);
        when(repo.available()).thenReturn(true);
        when(repo.claim("dom-1", "req-final", "m1", 4))
                .thenReturn(new IdempotencyRepository.ClaimResult(
                        IdempotencyRepository.ClaimState.RETRY, 4));
        when(publisher.publishToDeadLetter(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Map.of("subject", "collab.dom-1.deadletter"));
        IdempotentConsumer consumer = new IdempotentConsumer(repo, publisher, 3);

        IdempotentConsumer.DeliveryResult result = consumer.deliver(
                "t1", "dom-1", "req-final", "m1", "p",
                payload -> { throw new IllegalStateException("still broken"); });

        assertEquals(IdempotentConsumer.DeliveryState.DEAD_LETTER, result.state());
        verify(repo).markFailed("dom-1", "req-final", "still broken");
        verify(publisher).publishToDeadLetter("t1", "dom-1", "req-final", "处理器最终失败：still broken", "p");
        verify(repo).markDeadLetter("dom-1", "req-final", "处理器最终失败：still broken");
    }

    @Test
    void exhaustedClaimGoesDirectlyToDeadLetter() {
        IdempotencyRepository repo = mock(IdempotencyRepository.class);
        MessagePublisher publisher = mock(MessagePublisher.class);
        when(repo.available()).thenReturn(true);
        when(repo.claim("dom-1", "req-exhausted", "m1", 4))
                .thenReturn(new IdempotencyRepository.ClaimResult(
                        IdempotencyRepository.ClaimState.EXHAUSTED, 5));
        when(publisher.publishToDeadLetter(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Map.of("subject", "collab.dom-1.deadletter"));
        IdempotentConsumer consumer = new IdempotentConsumer(repo, publisher, 3);

        IdempotentConsumer.DeliveryResult result = consumer.deliver(
                "t1", "dom-1", "req-exhausted", "m1", "p",
                payload -> { throw new AssertionError("超限消息不能再次执行"); });

        assertEquals(IdempotentConsumer.DeliveryState.DEAD_LETTER, result.state());
        verify(repo).markDeadLetter("dom-1", "req-exhausted",
                "重投次数已超过上限 attempts=5");
    }

    // ─────────── 降级语义 ───────────

    @Test
    void pgUnavailableFailsWith503BeforeClaim() {
        IdempotencyRepository repo = mock(IdempotencyRepository.class);
        when(repo.available()).thenReturn(false);
        when(repo.lastError()).thenReturn("connection refused");
        IdempotentConsumer consumer = new IdempotentConsumer(repo, mock(MessagePublisher.class), 3);

        BizException e = assertThrows(BizException.class,
                () -> consumer.deliver("t1", "dom-1", "req-1", "m1", "p", payload -> { }));

        assertEquals(ErrorCode.AGENT_COLLAB_BUS_UNAVAILABLE, e.code());
        verify(repo, never()).claim(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void retryMaxIsConfigurable() {
        IdempotentConsumer consumer = new IdempotentConsumer(
                mock(IdempotencyRepository.class), mock(MessagePublisher.class), 5);
        assertEquals(5, consumer.retryMax());
    }
}