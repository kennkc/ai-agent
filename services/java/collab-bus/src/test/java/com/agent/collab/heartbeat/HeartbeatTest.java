package com.agent.collab.heartbeat;

import com.agent.collab.common.BizException;
import com.agent.collab.common.ErrorCode;
import com.agent.collab.domain.CollabDomain;
import com.agent.collab.domain.DomainRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** R-MC01-03 单元测试：窗口合并、聚合、stale 与降级语义。 */
class HeartbeatTest {

    private static final Instant T0 = Instant.parse("2026-09-20T06:00:00Z");

    private static CollabDomain domain(String state) {
        return new CollabDomain("dom-1", "t1", "demo", state, 8, T0, null);
    }

    private HeartbeatService service(HeartbeatRepository repository, DomainRepository domains) {
        return new HeartbeatService(repository, domains, 5000, 30000);
    }

    private static void commonStubs(HeartbeatRepository repository, DomainRepository domains) {
        when(repository.available()).thenReturn(true);
        when(domains.available()).thenReturn(true);
        when(domains.find("t1", "dom-1")).thenReturn(Optional.of(domain(CollabDomain.STATE_ACTIVE)));
    }

    @Test
    void equalWeightAggregateMatchesArithmeticMean() {
        HeartbeatRepository repository = mock(HeartbeatRepository.class);
        DomainRepository domains = mock(DomainRepository.class);
        commonStubs(repository, domains);
        when(repository.list("dom-1")).thenReturn(List.of(
                heartbeat("a", 90, 1, T0),
                heartbeat("b", 60, 1, T0),
                heartbeat("c", 30, 1, T0)));

        Map<String, Object> result = service(repository, domains).aggregateAt("t1", "dom-1", T0.plusSeconds(1));

        assertEquals(60.0d, (Double) result.get("progress"), 0.001d);
        assertEquals(3, result.get("member_count"));
        assertEquals(0L, result.get("stale_count"));
    }

    @Test
    void weightedAggregateUsesEachMemberWeight() {
        HeartbeatRepository repository = mock(HeartbeatRepository.class);
        DomainRepository domains = mock(DomainRepository.class);
        commonStubs(repository, domains);
        when(repository.list("dom-1")).thenReturn(List.of(
                heartbeat("a", 90, 3, T0),
                heartbeat("b", 30, 1, T0)));

        Map<String, Object> result = service(repository, domains).aggregateAt("t1", "dom-1", T0.plusSeconds(1));

        assertEquals(75.0d, (Double) result.get("progress"), 0.001d);
        assertEquals(4.0d, (Double) result.get("total_weight"), 0.001d);
    }

    @Test
    void highFrequencyReportIsQueuedWithoutImmediateSecondWrite() {
        HeartbeatRepository repository = mock(HeartbeatRepository.class);
        DomainRepository domains = mock(DomainRepository.class);
        commonStubs(repository, domains);
        HeartbeatService service = service(repository, domains);

        service.reportAt("t1", "dom-1", "a", 40d, "working", 1d, T0);
        Map<String, Object> result = service.reportAt("t1", "dom-1", "a", 88d, "working", 1d, T0.plusSeconds(1));

        assertTrue((Boolean) result.get("throttled"));
        assertFalse((Boolean) result.get("persisted"));
        assertEquals(1, service.status().get("pending_updates"));
        verify(repository, times(1)).upsert(argThat(item -> item.progress() == 40d));
    }

    @Test
    void windowFlushPersistsLatestQueuedValue() {
        HeartbeatRepository repository = mock(HeartbeatRepository.class);
        DomainRepository domains = mock(DomainRepository.class);
        commonStubs(repository, domains);
        HeartbeatService service = service(repository, domains);

        service.reportAt("t1", "dom-1", "a", 40d, "working", 1d, T0);
        service.reportAt("t1", "dom-1", "a", 88d, "working", 1d, T0.plusSeconds(1));
        service.flushPendingAt(T0.plusSeconds(5));

        verify(repository).upsert(argThat(item ->
                item.progress() == 88d && item.throttled() && item.updatedAt().equals(T0.plusSeconds(5))));
        assertEquals(0, service.status().get("pending_updates"));
    }

