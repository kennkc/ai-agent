package com.agent.collab.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/** MC-01 business metrics exposed through /actuator/prometheus. */
@Component
public class CollabBusMetrics {
    private final MeterRegistry registry;
    private final AtomicInteger activeDomains = new AtomicInteger();
    private final AtomicInteger pendingHeartbeats = new AtomicInteger();

    public CollabBusMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("lifeform.collab.active.domains", activeDomains, AtomicInteger::get)
                .description("Active collaboration domains with running durable consumers")
                .register(registry);
        Gauge.builder("lifeform.collab.heartbeat.pending", pendingHeartbeats, AtomicInteger::get)
                .description("Heartbeat updates waiting for window flush")
                .register(registry);
    }

    public void messagePublished(String type) {
        registry.counter("lifeform.collab.messages", "direction", "published", "type", type).increment();
    }

    public void messageConsumed(String state) {
        registry.counter("lifeform.collab.messages", "direction", "consumed", "state", state).increment();
    }

    public void messageRetry() {
        registry.counter("lifeform.collab.consumer.retries").increment();
    }

    public void deadLetter() {
        registry.counter("lifeform.collab.deadletter").increment();
    }

    public void heartbeatReport(boolean throttled) {
        registry.counter("lifeform.collab.heartbeat.reports", "outcome", throttled ? "throttled" : "persisted").increment();
    }

    public void heartbeatFlushed(int count) {
        if (count > 0) {
            registry.counter("lifeform.collab.heartbeat.flushed").increment(count);
        }
    }

    public void activeDomainsChanged(int count) {
        activeDomains.set(Math.max(0, count));
    }

    public void pendingHeartbeatsChanged(int count) {
        pendingHeartbeats.set(Math.max(0, count));
    }
}