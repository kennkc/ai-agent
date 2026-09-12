package com.agent.sense.staging;

import com.agent.sense.model.CollectedData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 隔离暂存入口（R2-10）：按可用性自动选择 MinIO / 本地后端，
 * 并在 MinIO 失效时实时降级，保证「低质拦截 + 批次可回滚」始终成立。
 */
@Slf4j
@Component
public class StagingStore {

    private final MinioStagingBackend minioBackend;
    private final LocalStagingBackend localBackend;

    public StagingStore(MinioStagingBackend minioBackend, LocalStagingBackend localBackend) {
        this.minioBackend = minioBackend;
        this.localBackend = localBackend;
    }

    public String put(CollectedData data) {
        StagingBackend backend = active();
        String ref = backend.put(data);
        if (ref == null && backend != localBackend && localBackend.available()) {
            log.warn("staging degraded to local backend for batch {}", data.getBatchId());
            ref = localBackend.put(data);
        }
        return ref;
    }

    public CollectedData get(String batchId, String tenantId) {
        CollectedData data = active().get(batchId, tenantId);
        if (data == null && active() != localBackend) data = localBackend.get(batchId, tenantId);
        return data;
    }

    public List<CollectedData> list(String tenantId, String status, int limit) {
        List<CollectedData> data = active().list(tenantId, status, limit);
        if (data.isEmpty() && active() != localBackend) data = localBackend.list(tenantId, status, limit);
        return data;
    }

    public boolean rollback(String batchId, String tenantId) {
        boolean removed = active().rollback(batchId, tenantId);
        if (!removed) removed = localBackend.rollback(batchId, tenantId);
        return removed;
    }

    public String backendName() { return active().name(); }

    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("active_backend", active().name());
        stats.put("minio", minioBackend.stats());
        stats.put("local", localBackend.stats());
        return stats;
    }

    private StagingBackend active() {
        return minioBackend.available() ? minioBackend : localBackend;
    }
}
