package com.agent.sense.staging;

import com.agent.sense.config.SenseProperties;
import com.agent.sense.model.CollectedData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R2-10 隔离暂存区单测（MinIO 不可用时自动降级本地后端）
 */
class StagingStoreTest {

    private SenseProperties properties;
    private StagingStore store;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        properties = new SenseProperties();
        properties.getStaging().setBackend("local");
        properties.getStaging().setLocalRoot(tempDir.resolve("staging").toString());
        properties.getStaging().setMinioEndpoint("http://127.0.0.1:1");   // 不可达，验证降级
        MinioStagingBackend minio = new MinioStagingBackend(properties);
        LocalStagingBackend local = new LocalStagingBackend(properties);
        store = new StagingStore(minio, local);
    }

    private CollectedData data(String batchId, String status) {
        CollectedData data = new CollectedData();
        data.setBatchId(batchId);
        data.setSourceChannel("TOUCH");
        data.setTenantId("default");
        data.setTimestamp(System.currentTimeMillis());
        data.setFreshness(CollectedData.Freshness.REALTIME);
        data.setConfidence(0.9);
        data.setContent("暂存内容 " + batchId);
        data.setQualityScore(0.9);
        data.setStagingStatus(CollectedData.StagingStatus.valueOf(status));
        return data;
    }

    @Test
    void degradesToLocalBackendWhenMinioUnavailable() {
        assertEquals("local", store.backendName());
    }

    @Test
    void storesAndListsAcceptedData() {
        store.put(data("b-1", "ACCEPTED"));
        store.put(data("b-2", "REJECTED"));
        List<CollectedData> accepted = store.list("default", "ACCEPTED", 10);
        assertEquals(1, accepted.size());
        assertEquals("b-1", accepted.get(0).getBatchId());
    }

    @Test
    void keepsRejectedDataOutOfAcceptedArea() {
        store.put(data("b-3", "REJECTED"));
        assertTrue(store.list("default", "ACCEPTED", 10).isEmpty());
        assertEquals(1, store.list("default", "REJECTED", 10).size());
    }

    @Test
    void rollbackRemovesWholeBatch() {
        store.put(data("b-4", "ACCEPTED"));
        assertTrue(store.rollback("b-4", "default"));
        assertNull(store.get("b-4", "default"));
        assertTrue(store.list("default", null, 10).isEmpty());
    }

    @Test
    void tenantIsolationOnRead() {
        store.put(data("b-5", "ACCEPTED"));
        assertTrue(store.list("other-tenant", null, 10).isEmpty());
    }

    @Test
    void statsReportsBothBackends() {
        assertTrue(store.stats().containsKey("minio"));
        assertTrue(store.stats().containsKey("local"));
        assertEquals("local", store.stats().get("active_backend"));
    }
}
