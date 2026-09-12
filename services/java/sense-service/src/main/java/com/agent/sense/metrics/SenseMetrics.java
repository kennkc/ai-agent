package com.agent.sense.metrics;

import com.agent.sense.channel.SenseChannel;
import com.agent.sense.model.CollectedData;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 采集指标聚合（供 Console 感官视图 R-C02 / D6 五感矩阵使用）
 * 每渠道：累计采集批次、通过/拒绝数、最近记录、近期速率。
 */
@Component
public class SenseMetrics {

    private static final int WINDOW_MS = 6 * 60 * 1000;   // 近 6 分钟用于速率
    private static final int MAX_SAMPLES = 512;

    private final Map<SenseChannel.ChannelType, Counter> counters = new ConcurrentHashMap<>();
    private final AtomicLong totalBatches = new AtomicLong();
    private final AtomicLong totalAccepted = new AtomicLong();
    private final AtomicLong totalRejected = new AtomicLong();

    public void record(SenseChannel.ChannelType type, CollectedData data) {
        Counter counter = counters.computeIfAbsent(type, k -> new Counter());
        counter.record(data);
        totalBatches.incrementAndGet();
        if (data.getStagingStatus() == CollectedData.StagingStatus.ACCEPTED) totalAccepted.incrementAndGet();
        else if (data.getStagingStatus() == CollectedData.StagingStatus.REJECTED) totalRejected.incrementAndGet();
    }

    public Counter counter(SenseChannel.ChannelType type) {
        return counters.computeIfAbsent(type, k -> new Counter());
    }

    public long totalBatches() { return totalBatches.get(); }

    public double overallQualityPassRate() {
        long decided = totalAccepted.get() + totalRejected.get();
        return decided == 0 ? 0 : Math.round(totalAccepted.get() * 10000.0 / decided) / 100.0;
    }

    public Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_batches", totalBatches.get());
        summary.put("accepted", totalAccepted.get());
        summary.put("rejected", totalRejected.get());
        summary.put("quality_pass_rate", overallQualityPassRate());
        return summary;
    }

    /** 近 6 分钟按分钟采集体量（用于趋势图） */
    public List<Map<String, Object>> rateTrend() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            long from = now - (i + 1) * 60_000L;
            long to = now - i * 60_000L;
            long count = 0;
            for (Counter c : counters.values()) count += c.countBetween(from, to);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("bucket", new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date(to)));
            point.put("value", count);
            trend.add(point);
        }
        return trend;
    }

    public static class Counter {
        private final AtomicLong batches = new AtomicLong();
        private final AtomicLong accepted = new AtomicLong();
        private final AtomicLong rejected = new AtomicLong();
        private final AtomicLong items = new AtomicLong();
        private final AtomicLong lastRecordAt = new AtomicLong();
        private final AtomicLong consecutiveFailures = new AtomicLong();
        private final Deque<long[]> samples = new ArrayDeque<>();   // [timestamp, accepted(1/0)]

        synchronized void record(CollectedData data) {
            batches.incrementAndGet();
            items.addAndGet(Math.max(0, data.getItemCount()));
            boolean ok = data.getStagingStatus() == CollectedData.StagingStatus.ACCEPTED;
            if (ok) {
                accepted.incrementAndGet();
                consecutiveFailures.set(0);
            } else {
                rejected.incrementAndGet();
                consecutiveFailures.incrementAndGet();
            }
            long now = System.currentTimeMillis();
            lastRecordAt.set(now);
            samples.addLast(new long[]{now, ok ? 1 : 0});
            while (samples.size() > MAX_SAMPLES) samples.removeFirst();
        }

        synchronized long countBetween(long from, long to) {
            long count = 0;
            for (long[] s : samples) if (s[0] >= from && s[0] < to) count++;
            return count;
        }

        public long batches() { return batches.get(); }
        public long accepted() { return accepted.get(); }
        public long rejected() { return rejected.get(); }
        public long items() { return items.get(); }
        public long lastRecordAt() { return lastRecordAt.get(); }
        public long consecutiveFailures() { return consecutiveFailures.get(); }

        public double passRate() {
            long decided = accepted.get() + rejected.get();
            return decided == 0 ? 0 : Math.round(accepted.get() * 10000.0 / decided) / 100.0;
        }

        public double recentRatePerMinute() {
            long now = System.currentTimeMillis();
            return Math.round(countBetween(now - WINDOW_MS, now) * 100.0 / 6) / 100.0;
        }
    }
}