    @Test
    void aggregateIncludesQueuedValueBeforeFlush() {
        HeartbeatRepository repository = mock(HeartbeatRepository.class);
        DomainRepository domains = mock(DomainRepository.class);
        commonStubs(repository, domains);
        when(repository.list("dom-1")).thenReturn(List.of(heartbeat("a", 40, 1, T0)));
        HeartbeatService service = service(repository, domains);

        service.reportAt("t1", "dom-1", "a", 40d, "working", 1d, T0);
        service.reportAt("t1", "dom-1", "a", 88d, "working", 1d, T0.plusSeconds(1));
        Map<String, Object> aggregate = service.aggregateAt("t1", "dom-1", T0.plusSeconds(2));

        assertEquals(88.0d, (Double) aggregate.get("progress"), 0.001d);
    }

    @Test
    void reportOutsideWindowIsImmediateAndNotThrottled() {
        HeartbeatRepository repository = mock(HeartbeatRepository.class);
        DomainRepository domains = mock(DomainRepository.class);
        commonStubs(repository, domains);
        HeartbeatService service = service(repository, domains);

        service.reportAt("t1", "dom-1", "a", 40d, "working", 1d, T0);
        Map<String, Object> result = service.reportAt("t1", "dom-1", "a", 88d, "working", 1d, T0.plusSeconds(5));

        assertEquals(false, result.get("throttled"));
        assertEquals(true, result.get("persisted"));
        verify(repository, times(2)).upsert(argThat(item -> item.memberId().equals("a")));
    }

    @Test
    void staleMemberIsRetainedAndExposedInAggregate() {
        HeartbeatRepository repository = mock(HeartbeatRepository.class);
        DomainRepository domains = mock(DomainRepository.class);
        commonStubs(repository, domains);
        when(repository.list("dom-1")).thenReturn(List.of(heartbeat("lost", 70, 1, T0)));

        Map<String, Object> result = service(repository, domains)
                .aggregateAt("t1", "dom-1", T0.plusSeconds(31));

        assertEquals(1L, result.get("stale_count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members = (List<Map<String, Object>>) result.get("members");
        assertEquals("stale", members.get(0).get("state"));
        assertEquals(Boolean.TRUE, members.get(0).get("stale"));
        assertEquals(70.0d, (Double) result.get("progress"), 0.001d);
    }

    @Test
    void invalidProgressIsRejectedBeforePersistence() {
        HeartbeatRepository repository = mock(HeartbeatRepository.class);
        DomainRepository domains = mock(DomainRepository.class);
        commonStubs(repository, domains);

        BizException error = assertThrows(BizException.class,
                () -> service(repository, domains).reportAt("t1", "dom-1", "a", 101d, "working", 1d, T0));

        assertEquals(ErrorCode.AGENT_COLLAB_HEARTBEAT_INVALID, error.code());
        verify(repository, never()).upsert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void closedDomainRejectsHeartbeat() {
        HeartbeatRepository repository = mock(HeartbeatRepository.class);
        DomainRepository domains = mock(DomainRepository.class);
        when(repository.available()).thenReturn(true);
        when(domains.available()).thenReturn(true);
        when(domains.find("t1", "dom-1")).thenReturn(Optional.of(domain(CollabDomain.STATE_CLOSED)));

        BizException error = assertThrows(BizException.class,
                () -> service(repository, domains).reportAt("t1", "dom-1", "a", 10d, "working", 1d, T0));

        assertEquals(ErrorCode.AGENT_COLLAB_DOMAIN_CLOSED, error.code());
        verify(repository, never()).upsert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void pgUnavailableFailsLoudly() {
        HeartbeatRepository repository = mock(HeartbeatRepository.class);
        DomainRepository domains = mock(DomainRepository.class);
        when(repository.available()).thenReturn(false);
        when(repository.lastError()).thenReturn("connection refused");

        BizException error = assertThrows(BizException.class,
                () -> service(repository, domains).aggregateAt("t1", "dom-1", T0));

        assertEquals(ErrorCode.AGENT_COLLAB_BUS_UNAVAILABLE, error.code());
    }

    private static MemberHeartbeat heartbeat(String memberId, double progress, double weight, Instant at) {
        return new MemberHeartbeat("dom-1", memberId, progress, "working", weight, at, at, false);
    }
}