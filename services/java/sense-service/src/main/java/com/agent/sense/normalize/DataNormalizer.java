package com.agent.sense.normalize;

import com.agent.sense.channel.SenseChannel;
import com.agent.sense.config.SenseProperties;
import com.agent.sense.model.CollectedData;
import org.springframework.stereotype.Component;

/**
 * 数据标准化打标（R2-04）：采集结果 → 五字段完整的数据对象
 * 五字段：source_channel / tenant_id / timestamp / freshness / confidence
 */
@Component
public class DataNormalizer {

    private final SenseProperties properties;

    public DataNormalizer(SenseProperties properties) { this.properties = properties; }

    public CollectedData normalize(SenseChannel.CollectResult result, SenseChannel.CollectRequest request) {
        CollectedData data = new CollectedData();
        data.setBatchId(result.getBatchId());
        // ① source_channel
        data.setSourceChannel(result.getSourceChannel() == null
                ? (request == null ? "UNKNOWN" : "UNKNOWN") : result.getSourceChannel());
        // ② tenant_id（只信任网关注入的租户，忽略请求体）
        data.setTenantId(tenantIdOf(request));
        // ③ timestamp
        data.setTimestamp(System.currentTimeMillis());
        // ④ freshness：渠道自报优先，否则按采集模式推断
        data.setFreshness(resolveFreshness(result, request));
        // ⑤ confidence：渠道自报优先，否则按质检分回填
        double confidence = result.getConfidence();
        if (confidence < 0) confidence = result.getQualityScore();
        data.setConfidence(Math.round(Math.max(0, Math.min(1, confidence)) * 10000) / 10000.0);

        data.setContent(result.getContent());
        data.setItemCount(result.getItemCount());
        data.setMode(request == null || request.getMode() == null ? "R1_DYNAMIC" : request.getMode());
        return data;
    }

    private String tenantIdOf(SenseChannel.CollectRequest request) {
        if (request == null || request.getTenantId() == null || request.getTenantId().isBlank()) return "default";
        return request.getTenantId();
    }

    private CollectedData.Freshness resolveFreshness(SenseChannel.CollectResult result, SenseChannel.CollectRequest request) {
        String raw = result.getFreshness();
        if (raw == null && request != null && request.getParams() != null) {
            raw = request.getParams().get("freshness");
        }
        if (raw != null && !raw.isBlank()) {
            try {
                return CollectedData.Freshness.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // 落到模式推断
            }
        }
        String mode = request == null ? null : request.getMode();
        if ("R0_DEFAULT".equalsIgnoreCase(mode)) return CollectedData.Freshness.NEAR_REALTIME;
        return CollectedData.Freshness.REALTIME;
    }

    /** R2-04 验收：五字段完整率 100% */
    public boolean fiveFieldsComplete(CollectedData data) { return data.fiveFieldsComplete(); }

    public SenseProperties getProperties() { return properties; }
}
