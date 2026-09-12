package com.agent.sense.controller;

import com.agent.sense.channel.ChannelRegistry;
import com.agent.sense.channel.SenseChannel;
import com.agent.sense.common.BizException;
import com.agent.sense.common.ErrorCode;
import com.agent.sense.config.SenseProperties;
import com.agent.sense.deadletter.DeadLetterStore;
import com.agent.sense.event.SenseEventPublisher;
import com.agent.sense.health.ChannelHealthMonitor;
import com.agent.sense.metrics.SenseMetrics;
import com.agent.sense.model.CollectedData;
import com.agent.sense.schedule.R0Scheduler;
import com.agent.sense.staging.StagingStore;
import org.springframework.web.bind.annotation.*;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 感官服务运营/观测接口（R-C02 感官视图 · R-C09 统一数据模块）
 * Console 感官视图与五感矩阵所需的全部数据面在此暴露，字段统一 snake_case。
 */
@RestController
@RequestMapping("/api/sense")
public class SenseAdminController {

    private final ChannelRegistry registry;
    private final ChannelHealthMonitor healthMonitor;
    private final SenseMetrics metrics;
    private final StagingStore stagingStore;
    private final DeadLetterStore deadLetterStore;
    private final R0Scheduler r0Scheduler;
    private final SenseEventPublisher eventPublisher;
    private final SenseProperties properties;

