package com.agent.tool.exec;

import com.agent.tool.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工具执行指标（支撑 DoD「工具成功率 ≥ 90%」「执行 P99 < 2s」的量化验收）。
 *
 * <p>进程内滑动窗口统计（保留最近 {@code window} 次调用样本）。**未接 Prometheus**，
 * 当前只对视图/报表提供数据（《技术债台账》DEBT-021 登记）。
 */
@Component
public class ToolMetrics {

    private static final int WINDOW = 500;

    private final ConcurrentLinkedDeque<Sample> samples = new ConcurrentLinkedDeque<>();
    private final AtomicLong totalCalls = new AtomicLong(0);
    private final AtomicLong totalFailures = new AtomicLong(0);
    private final AtomicLong blockedCalls = new AtomicLong(0);
    private final Map<String, AtomicLong> perTool = new java.util.concurrent.ConcurrentHashMap<>();

    public record Sample(String toolName, boolean success, long latencyMs, String errorCode) { }

    public void record(String toolName, boolean success, long latencyMs, String errorCode) {
        totalCalls.incrementAndGet();
        if (!success) totalFailures.incrementAndGet();
        if (errorCode != null && (errorCode.equals(ErrorCode.AGENT_TOOL_NOT_ALLOWED.code())
                || errorCode.equals(ErrorCode.AGENT_TOOL_ARGS_BLOCKED.code())
                || errorCode.equals(ErrorCode.AGENT_SANDBOX_REJECTED.code()))) {
            blockedCalls.incrementAndGet();
        }
        samples.addLast(new Sample(toolName, success, latencyMs, errorCode));
        while (samples.size() > WINDOW) samples.removeFirst();
        perTool.computeIfAbsent(toolName, key -> new AtomicLong(0)).incrementAndGet();
    }

    public Map<String, Object> snapshot() {
        List<Sample> window = new ArrayList<>(samples);
        long success = window.stream().filter(Sample::success).count();
        List<Long> latencies = new ArrayList<>();
        for (Sample sample : window) latencies.add(sample.latencyMs());
        latencies.sort(Long::compareTo);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("window_size", window.size());
        row.put("total_calls", totalCalls.get());
        row.put("total_failures", totalFailures.get());
        row.put("blocked_calls", blockedCalls.get());
        row.put("success_rate", window.isEmpty() ? null : round(success * 1.0 / window.size()));
        row.put("p50_ms", percentile(latencies, 0.50));
        row.put("p95_ms", percentile(latencies, 0.95));
        row.put("p99_ms", percentile(latencies, 0.99));
        Map<String, Object> byTool = new LinkedHashMap<>();
        perTool.forEach((key, value) -> byTool.put(key, value.get()));
        row.put("calls_by_tool", byTool);
        return row;
    }

    private static Long percentile(List<Long> sorted, double ratio) {
        if (sorted.isEmpty()) return 0L;
        int index = (int) Math.ceil(ratio * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}