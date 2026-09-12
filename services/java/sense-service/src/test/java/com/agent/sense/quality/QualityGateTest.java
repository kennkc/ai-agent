package com.agent.sense.quality;

import com.agent.sense.config.SenseProperties;
import com.agent.sense.model.CollectedData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R2-04/R2-10 质检门槛单测
 */
class QualityGateTest {

    private final QualityGate gate = new QualityGate(new SenseProperties());

    @Test
    void acceptsNormalContent() {
        String content = "Agent-Lifeform 感官期采集到的一段正常正文内容，包含足够长度的有效信息。";
        assertTrue(gate.pass(content));
        assertTrue(gate.score(content) > 0.9);
    }

    @Test
    void rejectsEmptyContent() {
        assertEquals(0.0, gate.score(""));
        assertEquals("EMPTY_CONTENT", gate.rejectReason("   "));
    }

    @Test
    void rejectsRepetitiveGarbage() {
        String garbage = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        assertFalse(gate.pass(garbage));
        assertEquals("REPETITIVE_GARBAGE", gate.rejectReason(garbage));
    }

    @Test
    void rejectsControlCharacterGarbage() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) sb.append((char) (i % 20 + 1));
        assertEquals("ENCODING_GARBLED", gate.rejectReason(sb.toString()));
    }

    @Test
    void rejectsShortLowQualityContent() {
        String content = "短句";
        assertFalse(gate.pass(content));
        assertEquals("BELOW_QUALITY_THRESHOLD", gate.rejectReason(content));
    }

    @Test
    void applyMarksRejectedAndRecordsReason() {
        CollectedData data = new CollectedData();
        data.setContent("");
        gate.apply(data);
        assertEquals(CollectedData.StagingStatus.REJECTED, data.getStagingStatus());
        assertEquals("EMPTY_CONTENT", data.getRejectReason());
    }

    @Test
    void applyMarksAcceptedWithScore() {
        CollectedData data = new CollectedData();
        data.setContent("这是一条通过质检门槛的正常采集内容，长度与可打印比例都达标。");
        gate.apply(data);
        assertEquals(CollectedData.StagingStatus.ACCEPTED, data.getStagingStatus());
        assertNull(data.getRejectReason());
        assertTrue(data.getQualityScore() >= 0.6);
    }
}
