package com.agent.collab.domain;

import com.agent.collab.common.BizException;
import com.agent.collab.common.ErrorCode;
import com.agent.collab.delivery.ReliableConsumer;
import com.agent.collab.nats.NatsConnection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** R-MC01-01 单元测试：域生命周期、创建补偿与降级语义。 */
class DomainServiceTest {

    private DomainService service(DomainRepository repo, NatsConnection nats) {
        return new DomainService(repo, nats, mock(ReliableConsumer.class), 8, 24);
    }

    private DomainService service(DomainRepository repo, NatsConnection nats, ReliableConsumer consumer) {
        return new DomainService(repo, nats, consumer, 8, 24);
    }

    @Test
    void pgUnavailable_createFailsWith503NotSilent() {
        DomainRepository repo = mock(DomainRepository.class);
        when(repo.available()).thenReturn(false);
        when(repo.lastError()).thenReturn("connection refused");
        NatsConnection nats = mock(NatsConnection.class);

        BizException e = assertThrows(BizException.class, () -> service(repo, nats).create("t1", "demo"));
        assertEquals(ErrorCode.AGENT_COLLAB_BUS_UNAVAILABLE, e.code());
        assertTrue(e.getMessage().contains("PG 不可用"));
        verify(repo, never()).insert(any());
    }

    @Test
    void natsUnavailable_createFailsWith503AndDoesNotInsert() {
        DomainRepository repo = mock(DomainRepository.class);
        when(repo.available()).thenReturn(true);
        NatsConnection nats = mock(NatsConnection.class);
        when(nats.jetStreamAvailable()).thenReturn(false);

        BizException e = assertThrows(BizException.class, () -> service(repo, nats).create("t1", "demo"));
        assertEquals(ErrorCode.AGENT_COLLAB_BUS_UNAVAILABLE, e.code());
        assertTrue(e.getMessage().contains("JetStream"));
        verify(repo, never()).insert(any());
    }

    @Test
    void streamFailureMarksDomainFailedAndStopsConsumer() {
        DomainRepository repo = mock(DomainRepository.class);
        ReliableConsumer consumer = mock(ReliableConsumer.class);
        NatsConnection nats = mock(NatsConnection.class);
        when(repo.available()).thenReturn(true);
        when(nats.jetStreamAvailable()).thenReturn(true);
        when(nats.jetStreamManagement()).thenThrow(new IllegalStateException("stream unavailable"));

        BizException e = assertThrows(BizException.class,
                () -> service(repo, nats, consumer).create("t1", "demo"));

        assertEquals(ErrorCode.AGENT_COLLAB_BUS_UNAVAILABLE, e.code());
        verify(repo).insert(any(CollabDomain.class));
        verify(consumer).stopDomain(anyString());
        verify(repo).markFailed(anyString(), anyString());
    }

    @Test
    void missingOrCrossTenantDomain_getReturns404() {
        DomainRepository repo = mock(DomainRepository.class);
        when(repo.available()).thenReturn(true);
        when(repo.find(anyString(), anyString())).thenReturn(Optional.empty());
        NatsConnection nats = mock(NatsConnection.class);

        BizException e = assertThrows(BizException.class,
                () -> service(repo, nats).get("other-tenant", "dom-abc"));
        assertEquals(ErrorCode.AGENT_COLLAB_DOMAIN_NOT_FOUND, e.code());
        assertEquals(404, e.code().httpStatus());
    }

    @Test
    void closeIsIdempotent_forAlreadyClosedDomain() {
        DomainRepository repo = mock(DomainRepository.class);
        ReliableConsumer consumer = mock(ReliableConsumer.class);
        when(repo.available()).thenReturn(true);
        CollabDomain closed = new CollabDomain("dom-x", "t1", "demo",
                CollabDomain.STATE_CLOSED, 8, Instant.now(), Instant.now());
        when(repo.find("t1", "dom-x")).thenReturn(Optional.of(closed));
        NatsConnection nats = mock(NatsConnection.class);

        CollabDomain result = service(repo, nats, consumer).close("t1", "dom-x");

        assertEquals(CollabDomain.STATE_CLOSED, result.state());
        verify(repo, never()).close(anyString(), anyString());
        verify(consumer, never()).stopDomain(anyString());
    }

    @Test
    void closeTransitionsActiveDomainAndStopsConsumer() {
        DomainRepository repo = mock(DomainRepository.class);
        ReliableConsumer consumer = mock(ReliableConsumer.class);
        when(repo.available()).thenReturn(true);
        CollabDomain active = new CollabDomain("dom-y", "t1", "demo",
                CollabDomain.STATE_ACTIVE, 8, Instant.now(), null);
        CollabDomain closed = new CollabDomain("dom-y", "t1", "demo",
                CollabDomain.STATE_CLOSED, 8, Instant.now(), Instant.now());
        when(repo.find("t1", "dom-y")).thenReturn(Optional.of(active)).thenReturn(Optional.of(closed));
        NatsConnection nats = mock(NatsConnection.class);

        CollabDomain result = service(repo, nats, consumer).close("t1", "dom-y");

        assertEquals(CollabDomain.STATE_CLOSED, result.state());
        verify(repo).close("t1", "dom-y");
        verify(consumer).stopDomain("dom-y");
    }

    @Test
    void streamNamingFollowsDomainConvention() {
        assertEquals("COLLAB_DOM_ABC123", DomainService.streamName("dom-abc123"));
        assertEquals("collab.dom-abc123.>", DomainService.subjectPattern("dom-abc123"));
    }

    @Test
    void listDelegatesToRepositoryScopedByTenant() {
        DomainRepository repo = mock(DomainRepository.class);
        when(repo.available()).thenReturn(true);
        when(repo.listByTenant("t1")).thenReturn(List.of());
        NatsConnection nats = mock(NatsConnection.class);

        assertTrue(service(repo, nats).list("t1").isEmpty());
        verify(repo).listByTenant("t1");
    }
}