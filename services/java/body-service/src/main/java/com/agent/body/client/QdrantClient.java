package com.agent.body.client;

import com.agent.body.common.BizException;
import com.agent.body.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Qdrant 向量库客户端（R3-04）：走 REST（6333），不引入额外 SDK 依赖。
 *
 * <p>关键约定：
 * <ul>
 *   <li>集合维度由嵌入后端决定（hash 后端 768 / BGE-M3 1024）——**维度不一致时必须重建集合**，
 *       否则 upsert 会被 Qdrant 拒绝；</li>
 *   <li>tenant 隔离靠 payload filter（{@code tenant_id}），绝不靠调用方自觉过滤结果；</li>
 *   <li>距离度量 Cosine（与嵌入的 L2 归一化配套）。</li>
 * </ul>
 */
@Component
public class QdrantClient {

    private static final Logger log = LoggerFactory.getLogger(QdrantClient.class);

    private final RestClient client;
    private final String collection;

    public QdrantClient(@Value("${app.body.qdrant.base-url:http://127.0.0.1:6333}") String baseUrl,
                        @Value("${app.body.qdrant.collection:lifeform_knowledge}") String collection) {
        this.client = OutboundHttp.restClient(baseUrl);
        this.collection = collection;
    }

    public String collection() { return collection; }

