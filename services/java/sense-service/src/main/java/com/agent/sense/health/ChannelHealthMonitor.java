package com.agent.sense.health;

import com.agent.sense.channel.ChannelRegistry;
import com.agent.sense.channel.SenseChannel;
import com.agent.sense.config.SenseProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 渠道健康监控（R2-09）
 * - 周期探针：healthy() 连续失败达阈值 → DEGRADED → DOWN
 * - 自动隔离：DOWN 渠道被路由层跳过（降级到可用渠道）
 * - 自动恢复：探针恢复成功后立即解除隔离
 */
@Slf4j
@Component
public class ChannelHealthMonitor {

    public enum Status { UP, DEGRADED, DOWN }

    private final ChannelRegistry registry;
    private final SenseProperties properties;
    private final Map<SenseChannel.ChannelType, AtomicInteger> failures = new ConcurrentHashMap<>();
    private final Map<SenseChannel.ChannelType, Status> statuses = new ConcurrentHashMap<>();
    private final Map<SenseChannel.ChannelType, Long> lastProbeAt = new ConcurrentHashMap<>();
    private final Map<SenseChannel.ChannelType, Long> lastRecoverAt = new ConcurrentHashMap<>();
    private final AtomicInteger recoveries = new AtomicInteger();

    public ChannelHealthMonitor(ChannelRegistry registry, SenseProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        probeAll();
    }

    @Scheduled(fixedDelayString = "${sense.health.probe-ms:30000}", initialDelayString = "${sense.health.initial-delay-ms:10000}")
    public void probeAll() {
        for (SenseChannel channel : registry.all()) {
            probe(channel);
        }
    }

    public Status probe(SenseChannel channel) {
        SenseChannel.ChannelType type = channel.type();
        boolean ok;
        try {
            ok = channel.healthy();
        } catch (Exception e) {
            ok = false;
            log.debug("channel {} probe threw: {}", type, e.getMessage());
        }
        lastProbeAt.put(type, System.currentTimeMillis());
        AtomicInteger counter = failures.computeIfAbsent(type, k -> new AtomicInteger());
        Status previous = statuses.getOrDefault(type, Status.UP);
        Status now;
        if (ok && channel.available()) {
            if (previous != Status.UP) {
                recoveries.incrementAndGet();
                lastRecoverAt.put(type, System.currentTimeMillis());
                log.info("channel {} recovered: {} -> UP", type, previous);
            }
            counter.set(0);
            now = Status.UP;
        } else {
            int fail = counter.incrementAndGet();
            if (!channel.available() || fail >= properties.getHealth().getDownAfter()) now = Status.DOWN;
            else if (fail >= properties.getHealth().getDegradeAfter()) now = Status.DEGRADED;
            else now = Status.UP;
            if (now != previous) log.warn("channel {} degraded: {} -> {} (failures={})", type, previous, now, fail);
        }
        statuses.put(type, now);
        return now;
    }

    public Status status(SenseChannel.ChannelType type) {
        return statuses.getOrDefault(type, Status.UP);
    }

    /** 隔离判定：DOWN 渠道不参与路由 */
    public boolean isIsolated(SenseChannel.ChannelType type) {
        return status(type) == Status.DOWN;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (SenseChannel channel : registry.all()) {
            SenseChannel.ChannelType type = channel.type();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("status", status(type).name());
            item.put("isolated", isIsolated(type));
            AtomicInteger counter = failures.get(type);
            item.put("consecutive_failures", counter == null ? 0 : counter.get());
            item.put("last_probe_at", lastProbeAt.getOrDefault(type, 0L));
            item.put("last_recover_at", lastRecoverAt.getOrDefault(type, 0L));
            item.put("available", channel.available());
            out.put(type.name(), item);
        }
        return out;
    }

    public Map<SenseChannel.ChannelType, Status> allStatuses() {
        Map<SenseChannel.ChannelType, Status> copy = new EnumMap<>(SenseChannel.ChannelType.class);
        copy.putAll(statuses);
        return copy;
    }

    public int recoveries() { return recoveries.get(); }
}
