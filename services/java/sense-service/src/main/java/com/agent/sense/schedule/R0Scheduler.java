package com.agent.sense.schedule;

import com.agent.sense.channel.SenseChannel;
import com.agent.sense.collect.CollectPipeline;
import com.agent.sense.config.SenseProperties;
import com.agent.sense.model.CollectedData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
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
 * R0 默认采集调度（R2-05）
 * 规则驱动：定时轮询（按 intervalSeconds 判定到期）+ 增量同步语义（仅到期规则触发）。
 * 调度日志完整保留最近 MAX_LOG 条，供 Console / 排障使用。
 */
@Slf4j
@Component
public class R0Scheduler {

    private static final int MAX_LOG = 200;

    private final SenseProperties properties;
    private final CollectPipeline pipeline;
    private final Map<String, Long> lastRunAt = new ConcurrentHashMap<>();
    private final Deque<Map<String, Object>> logRecords = new ArrayDeque<>();
    private final AtomicLong triggered = new AtomicLong();

    public R0Scheduler(SenseProperties properties, CollectPipeline pipeline) {
        this.properties = properties;
        this.pipeline = pipeline;
    }

    @Scheduled(fixedDelayString = "${sense.r0.tick-ms:60000}", initialDelayString = "${sense.r0.initial-delay-ms:30000}")
    public void tick() {
        if (!properties.getR0().isEnabled()) return;
        for (SenseProperties.R0.Rule rule : properties.getR0().getRules()) {
            if (!rule.isEnabled()) continue;
            if (!due(rule)) continue;
            run(rule, "R0_SCHEDULED");
        }
    }

    private boolean due(SenseProperties.R0.Rule rule) {
        long interval = rule.getIntervalSeconds() <= 0 ? 0 : rule.getIntervalSeconds() * 1000L;
        long last = lastRunAt.getOrDefault(rule.getId(), 0L);
        return System.currentTimeMillis() - last >= interval;
    }

    /** 执行单条规则；手动触发时 scope 传 MANUAL */
    public Map<String, Object> run(SenseProperties.R0.Rule rule, String scope) {
        long started = System.currentTimeMillis();
        SenseChannel.CollectRequest request = new SenseChannel.CollectRequest();
        request.setDataSource(rule.getDataSource());
        request.setQuery(rule.getQuery());
        request.setTenantId(rule.getTenantId());
        request.setMode("R0_DEFAULT");
        request.setParams(Map.of("freshness", rule.getFreshness(), "scope", scope, "rule_id", rule.getId()));
        CollectedData data = pipeline.collect(SenseChannel.ChannelType.parse(rule.getChannel()), request);
        lastRunAt.put(rule.getId(), started);
        triggered.incrementAndGet();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("rule_id", rule.getId());
        record.put("scope", scope);
        record.put("channel", rule.getChannel());
        record.put("batch_id", data.getBatchId());
        record.put("staging_status", data.getStagingStatus() == null ? null : data.getStagingStatus().name());
        record.put("item_count", data.getItemCount());
        record.put("quality_score", data.getQualityScore());
        record.put("duration_ms", System.currentTimeMillis() - started);
        record.put("at", started);
        synchronized (logRecords) {
            if (logRecords.size() >= MAX_LOG) logRecords.removeFirst();
            logRecords.addLast(record);
        }
        log.info("R0 rule {} executed: status={} items={} cost={}ms",
                rule.getId(), record.get("staging_status"), record.get("item_count"), record.get("duration_ms"));
        return record;
    }

    /** 手动触发单条规则（演示 / 验收用） */
    public Map<String, Object> runNow(String ruleId) {
        SenseProperties.R0.Rule rule = properties.getR0().getRules().stream()
                .filter(r -> ruleId.equals(r.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown R0 rule: " + ruleId));
        return run(rule, "MANUAL");
    }

    public List<Map<String, Object>> rules() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SenseProperties.R0.Rule rule : properties.getR0().getRules()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rule.getId());
            item.put("channel", rule.getChannel());
            item.put("data_source", rule.getDataSource());
            item.put("query", rule.getQuery());
            item.put("tenant_id", rule.getTenantId());
            item.put("interval_seconds", rule.getIntervalSeconds());
            item.put("freshness", rule.getFreshness());
            item.put("enabled", rule.isEnabled());
            item.put("last_run_at", lastRunAt.getOrDefault(rule.getId(), 0L));
            out.add(item);
        }
        return out;
    }

    public List<Map<String, Object>> log(int limit) {
        synchronized (logRecords) {
            List<Map<String, Object>> out = new ArrayList<>(logRecords);
            java.util.Collections.reverse(out);
            return limit > 0 && out.size() > limit ? out.subList(0, limit) : out;
        }
    }

    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("enabled", properties.getR0().isEnabled());
        stats.put("tick_ms", properties.getR0().getTickMs());
        stats.put("rules", properties.getR0().getRules().size());
        stats.put("triggered", triggered.get());
        stats.put("last_tick_at", lastRunAt.values().stream().mapToLong(Long::longValue).max().orElse(0L));
        return stats;
    }
}
