package com.agent.sense.collect;

import com.agent.sense.channel.ChannelRegistry;
import com.agent.sense.channel.SenseChannel;
import com.agent.sense.config.SenseProperties;
import com.agent.sense.deadletter.DeadLetterStore;
import com.agent.sense.event.SenseEventPublisher;
import com.agent.sense.health.ChannelHealthMonitor;
import com.agent.sense.metrics.SenseMetrics;
import com.agent.sense.model.CollectedData;
import com.agent.sense.normalize.DataNormalizer;
import com.agent.sense.quality.QualityGate;
import com.agent.sense.staging.StagingStore;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 采集主流程（Phase 2 §5.1）
 * 触发 → 渠道路由（含故障降级）→ 采集（指数退避重试）→ 标准化五字段打标
 *      → 质检 → 隔离暂存（MinIO）→ 事件通知（lifeform.sense.collected）→ 指标登记
 * 重试耗尽 → 死信队列登记。
 */
@Slf4j
@Component
public class CollectPipeline {

    private final ChannelRegistry registry;
    private final ChannelHealthMonitor healthMonitor;
    private final DataNormalizer normalizer;
    private final QualityGate qualityGate;
    private final StagingStore stagingStore;
    private final SenseMetrics metrics;
    private final SenseEventPublisher eventPublisher;
    private final DeadLetterStore deadLetterStore;
    private final SenseProperties properties;

    public CollectPipeline(ChannelRegistry registry, ChannelHealthMonitor healthMonitor,
                           DataNormalizer normalizer, QualityGate qualityGate, StagingStore stagingStore,
                           SenseMetrics metrics, SenseEventPublisher eventPublisher,
                           DeadLetterStore deadLetterStore, SenseProperties properties) {
        this.registry = registry;
        this.healthMonitor = healthMonitor;
        this.normalizer = normalizer;
        this.qualityGate = qualityGate;
        this.stagingStore = stagingStore;
        this.metrics = metrics;
        this.eventPublisher = eventPublisher;
        this.deadLetterStore = deadLetterStore;
        this.properties = properties;
    }

    public CollectedData collect(SenseChannel.ChannelType requested, SenseChannel.CollectRequest request) {
        long started = System.currentTimeMillis();
        Route route = route(requested);
        if (route.channel == null) {
            return failFast(requested, request, "no available channel for " + requested);
        }
        SenseChannel.CollectRequest effective = request == null ? new SenseChannel.CollectRequest() : request;
        Attempt attempt = collectWithRetry(route.channel, effective);

        CollectedData data = normalizer.normalize(attempt.result, effective);
        data.setBatchId(attempt.result.getBatchId() == null ? UUID.randomUUID().toString() : attempt.result.getBatchId());
        data.setDegraded(route.degraded);
        data.setAttempts(attempt.attempts);
        data.setCollectTimeMs(System.currentTimeMillis() - started);

        if (!attempt.result.isAccepted()) {
            data.setStagingStatus(CollectedData.StagingStatus.REJECTED);
            data.setRejectReason(attempt.result.getError() == null ? "COLLECT_FAILED" : attempt.result.getError());
            data.setConfidence(0);
            data.setDeadLetterId(deadLetterStore.add(route.channel.type(), data.getTenantId(),
                    effective.getDataSource(), data.getRejectReason(), attempt.attempts, data.getMode()));
        } else {
            qualityGate.apply(data);
            if (data.getStagingStatus() == CollectedData.StagingStatus.REJECTED) {
                data.setDeadLetterId(deadLetterStore.add(route.channel.type(), data.getTenantId(),
                        effective.getDataSource(), "QUALITY_REJECTED:" + data.getRejectReason(),
                        attempt.attempts, data.getMode()));
            }
        }

        data.setStagingRef(stagingStore.put(data));
        metrics.record(route.channel.type(), data);
        if (data.getStagingStatus() == CollectedData.StagingStatus.ACCEPTED) {
            eventPublisher.publishCollected(data);
        }
        return data;
    }

