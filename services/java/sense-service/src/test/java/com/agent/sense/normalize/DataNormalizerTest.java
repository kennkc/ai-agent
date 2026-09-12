package com.agent.sense.normalize;

import com.agent.sense.channel.SenseChannel;
import com.agent.sense.config.SenseProperties;
import com.agent.sense.model.CollectedData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R2-04 五字段标准化打标单测
 */
class DataNormalizerTest {

    private final DataNormalizer normalizer = new DataNormalizer(new SenseProperties());

    private SenseChannel.CollectResult result() {
        SenseChannel.CollectResult result = new SenseChannel.CollectResult();
        result.setBatchId(UUID.randomUUID().toString());
        result.setSourceChannel("TOUCH");
        result.setItemCount(1);
        result.setQualityScore(0.95);
        result.setAccepted(true);
        result.setContent("采集内容");
        return result;
    }

    private SenseChannel.CollectRequest request(String tenantId, String mode) {
        SenseChannel.CollectRequest request = new SenseChannel.CollectRequest();
        request.setTenantId(tenantId);
        request.setMode(mode);
        return request;
    }

    @Test
    void producesFiveCompleteFields() {
        CollectedData data = normalizer.normalize(result(), request("tenant-a", "R1_DYNAMIC"));
        assertTrue(data.fiveFieldsComplete());
        assertTrue(data.missingFields().isEmpty());
        assertEquals("TOUCH", data.getSourceChannel());
        assertEquals("tenant-a", data.getTenantId());
        assertTrue(data.getTimestamp() > 0);
        assertEquals(CollectedData.Freshness.REALTIME, data.getFreshness());
        assertEquals(0.95, data.getConfidence());
    }

    @Test
    void usesChannelReportedConfidenceWhenPresent() {
        SenseChannel.CollectResult result = result();
        result.setConfidence(0.62);
        CollectedData data = normalizer.normalize(result, request("default", "R1_DYNAMIC"));
        assertEquals(0.62, data.getConfidence());
    }

    @Test
    void r0ModeInfersNearRealtimeFreshness() {
        CollectedData data = normalizer.normalize(result(), request("default", "R0_DEFAULT"));
        assertEquals(CollectedData.Freshness.NEAR_REALTIME, data.getFreshness());
    }

    @Test
    void explicitFreshnessFromChannelWins() {
        SenseChannel.CollectResult result = result();
        result.setFreshness("BATCH");
        CollectedData data = normalizer.normalize(result, request("default", "R1_DYNAMIC"));
        assertEquals(CollectedData.Freshness.BATCH, data.getFreshness());
    }

    @Test
    void blankTenantFallsBackToDefault() {
        CollectedData data = normalizer.normalize(result(), request("  ", "R1_DYNAMIC"));
        assertEquals("default", data.getTenantId());
        assertTrue(data.fiveFieldsComplete());
    }

    @Test
    void toMapUsesSnakeCaseKeys() {
        CollectedData data = normalizer.normalize(result(), request("default", "R1_DYNAMIC"));
        assertTrue(data.toMap().containsKey("source_channel"));
        assertTrue(data.toMap().containsKey("tenant_id"));
        assertTrue(data.toMap().containsKey("quality_score"));
        assertFalse(data.toMap().containsKey("sourceChannel"));
    }
}
