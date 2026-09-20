package com.agent.collab.delivery;

import com.agent.collab.common.BizException;
import com.agent.collab.common.ErrorCode;
import com.agent.collab.domain.CollabDomain;
import com.agent.collab.domain.DomainRepository;
import com.agent.collab.nats.NatsConnection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R-MC01-02 单元测试：发布前置校验 + 消费幂等 + 降级语义。
 *
 * <p>对应测试用例：TC-02（关闭域拒绝）、TC-04（重投幂等）、TC-06（失败进死信）。
 */
class DeliveryTest {

    private CollabDomain domain(String state) {
        return new CollabDomain("dom-1", "t1", "demo", state, 8, Instant.now(),
                CollabDomain.STATE_CLOSED.equals(state) ? Instant.now() : null);
    }

    // ─────────── 主题与类型校验 ───────────

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

    // ─────────── 关闭域拒绝（TC-02）───────────

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

    // ─────────── 消费幂等（TC-04 核心）───────────

    @Test
    void duplicateRequestIdIsDiscardedWithoutSideEffect() {
        IdempotencyRepository repo = mock(IdempotencyRepository.class);
        when(repo.available()).thenReturn(true);
        // 第一次标记成功（首次），第二次失败（重复）
        when(repo.markSeen("dom-1", "req-1", "m1")).thenReturn(true).thenReturn(false);
        IdempotentConsumer consumer = new IdempotentConsumer(repo, mock(MessagePublisher.class), 3);

        int[] executed = {0};
        IdempotentConsumer.Handler handler = payload -> executed[0]++;

        assertTrue(consumer.consume("t1", "dom-1", "req-1", "m1", "p", handler), "首次应执行");
        assertFalse(consumer.consume("t1", "dom-1", "req-1", "m1", "p", handler), "重投应被丢弃");

        // 关键断言：业务副作用只发生一次
        assertEquals(1, executed[0]);
        verify(repo, times(2)).markSeen("dom-1", "req-1", "m1");
    }

    @Test
    void failedHandlerKeepsIdempotencyMarkAndSurfacesError() {
        IdempotencyRepository repo = mock(IdempotencyRepository.class);
        when(repo.available()).thenReturn(true);
        when(repo.markSeen(anyString(), anyString(), anyString())).thenReturn(true);
        IdempotentConsumer consumer = new IdempotentConsumer(repo, mock(MessagePublisher.class), 3);

        IdempotentConsumer.Handler failing = payload -> {
            throw new IllegalStateException("boom");
        };

        BizException e = assertThrows(BizException.class,
                () -> consumer.consume("t1", "dom-1", "req-x", "m1", "p", failing));
        assertTrue(e.getMessage().contains("消息处理失败"));
        // 幂等标记保留（最多一次语义）：不因失败而回滚标记
        verify(repo, never()).cleanupOlderThanHours(any(Integer.class));
    }

    // ─────────── 降级语义 ───────────

    @Test
    void pgUnavailable_consumeFailsWith503NotSilent() {
        IdempotencyRepository repo = mock(IdempotencyRepository.class);
        when(repo.available()).thenReturn(false);
        when(repo.lastError()).thenReturn("connection refused");
        IdempotentConsumer consumer = new IdempotentConsumer(repo, mock(MessagePublisher.class), 3);

        BizException e = assertThrows(BizException.class,
                () -> consumer.consume("t1", "dom-1", "req-1", "m1", "p", payload -> { }));
        assertEquals(ErrorCode.AGENT_COLLAB_BUS_UNAVAILABLE, e.code());
        // 关键：PG 不可用时不得"无去重执行"（那会破坏幂等承诺）
        verify(repo, never()).markSeen(anyString(), anyString(), anyString());
    }

    @Test
    void retryMaxIsConfigurable() {
        IdempotentConsumer consumer = new IdempotentConsumer(
                mock(IdempotencyRepository.class), mock(MessagePublisher.class), 5);
        assertEquals(5, consumer.retryMax());
    }
}
