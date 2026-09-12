package com.agent.sense.staging;

import com.agent.sense.config.SenseProperties;
import com.agent.sense.model.CollectedData;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MinIO 暂存后端（R2-10 主实现）
 * 对象键：staging/{tenant}/{STATUS}/batch-{batchId}/item-{n}.json
 */
@Slf4j
@Component
public class MinioStagingBackend implements StagingBackend {

    private final SenseProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong writes = new AtomicLong();

    private volatile MinioClient client;
    private volatile boolean ready = false;

    public MinioStagingBackend(SenseProperties properties) { this.properties = properties; }

    @PostConstruct
    public void init() {
        if ("local".equalsIgnoreCase(properties.getStaging().getBackend())) {
            log.info("MinioStagingBackend disabled by configuration (backend=local)");
            return;
        }
        try {
            SenseProperties.Staging cfg = properties.getStaging();
        if (cfg.getMinioAccessKey() == null || cfg.getMinioAccessKey().isBlank()
                || cfg.getMinioSecretKey() == null || cfg.getMinioSecretKey().isBlank()) {
            log.warn("MinIO credentials are not configured; staging will degrade to local backend");
            return;
        }
            client = MinioClient.builder()
                    .endpoint(cfg.getMinioEndpoint())
                    .credentials(cfg.getMinioAccessKey(), cfg.getMinioSecretKey())
                    .build();
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(cfg.getBucket()).build());
            if (!exists) client.makeBucket(MakeBucketArgs.builder().bucket(cfg.getBucket()).build());
            ready = true;
            log.info("MinioStagingBackend ready bucket={} endpoint={}", cfg.getBucket(), cfg.getMinioEndpoint());
        } catch (Exception e) {
            ready = false;
            log.warn("MinIO unavailable, staging will degrade to local backend: {}", e.getMessage());
        }
    }

    @Override public String name() { return "minio"; }

    @Override public boolean available() { return ready; }

    @Override
    public String put(CollectedData data) {
        if (!ready) return null;
        try {
            String key = keyOf(data, 0);
            byte[] payload = objectMapper.writeValueAsBytes(data.toMap());
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket())
                    .object(key)
                    .stream(new ByteArrayInputStream(payload), payload.length, -1)
                    .contentType("application/json")
                    .build());
            writes.incrementAndGet();
            return "minio://" + bucket() + "/" + key;
        } catch (Exception e) {
            log.warn("minio staging put failed: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public CollectedData get(String batchId, String tenantId) {
        if (!ready) return null;
        String prefix = "staging/" + tenant(tenantId) + "/";
        try {
            for (Result<Item> r : client.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket()).prefix(prefix).recursive(true).build())) {
                Item item = r.get();
                if (!item.objectName().contains("batch-" + batchId)) continue;
                return readObject(item.objectName());
            }
        } catch (Exception e) {
            log.warn("minio staging get failed: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public List<CollectedData> list(String tenantId, String status, int limit) {
        List<CollectedData> out = new ArrayList<>();
        if (!ready) return out;
        String prefix = "staging/" + tenant(tenantId) + "/" + (status == null || status.isBlank() ? "" : status.toUpperCase());
        try {
            for (Result<Item> r : client.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket()).prefix(prefix).recursive(true).build())) {
                Item item = r.get();
                if (item.isDir() || !item.objectName().endsWith(".json")) continue;
                CollectedData data = readObject(item.objectName());
                if (data != null) out.add(data);
                if (limit > 0 && out.size() >= limit) break;
            }
        } catch (Exception e) {
            log.warn("minio staging list failed: {}", e.getMessage());
        }
        return out;
    }

    @Override
    public boolean rollback(String batchId, String tenantId) {
        if (!ready) return false;
        String prefix = "staging/" + tenant(tenantId) + "/";
        boolean deleted = false;
        try {
            List<String> targets = new ArrayList<>();
            for (Result<Item> r : client.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket()).prefix(prefix).recursive(true).build())) {
                Item item = r.get();
                if (item.objectName().contains("batch-" + batchId)) targets.add(item.objectName());
            }
            for (String key : targets) {
                client.removeObject(RemoveObjectArgs.builder().bucket(bucket()).object(key).build());
                deleted = true;
            }
        } catch (Exception e) {
            log.warn("minio staging rollback failed for {}: {}", batchId, e.getMessage());
            return false;
        }
        return deleted;
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("backend", name());
        stats.put("available", ready);
        stats.put("bucket", bucket());
        stats.put("endpoint", properties.getStaging().getMinioEndpoint());
        stats.put("writes", writes.get());
        return stats;
    }

    private CollectedData readObject(String key) {
        try (var stream = client.getObject(GetObjectArgs.builder().bucket(bucket()).object(key).build())) {
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return LocalStagingBackend.fromMap(objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {}));
        } catch (Exception e) {
            log.debug("read staging object failed {}: {}", key, e.getMessage());
            return null;
        }
    }

    private String keyOf(CollectedData data, int index) {
        String status = data.getStagingStatus() == null ? "PENDING" : data.getStagingStatus().name();
        return "staging/" + tenant(data.getTenantId()) + "/" + status
                + "/batch-" + data.getBatchId() + "/item-" + index + ".json";
    }

    private String bucket() { return properties.getStaging().getBucket(); }

    private static String tenant(String tenantId) {
        return (tenantId == null || tenantId.isBlank()) ? "default" : tenantId.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
