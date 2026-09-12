package com.agent.sense.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * R2-04 采集数据模型（五字段标准化打标）
 * 五字段：source_channel / tenant_id / timestamp / freshness / confidence
 */
@Data
public class CollectedData {

    public enum StagingStatus { ACCEPTED, REJECTED, PENDING }
    public enum Freshness { REALTIME, NEAR_REALTIME, BATCH, STALE }

    private String batchId;
    private String sourceChannel;
    private String tenantId;
    private long timestamp;
    private Freshness freshness;
    private double confidence;
    private String content;
    private String title;
    private String mode;               // R0_DEFAULT / R1_DYNAMIC
    private StagingStatus stagingStatus = StagingStatus.PENDING;
    private double qualityScore;
    private int itemCount;
    private String rejectReason;
    private long collectTimeMs;
    private String stagingRef;         // 暂存对象引用
    private int attempts = 1;          // 实际尝试次数（R2-09 重试）
    private boolean degraded;          // 是否走了降级渠道
    private String deadLetterId;       // 进入死信队列时的记录号

    /** 五字段完整性校验（R2-04 验收：缺失率 = 0） */
    public boolean fiveFieldsComplete() {
        return sourceChannel != null && !sourceChannel.isBlank()
                && tenantId != null && !tenantId.isBlank()
                && timestamp > 0
                && freshness != null
                && confidence >= 0;
    }

    public Map<String, Object> missingFields() {
        Map<String, Object> missing = new LinkedHashMap<>();
        if (sourceChannel == null || sourceChannel.isBlank()) missing.put("source_channel", "missing");
        if (tenantId == null || tenantId.isBlank()) missing.put("tenant_id", "missing");
        if (timestamp <= 0) missing.put("timestamp", "missing");
        if (freshness == null) missing.put("freshness", "missing");
        if (confidence < 0) missing.put("confidence", "missing");
        return missing;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("batch_id", batchId);
        map.put("source_channel", sourceChannel);
        map.put("tenant_id", tenantId);
        map.put("timestamp", timestamp);
        map.put("freshness", freshness == null ? null : freshness.name());
        map.put("confidence", confidence);
        map.put("title", title);
        map.put("content", content);
        map.put("mode", mode);
        map.put("staging_status", stagingStatus == null ? null : stagingStatus.name());
        map.put("quality_score", qualityScore);
        map.put("item_count", itemCount);
        map.put("reject_reason", rejectReason);
        map.put("collect_time_ms", collectTimeMs);
        map.put("staging_ref", stagingRef);
        map.put("attempts", attempts);
        map.put("degraded", degraded);
        map.put("dead_letter_id", deadLetterId);
        return map;
    }
}
