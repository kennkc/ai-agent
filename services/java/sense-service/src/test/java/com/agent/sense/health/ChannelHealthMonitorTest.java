package com.agent.sense.health;

import com.agent.sense.channel.ChannelRegistry;
import com.agent.sense.channel.SenseChannel;
import com.agent.sense.config.SenseProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R2-09 渠道健康探针 / 隔离 / 恢复单测
 */
class ChannelHealthMonitorTest {

    static class FakeChannel implements SenseChannel {
        private final ChannelType type;
        private boolean healthy = true;
        private boolean available = true;
        private int collectCalls = 0;

        FakeChannel(ChannelType type) { this.type = type; }

        void setHealthy(boolean value) { this.healthy = value; }
        void setAvailable(boolean value) { this.available = value; }
        int collectCalls() { return collectCalls; }

        @Override public ChannelType type() { return type; }
        @Override public boolean register(Map<String, String> config) { return true; }
        @Override public boolean healthy() { return healthy; }
        @Override public boolean available() { return available; }
        @Override public void close() { }

        @Override
        public CollectResult collect(CollectRequest request) {
            collectCalls++;
            CollectResult result = new CollectResult();
            result.setBatchId("batch-" + collectCalls);
            result.setSourceChannel(type.name());
            result.setContent("fake channel content for " + type.name());
            result.setItemCount(1);
            result.setQualityScore(0.9);
            result.setAccepted(true);
            return result;
        }
    }

    private SenseProperties properties() {
        SenseProperties properties = new SenseProperties();
        properties.getHealth().setDegradeAfter(1);
        properties.getHealth().setDownAfter(3);
        return properties;
    }

    @Test
    void degradesThenIsolatesAfterConsecutiveFailures() {
        FakeChannel channel = new FakeChannel(SenseChannel.ChannelType.TOUCH);
        ChannelRegistry registry = new ChannelRegistry(List.of(channel));
        ChannelHealthMonitor monitor = new ChannelHealthMonitor(registry, properties());

        monitor.probe(channel);
        assertEquals(ChannelHealthMonitor.Status.UP, monitor.status(SenseChannel.ChannelType.TOUCH));

        channel.setHealthy(false);
        monitor.probe(channel);
        assertEquals(ChannelHealthMonitor.Status.DEGRADED, monitor.status(SenseChannel.ChannelType.TOUCH));
        assertFalse(monitor.isIsolated(SenseChannel.ChannelType.TOUCH));

        monitor.probe(channel);
        monitor.probe(channel);
        assertEquals(ChannelHealthMonitor.Status.DOWN, monitor.status(SenseChannel.ChannelType.TOUCH));
        assertTrue(monitor.isIsolated(SenseChannel.ChannelType.TOUCH));
    }

    @Test
    void recoversAutomaticallyAndCountsRecovery() {
        FakeChannel channel = new FakeChannel(SenseChannel.ChannelType.TOUCH);
        ChannelRegistry registry = new ChannelRegistry(List.of(channel));
        ChannelHealthMonitor monitor = new ChannelHealthMonitor(registry, properties());

        channel.setHealthy(false);
        for (int i = 0; i < 3; i++) monitor.probe(channel);
        assertTrue(monitor.isIsolated(SenseChannel.ChannelType.TOUCH));

        channel.setHealthy(true);
        monitor.probe(channel);
        assertEquals(ChannelHealthMonitor.Status.UP, monitor.status(SenseChannel.ChannelType.TOUCH));
        assertFalse(monitor.isIsolated(SenseChannel.ChannelType.TOUCH));
        assertEquals(1, monitor.recoveries());
    }

    @Test
    void reservedChannelIsDownEvenWhenProbeThrows() {
        FakeChannel channel = new FakeChannel(SenseChannel.ChannelType.AUDIO);
        channel.setAvailable(false);
        ChannelRegistry registry = new ChannelRegistry(List.of(channel));
        ChannelHealthMonitor monitor = new ChannelHealthMonitor(registry, properties());
        monitor.probe(channel);
        assertEquals(ChannelHealthMonitor.Status.DOWN, monitor.status(SenseChannel.ChannelType.AUDIO));
        assertTrue(monitor.isIsolated(SenseChannel.ChannelType.AUDIO));
    }

    @Test
    void snapshotContainsIsolationFields() {
        FakeChannel channel = new FakeChannel(SenseChannel.ChannelType.TOUCH);
        ChannelRegistry registry = new ChannelRegistry(List.of(channel));
        ChannelHealthMonitor monitor = new ChannelHealthMonitor(registry, properties());
        monitor.probe(channel);
        @SuppressWarnings("unchecked")
        Map<String, Object> touch = (Map<String, Object>) monitor.snapshot().get("TOUCH");
        assertEquals("UP", touch.get("status"));
        assertEquals(false, touch.get("isolated"));
        assertTrue(touch.containsKey("consecutive_failures"));
    }
}
