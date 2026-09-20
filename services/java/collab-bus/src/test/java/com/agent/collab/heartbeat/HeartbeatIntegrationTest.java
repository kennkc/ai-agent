package com.agent.collab.heartbeat;

import com.agent.collab.domain.DomainService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R-MC01-03 真实 PG 集成路径；COLLAB_IT=true 时启用。 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "COLLAB_IT", matches = "true")
class HeartbeatIntegrationTest {

    @Autowired
    DomainService domainService;

    @Autowired
    HeartbeatService heartbeatService;

    private String newDomain() {
        String tenant = "it-heartbeat";
        return domainService.create(tenant, "heartbeat-" + UUID.randomUUID().toString().substring(0, 6)).domainId();
    }

    @Test
    void threeMembersAggregateToExpectedWeightedProgress() {
        String tenant = "it-heartbeat";
        String domainId = newDomain();
        Instant t0 = Instant.now();

        heartbeatService.reportAt(tenant, domainId, "a", 90d, "working", 1d, t0);
        heartbeatService.reportAt(tenant, domainId, "b", 60d, "working", 1d, t0.plusSeconds(6));
        heartbeatService.reportAt(tenant, domainId, "c", 30d, "working", 1d, t0.plusSeconds(12));

        Map<String, Object> aggregate = heartbeatService.aggregateAt(tenant, domainId, t0.plusSeconds(13));

        assertEquals(60.0d, (Double) aggregate.get("progress"), 0.001d);
        assertEquals(3, aggregate.get("member_count"));
    }

    @Test
    void staleMemberRemainsVisible() {
        String tenant = "it-heartbeat";
        String domainId = newDomain();
        Instant t0 = Instant.now();

        heartbeatService.reportAt(tenant, domainId, "lost", 42d, "working", 1d, t0);
        Map<String, Object> aggregate = heartbeatService.aggregateAt(tenant, domainId, t0.plusSeconds(31));

        assertEquals(1L, aggregate.get("stale_count"));
        assertTrue(String.valueOf(aggregate.get("members")).contains("stale"));
    }

    @Test
    void throttledReportStillUpdatesLatestValue() {
        String tenant = "it-heartbeat";
        String domainId = newDomain();
        Instant t0 = Instant.now();

        Map<String, Object> first = heartbeatService.reportAt(tenant, domainId, "a", 10d, "working", 1d, t0);
        Map<String, Object> second = heartbeatService.reportAt(tenant, domainId, "a", 80d, "working", 1d, t0.plusSeconds(1));

        assertEquals(false, first.get("throttled"));
        assertEquals(true, second.get("throttled"));
        Map<String, Object> aggregate = heartbeatService.aggregateAt(tenant, domainId, t0.plusSeconds(2));
        assertEquals(80.0d, (Double) aggregate.get("progress"), 0.001d);
    }
}