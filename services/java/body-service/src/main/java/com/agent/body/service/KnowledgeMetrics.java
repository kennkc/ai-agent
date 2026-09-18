package com.agent.body.service;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 躯体层运行指标（R-C03 躯体视图数据源 / R3-05 性能验收口径）
 *
 * <p>口径说明（避免误读）：
 * <ul>
 *   <li>{@code search_hit_rate} = 有结果的检索次数 / 检索总次数（**检索命中率**）</li>
 *   <li>{@code cache_hit_rate} = 缓存命中次数 / 检索总次数（R3-08 缓存命中率 ≥ 30%）</li>
 *   <li>P99 基于**最近 N 次**检索延迟采样（{@code sampled_recent_searches}），非全量历史</li>
 * </ul>
 */
@Component
public class KnowledgeMetrics {

    private static final int MAX_SAMPLES = 500;

    private final AtomicLong ingestDocuments = new AtomicLong();
    private final AtomicLong ingestChunks = new AtomicLong();
    private final AtomicLong ingestFailures = new AtomicLong();
    private final AtomicLong searchTotal = new AtomicLong();
    private final AtomicLong searchWithResult = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong rerankCalls = new AtomicLong();
    private final AtomicLong rerankDegraded = new AtomicLong();
    private final Deque<Long> latencies = new ArrayDeque<>();

    public void recordIngest(int chunks, boolean success) {
        ingestDocuments.incrementAndGet();
        ingestChunks.addAndGet(chunks);
        if (!success) ingestFailures.incrementAndGet();
    }

    public void recordSearch(long latencyMs, boolean hasResult, boolean cacheHit, boolean rerankCalled,
                             boolean rerankDown) {
        searchTotal.incrementAndGet();
        if (hasResult) searchWithResult.incrementAndGet();
        if (cacheHit) cacheHits.incrementAndGet();
        if (rerankCalled) rerankCalls.incrementAndGet();
        if (rerankDown) rerankDegraded.incrementAndGet();
        synchronized (latencies) {
            latencies.addLast(latencyMs);
            while (latencies.size() > MAX_SAMPLES) {
                latencies.removeFirst();
            }
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("documents", ingestDocuments.get());
        stats.put("chunks", ingestChunks.get());
        stats.put("ingest_failures", ingestFailures.get());
        stats.put("searches", searchTotal.get());
        stats.put("searches_with_result", searchWithResult.get());
        stats.put("search_hit_rate", ratio(searchWithResult.get(), searchTotal.get()));
        stats.put("cache_hits", cacheHits.get());
        stats.put("cache_hit_rate", ratio(cacheHits.get(), searchTotal.get()));
        stats.put("rerank_calls", rerankCalls.get());
        stats.put("rerank_degraded", rerankDegraded.get());
        stats.put("latency_p50_ms", percentile(0.50));
        stats.put("latency_p95_ms", percentile(0.95));
        stats.put("latency_p99_ms", percentile(0.99));
        stats.put("sample_size", sampleSize());
        stats.put("sample_limit", MAX_SAMPLES);
        stats.put("p99_basis", "sampled_recent_searches");
        return stats;
    }

    public double searchHitRate() { return ratio(searchWithResult.get(), searchTotal.get()); }

    public double cacheHitRate() { return ratio(cacheHits.get(), searchTotal.get()); }

    public long p99() { return percentile(0.99); }

    private int sampleSize() {
        synchronized (latencies) {
            return latencies.size();
        }
    }

    private long percentile(double ratio) {
        long[] snapshot;
        synchronized (latencies) {
            snapshot = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
        }
        if (snapshot.length == 0) return 0;
        int index = (int) Math.ceil(snapshot.length * ratio) - 1;
        return snapshot[Math.max(0, Math.min(index, snapshot.length - 1))];
    }

    private double ratio(long numerator, long denominator) {
        if (denominator == 0) return 0.0;
        return Math.round((double) numerator / denominator * 10000.0) / 10000.0;
    }
}
