package com.agent.sense.staging;

import com.agent.sense.config.SenseProperties;
import com.agent.sense.model.CollectedData;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 本地文件系统暂存后端（MinIO 不可用时的降级实现，保证隔离暂存能力不丢失）
 */
@Slf4j
@Component
public class LocalStagingBackend implements StagingBackend {

    private final SenseProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong writes = new AtomicLong();
    private Path root;

    public LocalStagingBackend(SenseProperties properties) { this.properties = properties; }

    @PostConstruct
    public void init() {
        ensureRoot();
        if (root != null) log.info("LocalStagingBackend ready at {}", root);
    }

    private synchronized void ensureRoot() {
        if (root != null) return;
        try {
            root = Path.of(properties.getStaging().getLocalRoot()).toAbsolutePath().normalize();
            Files.createDirectories(root);
        } catch (Exception e) {
            log.warn("LocalStagingBackend init failed: {}", e.getMessage());
        }
    }

    private Path root() {
        if (root == null) ensureRoot();
        return root;
    }

    @Override public String name() { return "local"; }

    @Override public boolean available() { return root() != null && Files.isDirectory(root); }

    @Override
    public String put(CollectedData data) {
        if (root() == null) return null;
        try {
            Path dir = root.resolve(tenant(data)).resolve(status(data)).resolve("batch-" + data.getBatchId());
            Files.createDirectories(dir);
            Path file = dir.resolve("item-0.json");
            Files.writeString(file, objectMapper.writeValueAsString(data.toMap()), StandardCharsets.UTF_8);
            writes.incrementAndGet();
            return "local://" + root.relativize(file).toString().replace('\\', '/');
        } catch (Exception e) {
            log.warn("local staging put failed: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public CollectedData get(String batchId, String tenantId) {
        for (CollectedData data : list(tenantId, null, Integer.MAX_VALUE)) {
            if (batchId.equals(data.getBatchId())) return data;
        }
        return null;
    }

    @Override
    public List<CollectedData> list(String tenantId, String status, int limit) {
        List<CollectedData> out = new ArrayList<>();
        if (!available()) return out;
        Path base = tenantId == null || tenantId.isBlank() ? root() : root().resolve(tenantId);
        if (!Files.isDirectory(base)) return out;
        try (Stream<Path> walk = Files.walk(base)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .forEach(p -> {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> map = objectMapper.readValue(Files.readString(p, StandardCharsets.UTF_8), Map.class);
                            if (status != null && !status.isBlank() && !status.equalsIgnoreCase(String.valueOf(map.get("staging_status")))) return;
                            out.add(fromMap(map));
                        } catch (Exception e) {
                            log.debug("skip unreadable staging object {}: {}", p, e.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.warn("local staging list failed: {}", e.getMessage());
        }
        return limit > 0 && out.size() > limit ? out.subList(0, limit) : out;
    }

    @Override
    public boolean rollback(String batchId, String tenantId) {
        if (!available()) return false;
        Path base = tenantId == null || tenantId.isBlank() ? root() : root().resolve(tenantId);
        if (!Files.isDirectory(base)) return false;
        boolean deleted = false;
        try (Stream<Path> walk = Files.walk(base)) {
            // 逆序删除：保证子项先于父目录被删，避免 DirectoryNotEmptyException
            List<Path> targets = walk.filter(p -> p.toString().contains("batch-" + batchId))
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path p : targets) {
                if (Files.deleteIfExists(p)) deleted = true;
            }
        } catch (Exception e) {
            log.warn("local staging rollback failed for {}: {}", batchId, e.getMessage());
            return false;
        }
        return deleted;
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("backend", name());
        stats.put("available", available());
        stats.put("writes", writes.get());
        stats.put("root", root() == null ? "" : root().toString());
        return stats;
    }

    private String tenant(CollectedData data) {
        String t = data.getTenantId();
        return (t == null || t.isBlank()) ? "default" : t.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private String status(CollectedData data) {
        return data.getStagingStatus() == null ? "PENDING" : data.getStagingStatus().name();
    }

    @SuppressWarnings("unchecked")
    static CollectedData fromMap(Map<String, Object> map) {
        CollectedData data = new CollectedData();
        data.setBatchId(str(map.get("batch_id")));
        data.setSourceChannel(str(map.get("source_channel")));
        data.setTenantId(str(map.get("tenant_id")));
        data.setTimestamp(num(map.get("timestamp")));
        String freshness = str(map.get("freshness"));
        if (freshness != null && !freshness.isBlank()) {
            try { data.setFreshness(CollectedData.Freshness.valueOf(freshness)); } catch (IllegalArgumentException ignored) {}
        }
        Object conf = map.get("confidence");
        data.setConfidence(conf instanceof Number n ? n.doubleValue() : -1);
        data.setContent(str(map.get("content")));
        data.setTitle(str(map.get("title")));
        data.setMode(str(map.get("mode")));
        String status = str(map.get("staging_status"));
        if (status != null && !status.isBlank()) {
            try { data.setStagingStatus(CollectedData.StagingStatus.valueOf(status)); } catch (IllegalArgumentException ignored) {}
        }
        Object q = map.get("quality_score");
        data.setQualityScore(q instanceof Number n ? n.doubleValue() : 0);
        Object items = map.get("item_count");
        data.setItemCount(items instanceof Number n ? n.intValue() : 0);
        data.setRejectReason(str(map.get("reject_reason")));
        data.setStagingRef(str(map.get("staging_ref")));
        Object attempts = map.get("attempts");
        data.setAttempts(attempts instanceof Number n ? n.intValue() : 1);
        data.setDegraded(Boolean.parseBoolean(str(map.get("degraded"))));
        data.setDeadLetterId(str(map.get("dead_letter_id")));
        return data;
    }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }

    private static long num(Object v) {
        if (v instanceof Number n) return n.longValue();
        try { return v == null ? 0 : Long.parseLong(String.valueOf(v)); } catch (NumberFormatException e) { return 0; }
    }
}
