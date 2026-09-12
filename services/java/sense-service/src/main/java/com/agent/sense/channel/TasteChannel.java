package com.agent.sense.channel;

import com.agent.sense.model.CollectedData;
import com.agent.sense.quality.QualityGate;
import com.agent.sense.staging.StagingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 味觉渠道：数据质检（对暂存区批次做质检复核，产出质量报告）
 * 对应架构「味觉 = 数据质检」，是五感矩阵中唯一"向内"的渠道。
 */
@Slf4j
@Component
public class TasteChannel implements SenseChannel {

    private static final int SAMPLE_LIMIT = 20;

    private final StagingStore stagingStore;
    private final QualityGate qualityGate;

    public TasteChannel(StagingStore stagingStore, QualityGate qualityGate) {
        this.stagingStore = stagingStore;
        this.qualityGate = qualityGate;
    }

    @Override public ChannelType type() { return ChannelType.TASTE; }
    @Override public boolean register(Map<String, String> config) { return true; }
    @Override public boolean healthy() { return true; }
    @Override public boolean available() { return true; }
    @Override public void close() {}

    @Override
    public CollectResult collect(CollectRequest request) {
        CollectResult result = new CollectResult();
        result.setBatchId(UUID.randomUUID().toString());
        result.setSourceChannel(ChannelType.TASTE.name());
        result.setFreshness("BATCH");
        try {
            String tenantId = request.getTenantId() == null || request.getTenantId().isBlank()
                    ? "default" : request.getTenantId();
            List<CollectedData> samples = stagingStore.list(tenantId, null, SAMPLE_LIMIT);
            if (samples.isEmpty()) {
                result.setAccepted(false);
                result.setItemCount(0);
                result.setQualityScore(0);
                result.setConfidence(0);
                result.setError("no staged data to inspect");
                return result;
            }
            long accepted = samples.stream()
                    .filter(d -> d.getStagingStatus() == CollectedData.StagingStatus.ACCEPTED).count();
            double avg = samples.stream().mapToDouble(d -> qualityGate.score(d.getContent())).average().orElse(0);
            String report = "质检复核：样本 " + samples.size() + " 条，通过 " + accepted
                    + " 条，拒绝 " + (samples.size() - accepted)
                    + " 条，平均质量分 " + Math.round(avg * 10000) / 100.0 + "。";
            result.setContent(report);
            result.setItemCount(samples.size());
            result.setQualityScore(Math.round(avg * 10000) / 10000.0);
            result.setAccepted(true);
            result.setConfidence(0.95);
        } catch (Exception e) {
            log.warn("taste channel failed: {}", e.getMessage());
            result.setAccepted(false);
            result.setItemCount(0);
            result.setQualityScore(0);
            result.setError(e.getMessage());
        }
        return result;
    }
}