    private Attempt collectWithRetry(SenseChannel channel, SenseChannel.CollectRequest request) {
        SenseProperties.Retry retryCfg = properties.getRetry();
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(Math.max(1, retryCfg.getMaxAttempts()))
                .intervalFunction(IntervalFunction.ofExponentialBackoff(
                        Duration.ofMillis(retryCfg.getInitialMs()), retryCfg.getMultiplier(),
                        Duration.ofMillis(retryCfg.getMaxMs())))
                .retryOnResult(result -> result instanceof SenseChannel.CollectResult cr && !cr.isAccepted())
                .retryExceptions(Exception.class)
                .build();
        Retry retry = Retry.of("sense-" + channel.type().name(), config);
        AtomicInteger attempts = new AtomicInteger();
        Supplier<SenseChannel.CollectResult> supplier = () -> {
            attempts.incrementAndGet();
            try {
                return channel.collect(request);
            } catch (Exception e) {
                SenseChannel.CollectResult failed = new SenseChannel.CollectResult();
                failed.setBatchId(UUID.randomUUID().toString());
                failed.setSourceChannel(channel.type().name());
                failed.setAccepted(false);
                failed.setError(e.getMessage());
                return failed;
            }
        };
        SenseChannel.CollectResult result;
        try {
            result = Retry.decorateSupplier(retry, supplier).get();
        } catch (Exception e) {
            result = new SenseChannel.CollectResult();
            result.setBatchId(UUID.randomUUID().toString());
            result.setSourceChannel(channel.type().name());
            result.setAccepted(false);
            result.setError("retry exhausted: " + e.getMessage());
        }
        return new Attempt(result, Math.max(1, attempts.get()));
    }

    /** 渠道路由：隔离/未实现的渠道自动降级到可用渠道（R2-09） */
    Route route(SenseChannel.ChannelType requested) {
        SenseChannel primary = registry.find(requested).orElse(null);
        if (primary != null && primary.available() && !healthMonitor.isIsolated(requested)) {
            return new Route(primary, false);
        }
        List<SenseChannel.ChannelType> fallbackOrder = new ArrayList<>();
        fallbackOrder.add(SenseChannel.ChannelType.TOUCH);
        for (SenseChannel.ChannelType type : SenseChannel.ChannelType.values()) {
            if (type != SenseChannel.ChannelType.TOUCH) fallbackOrder.add(type);
        }
        for (SenseChannel.ChannelType candidate : fallbackOrder) {
            if (candidate == requested) continue;
            SenseChannel channel = registry.find(candidate).orElse(null);
            if (channel != null && channel.available() && !healthMonitor.isIsolated(candidate)) {
                log.warn("channel {} unavailable/isolated, degrading to {}", requested, candidate);
                return new Route(channel, true);
            }
        }
        return new Route(null, false);
    }

    private CollectedData failFast(SenseChannel.ChannelType requested, SenseChannel.CollectRequest request, String error) {
        CollectedData data = new CollectedData();
        data.setBatchId(UUID.randomUUID().toString());
        data.setSourceChannel(requested.name());
        data.setTenantId(request == null || request.getTenantId() == null ? "default" : request.getTenantId());
        data.setTimestamp(System.currentTimeMillis());
        data.setFreshness(CollectedData.Freshness.STALE);
        data.setConfidence(0);
        data.setQualityScore(0);
        data.setStagingStatus(CollectedData.StagingStatus.REJECTED);
        data.setRejectReason(error);
        data.setMode(request == null ? "R1_DYNAMIC" : request.getMode());
        data.setDeadLetterId(deadLetterStore.add(requested, data.getTenantId(),
                request == null ? null : request.getDataSource(), error, 0, data.getMode()));
        data.setStagingRef(stagingStore.put(data));
        metrics.record(requested, data);
        return data;
    }

    record Route(SenseChannel channel, boolean degraded) {}
    record Attempt(SenseChannel.CollectResult result, int attempts) {}
}