    public SenseAdminController(ChannelRegistry registry, ChannelHealthMonitor healthMonitor, SenseMetrics metrics,
                               StagingStore stagingStore, DeadLetterStore deadLetterStore, R0Scheduler r0Scheduler,
                               SenseEventPublisher eventPublisher, SenseProperties properties) {
        this.registry = registry;
        this.healthMonitor = healthMonitor;
        this.metrics = metrics;
        this.stagingStore = stagingStore;
        this.deadLetterStore = deadLetterStore;
        this.r0Scheduler = r0Scheduler;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    /** D6 五感矩阵：五感官卡片（视/听/触/嗅/味） */
    @GetMapping("/channels")
    public Map<String, Object> channels() {
        List<Map<String, Object>> cards = new ArrayList<>();
        for (SenseChannel channel : registry.all()) {
            cards.add(card(channel));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sensors", cards);
        response.put("total", cards.size());
        response.put("up_count", cards.stream().filter(c -> "UP".equals(c.get("status"))).count());
        response.put("degraded_count", cards.stream().filter(c -> "DEGRADED".equals(c.get("status"))).count());
        response.put("down_count", cards.stream().filter(c -> "DOWN".equals(c.get("status"))).count());
        return response;
    }

    /** 下钻：单渠道详情（最近批次 + 指标） */
    @GetMapping("/channels/{type}")
    public Map<String, Object> channelDetail(@PathVariable String type,
                                             @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        SenseChannel.ChannelType channelType = parse(type);
        SenseChannel channel = registry.find(channelType)
                .orElseThrow(() -> new BizException(ErrorCode.AGENT_NOT_FOUND, "channel not registered: " + type));
        Map<String, Object> response = card(channel);
        List<Map<String, Object>> recent = new ArrayList<>();
        for (CollectedData data : stagingStore.list(tenantId, null, 10)) {
            if (!channelType.name().equals(data.getSourceChannel())) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("batch_id", data.getBatchId());
            item.put("staging_status", data.getStagingStatus() == null ? null : data.getStagingStatus().name());
            item.put("quality_score", data.getQualityScore());
            item.put("item_count", data.getItemCount());
            item.put("timestamp", data.getTimestamp());
            item.put("reject_reason", data.getRejectReason());
            item.put("degraded", data.isDegraded());
            item.put("attempts", data.getAttempts());
            recent.add(item);
        }
        response.put("recent_batches", recent);
        return response;
    }

    /** 渠道健康快照（含隔离与恢复状态） */
    @GetMapping("/channels-health")
    public Map<String, Object> channelsHealth() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("channels", healthMonitor.snapshot());
        response.put("recoveries", healthMonitor.recoveries());
        return response;
    }

    /** 采集统计：总量 / 质检通过率 / 近 6 分钟趋势 / 暂存后端 / 死信 / 事件 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("summary", metrics.summary());
        response.put("collect_rate_trend", metrics.rateTrend());
        response.put("staging", stagingStore.stats());
        response.put("dead_letter", deadLetterStore.stats());
        response.put("event", eventPublisher.stats());
        response.put("r0", r0Scheduler.stats());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("quality_threshold", properties.getQuality().getThreshold());
        config.put("retry_max_attempts", properties.getRetry().getMaxAttempts());
        config.put("r1_max_channels_per_round", properties.getR1().getMaxChannelsPerRound());
        config.put("ocr_enabled", properties.getOcr().isEnabled());
        response.put("config", config);
        return response;
    }

    /** 暂存批次列表（质检通过/拒绝） */
    @GetMapping("/batches")
    public Map<String, Object> batches(@RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                       @RequestParam(value = "status", required = false) String status,
                                       @RequestParam(value = "limit", defaultValue = "20") int limit) {
        List<CollectedData> data = stagingStore.list(tenantId, status, limit);
        List<Map<String, Object>> items = new ArrayList<>();
        for (CollectedData d : data) {
            Map<String, Object> item = new LinkedHashMap<>(d.toMap());
            item.remove("content");
            item.put("content_preview", preview(d.getContent()));
            items.add(item);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tenant_id", tenantId);
        response.put("backend", stagingStore.backendName());
        response.put("count", items.size());
        response.put("batches", items);
        return response;
    }

    /** 批次回滚（R2-10 验收：批次可回滚） */
    @PostMapping("/batches/{batchId}/rollback")
    public Map<String, Object> rollback(@PathVariable String batchId,
                                        @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        boolean removed = stagingStore.rollback(batchId, tenantId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("batch_id", batchId);
        response.put("rolled_back", removed);
        response.put("backend", stagingStore.backendName());
        return response;
    }

    /** R0 规则清单 */
    @GetMapping("/rules")
    public Map<String, Object> rules() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("stats", r0Scheduler.stats());
        response.put("rules", r0Scheduler.rules());
        response.put("log", r0Scheduler.log(20));
        return response;
    }

    /** 手动触发某条 R0 规则（演示 / 验收） */
    @PostMapping("/rules/{ruleId}/run")
    public Map<String, Object> runRule(@PathVariable String ruleId) {
        try {
            return r0Scheduler.runNow(ruleId);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND, e.getMessage());
        }
    }

    /** 死信队列 */
    @GetMapping("/deadletters")
    public Map<String, Object> deadLetters(@RequestParam(value = "limit", defaultValue = "20") int limit) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("stats", deadLetterStore.stats());
        response.put("records", deadLetterStore.list(limit));
        return response;
    }

    private Map<String, Object> card(SenseChannel channel) {
        SenseChannel.ChannelType type = channel.type();
        ChannelHealthMonitor.Status status = healthMonitor.status(type);
        SenseMetrics.Counter counter = metrics.counter(type);
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", type.name());
        card.put("name", type.label());
        card.put("icon", type.icon());
        card.put("status", status.name());
        card.put("isolated", healthMonitor.isIsolated(type));
        card.put("available", channel.available());
        card.put("collect_count", counter.batches());
        card.put("accepted_count", counter.accepted());
        card.put("rejected_count", counter.rejected());
        card.put("item_count", counter.items());
        card.put("quality_pass_rate", counter.passRate());
        card.put("rate_per_minute", counter.recentRatePerMinute());
        card.put("last_record_at", counter.lastRecordAt());
        card.put("last_record_text", format(counter.lastRecordAt()));
        return card;
    }

    private SenseChannel.ChannelType parse(String type) {
        try {
            return SenseChannel.ChannelType.parse(type);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.AGENT_BAD_REQUEST, e.getMessage());
        }
    }

    private static String format(long ts) {
        if (ts <= 0) return "";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ts));
    }

    private String preview(String content) {
        if (content == null || content.isEmpty()) return "";
        return content.length() > 200 ? content.substring(0, 200) + "..." : content;
    }
}