    /** 探测可用性（容器未启动时入库应显式失败，而不是静默丢弃向量） */
    public boolean available() {
        try {
            client.get().uri("/collections").retrieve().toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> collectionInfo() {
        try {
            Map<String, Object> body = client.get().uri("/collections/{name}", collection).retrieve().body(Map.class);
            return body == null ? Map.of() : body;
        } catch (RestClientException e) {
            return Map.of("error", String.valueOf(e.getMessage()));
        }
    }

    /**
     * Qdrant point id 派生（**必须满足 Qdrant 的 ID 约束**：无符号整数或 UUID）。
     *
     * <p>业务 chunk_id 形如 {@code doc-1#0}，直接当 point id 会被 Qdrant 以
     * 「not a valid point ID」拒绝（E2E 实测 400）。这里用 name-based UUID（v3，MD5）
     * 派生：同一 chunk 每次得到**相同** id，因此重入库天然幂等，不会产生孤儿向量。
     * 人类可读的 chunk_id 仍随 payload 一起存储，检索结果回填 payload 值。
     */
    public static String pointId(String chunkId) {
        return UUID.nameUUIDFromBytes(String.valueOf(chunkId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** 现有集合的向量维度；集合不存在返回 0 */
    @SuppressWarnings("unchecked")
    public int currentVectorSize() {
        try {
            Map<String, Object> body = client.get().uri("/collections/{name}", collection).retrieve().body(Map.class);
            if (body == null || !(body.get("result") instanceof Map<?, ?> result)) return 0;
            Object config = ((Map<String, Object>) result).get("config");
            if (!(config instanceof Map<?, ?> configMap)) return 0;
            Object params = ((Map<String, Object>) configMap).get("params");
            if (!(params instanceof Map<?, ?> paramsMap)) return 0;
            Object vectors = ((Map<String, Object>) paramsMap).get("vectors");
            if (vectors instanceof Map<?, ?> vectorMap && vectorMap.get("size") instanceof Number size) {
                return size.intValue();
            }
            return 0;
        } catch (RestClientException e) {
            return 0;
        }
    }

    /**
     * 确保集合存在且维度一致。维度不一致时**重建集合**（删旧建新）并返回 true，
     * 调用方需据此全量重索引（回归注意：嵌入模型升级需全量重索引）。
     */
    public boolean ensureCollection(int dim) {
        if (dim <= 0) {
            throw new BizException(ErrorCode.AGENT_BAD_REQUEST, "集合维度非法：" + dim);
        }
        int existing = currentVectorSize();
        if (existing == dim) return false;
        if (existing > 0) {
            log.warn("Qdrant 集合 {} 维度不一致（现有 {} → 期望 {}），重建集合", collection, existing, dim);
            deleteCollection();
        }
        Map<String, Object> payload = Map.of("vectors", Map.of("size", dim, "distance", "Cosine"));
        try {
            client.put().uri("/collections/{name}", collection).body(payload).retrieve().toBodilessEntity();
            return existing > 0;
        } catch (RestClientException e) {
            throw new BizException(ErrorCode.AGENT_UPSTREAM_UNAVAILABLE, "Qdrant 集合创建失败：" + e.getMessage(), e);
        }
    }

    public void deleteCollection() {
        try {
            client.delete().uri("/collections/{name}", collection).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new BizException(ErrorCode.AGENT_UPSTREAM_UNAVAILABLE, "Qdrant 集合删除失败：" + e.getMessage(), e);
        }
    }

    /** 批量写入（wait=true，保证返回后即可检索——验收要求"批次入库可检索"） */
    public void upsert(List<VectorPoint> points) {
        if (points == null || points.isEmpty()) return;
        List<Map<String, Object>> payloadPoints = new ArrayList<>();
        for (VectorPoint point : points) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", point.id());
            row.put("vector", point.vector());
            row.put("payload", point.payload());
            payloadPoints.add(row);
        }
        try {
            client.put().uri(uriBuilder -> uriBuilder.path("/collections/{name}/points")
                            .queryParam("wait", "true").build(collection))
                    .body(Map.of("points", payloadPoints))
                    .retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new BizException(ErrorCode.AGENT_UPSTREAM_UNAVAILABLE, "Qdrant 写入失败：" + e.getMessage(), e);
        }
    }

    /** 向量检索（带 tenant 过滤） */
    @SuppressWarnings("unchecked")
    public List<ScoredPoint> search(String tenantId, double[] vector, int limit) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("vector", vector);
        payload.put("limit", limit);
        payload.put("with_payload", true);
        payload.put("filter", Map.of("must", List.of(
                Map.of("key", "tenant_id", "match", Map.of("value", tenantId)))));
        try {
            Map<String, Object> body = client.post()
                    .uri("/collections/{name}/points/search", collection)
                    .body(payload).retrieve().body(Map.class);
            if (body == null || !(body.get("result") instanceof List<?> rows)) return List.of();
            List<ScoredPoint> hits = new ArrayList<>();
            for (Object raw : rows) {
                Map<String, Object> row = (Map<String, Object>) raw;
                Map<String, Object> pointPayload = row.get("payload") instanceof Map<?, ?> map
                        ? (Map<String, Object>) map : Map.of();
                hits.add(new ScoredPoint(
                        // chunk_id 取 payload（人类可读），point id 仅是 Qdrant 内部主键
                        String.valueOf(pointPayload.getOrDefault("chunk_id", String.valueOf(row.get("id")))),
                        String.valueOf(pointPayload.getOrDefault("doc_id", "")),
                        intOf(pointPayload.get("chunk_index")),
                        String.valueOf(pointPayload.getOrDefault("heading", "")),
                        String.valueOf(pointPayload.getOrDefault("source", "")),
                        String.valueOf(pointPayload.getOrDefault("content", "")),
                        doubleOf(row.get("score")),
                        longOf(pointPayload.get("ingest_time"))));
            }
            return hits;
        } catch (RestClientException e) {
            throw new BizException(ErrorCode.AGENT_UPSTREAM_UNAVAILABLE, "Qdrant 检索失败：" + e.getMessage(), e);
        }
    }

    /** 按文档删除（重入库前清理旧向量，避免重复召回） */
    public void deleteByDocument(String tenantId, String docId) {
        Map<String, Object> payload = Map.of("filter", Map.of("must", List.of(
                Map.of("key", "tenant_id", "match", Map.of("value", tenantId)),
                Map.of("key", "doc_id", "match", Map.of("value", docId)))));
        try {
            client.post().uri(uriBuilder -> uriBuilder.path("/collections/{name}/points/delete")
                            .queryParam("wait", "true").build(collection))
                    .body(payload).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new BizException(ErrorCode.AGENT_UPSTREAM_UNAVAILABLE, "Qdrant 删除失败：" + e.getMessage(), e);
        }
    }

    /** 计数（可按租户）——R-C03 躯体视图的"知识量"口径 */
    @SuppressWarnings("unchecked")
    public long count(String tenantId) {
        Map<String, Object> payload = tenantId == null || tenantId.isBlank()
                ? Map.of("exact", true)
                : Map.of("exact", true, "filter", Map.of("must", List.of(
                        Map.of("key", "tenant_id", "match", Map.of("value", tenantId)))));
        try {
            Map<String, Object> body = client.post()
                    .uri("/collections/{name}/points/count", collection)
                    .body(payload).retrieve().body(Map.class);
            if (body == null || !(body.get("result") instanceof Map<?, ?> result)) return 0;
            return longOf(((Map<String, Object>) result).get("count"));
        } catch (RestClientException e) {
            log.warn("Qdrant 计数失败：{}", e.getMessage());
            return 0;
        }
    }

    private static int intOf(Object value) { return value instanceof Number number ? number.intValue() : 0; }
    private static long longOf(Object value) { return value instanceof Number number ? number.longValue() : 0L; }
    private static double doubleOf(Object value) { return value instanceof Number number ? number.doubleValue() : 0.0; }

    /** 待写入的向量点（chunk 级） */
    public record VectorPoint(String id, double[] vector, Map<String, Object> payload) { }

    /** 检索命中（含 payload 还原后的元数据） */
    public record ScoredPoint(String chunkId, String docId, int chunkIndex, String heading, String source,
                              String content, double score, long ingestTime) { }
}
