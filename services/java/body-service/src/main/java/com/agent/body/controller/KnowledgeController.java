package com.agent.body.controller;

import com.agent.body.client.EmbeddingClient;
import com.agent.body.client.QdrantClient;
import com.agent.body.client.RerankClient;
import com.agent.body.service.IngestService;
import com.agent.body.service.KnowledgeMetrics;
import com.agent.body.service.RagPipeline;
import com.agent.body.service.RetrievalService;
import com.agent.body.store.MetadataStore;
import com.agent.body.store.StoredDocument;
import com.agent.body.store.StorageFacade;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识管理 API（R3-09）+ 检索/RAG 对外接口（R3-05/R3-07）
 *
 * <p>路由契约（与 {@code contracts/work-platform-bff-openapi.yaml} 的 /knowledge 段对齐）：
 * <ul>
 *   <li>{@code POST /api/body/knowledge} 入库（单篇）/ {@code /knowledge/batch} 批量导入</li>
 *   <li>{@code GET /api/body/knowledge} 文档列表 / {@code DELETE /api/body/knowledge/{docId}}</li>
 *   <li>{@code GET /api/body/knowledge/stats} 躯体视图指标（知识量/检索 P99/命中率/三层存储）</li>
 *   <li>{@code POST /api/body/retrieve} 语义检索（保留 query/top_k 旧契约字段）</li>
 *   <li>{@code POST /api/body/rag/answer} RAG 回答（含引用）</li>
 * </ul>
 *
 * <p>租户来源：网关注入的 {@code X-Tenant-Id}；**不信任请求体里的租户字段**（Phase 1 安全约定）。
 */
@RestController
@RequestMapping("/api/body")
public class KnowledgeController {

    private final IngestService ingestService;
    private final RetrievalService retrievalService;
    private final RagPipeline ragPipeline;
    private final KnowledgeMetrics metrics;
    private final StorageFacade storage;
    private final EmbeddingClient embeddingClient;
    private final RerankClient rerankClient;
    private final QdrantClient qdrant;

    public KnowledgeController(IngestService ingestService, RetrievalService retrievalService,
                               RagPipeline ragPipeline, KnowledgeMetrics metrics, StorageFacade storage,
                               EmbeddingClient embeddingClient, RerankClient rerankClient, QdrantClient qdrant) {
        this.ingestService = ingestService;
        this.retrievalService = retrievalService;
        this.ragPipeline = ragPipeline;
        this.metrics = metrics;
        this.storage = storage;
        this.embeddingClient = embeddingClient;
        this.rerankClient = rerankClient;
        this.qdrant = qdrant;
    }

    // ─────────── 入库 ───────────
    @PostMapping("/knowledge")
    public Map<String, Object> ingest(@RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                      @RequestBody KnowledgeRequest request) {
        IngestService.IngestOutcome outcome = ingestService.ingest(tenantId, request.doc_id(), request.title(),
                request.content(), request.source());
        metrics.recordIngest(outcome.chunkCount(), true);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("doc_id", outcome.docId());
        result.put("chunk_count", outcome.chunkCount());
        result.put("status", outcome.status().name());
        result.put("vector_backend", outcome.vectorBackend());
        result.put("degraded", outcome.degraded());
        result.put("success", true);
        return result;
    }

    @PostMapping("/knowledge/batch")
    public Map<String, Object> ingestBatch(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestBody BatchRequest request) {
        List<Map<String, Object>> results = new ArrayList<>();
        int success = 0;
        for (KnowledgeRequest item : request.documents()) {
            try {
                IngestService.IngestOutcome outcome = ingestService.ingest(tenantId, item.doc_id(), item.title(),
                        item.content(), item.source() == null ? request.source() : item.source());
                metrics.recordIngest(outcome.chunkCount(), true);
                success++;
                results.add(Map.of("doc_id", outcome.docId(), "chunk_count", outcome.chunkCount(),
                        "status", outcome.status().name(), "success", true));
            } catch (RuntimeException e) {
                metrics.recordIngest(0, false);
                results.add(Map.of("doc_id", String.valueOf(item.doc_id()), "status", "FAILED",
                        "success", false, "error", String.valueOf(e.getMessage())));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", request.documents().size());
        result.put("succeeded", success);
        result.put("failed", request.documents().size() - success);
        result.put("results", results);
        return result;
    }

    @GetMapping("/knowledge")
    public Map<String, Object> list(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        MetadataStore metadata = storage.metadata();
        List<Map<String, Object>> documents = new ArrayList<>();
        for (StoredDocument document : metadata.listDocuments(tenantId, limit)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("doc_id", document.docId());
            row.put("title", document.title());
            row.put("source", document.source());
            row.put("char_count", document.charCount());
            row.put("chunk_count", document.chunkCount());
            row.put("status", document.status().name());
            row.put("vector_backend", document.backend());
            row.put("created_at", document.createdAt());
            row.put("updated_at", document.updatedAt());
            row.put("error", document.error());
            documents.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", metadata.countDocuments(tenantId));
        result.put("chunk_total", metadata.countChunks(tenantId));
        result.put("metadata_backend", metadata.backend());
        result.put("items", documents);
        return result;
    }

    @DeleteMapping("/knowledge/{docId}")
    public Map<String, Object> delete(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String docId) {
        storage.metadata().deleteDocument(tenantId, docId);
        storage.warm().deleteByDocument(tenantId, docId);
        storage.hot().invalidateTenant(tenantId);
        return Map.of("doc_id", docId, "deleted", true);
    }

    // ─────────── 检索 / RAG ───────────
    @PostMapping("/retrieve")
    public List<Map<String, Object>> retrieve(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestBody RetrieveRequest request) {
        int topK = request.top_k() == null ? 5 : request.top_k();
        boolean useCache = request.use_cache() == null || request.use_cache();
        RetrievalService.RetrievalOutcome outcome = retrievalService.retrieve(tenantId, request.query(), topK, useCache);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RetrievalService.SearchHit hit : outcome.hits()) {
            // 兼容 Phase 1 旧契约字段（content / title / source），新增语义检索字段
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chunk_id", hit.chunkId());
            row.put("doc_id", hit.docId());
            row.put("title", hit.title());
            row.put("heading", hit.heading());
            row.put("chunk_index", hit.chunkIndex());
            row.put("content", hit.content());
            row.put("source", hit.source());
            row.put("score", hit.score());
            row.put("rerank_score", hit.rerankScore());
            row.put("ingest_time", hit.ingestTime());
            row.put("ingest_time_iso", hit.ingestTimeIso());
            rows.add(row);
        }
        return rows;
    }

    @PostMapping("/rag/answer")
    public Map<String, Object> ragAnswer(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestBody RetrieveRequest request) {
        int topK = request.top_k() == null ? 5 : request.top_k();
        RagPipeline.RagAnswer answer = ragPipeline.answer(tenantId, request.query(), topK);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", request.query());
        result.put("answer", answer.answer());
        result.put("citations", answer.citations());
        result.put("generator", answer.generator());
        result.put("cache_hit", answer.cacheHit());
        result.put("latency_ms", answer.latencyMs());
        result.put("tier", answer.retrievalTier());
        return result;
    }

    // ─────────── 状态（R-C03 躯体视图）───────────
    @GetMapping("/knowledge/stats")
    public Map<String, Object> stats(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        MetadataStore metadata = storage.metadata();
        EmbeddingClient.EmbeddingHealth embedding = embeddingClient.health();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenant_id", tenantId);
        RerankClient.RerankHealth rerank = rerankClient.health();
        result.put("knowledge", Map.of(
                "documents", metadata.countDocuments(tenantId),
                "chunks", metadata.countChunks(tenantId),
                "metadata_backend", metadata.backend(),
                "vector_points", qdrant.count(tenantId)));
        result.put("retrieval", metrics.snapshot());
        result.put("storage", storage.tierStatus());
        result.put("embedding", Map.of(
                "backend", embedding.backend(),
                "dim", embedding.dim(),
                "available", embedding.available(),
                "degraded", embedding.degraded()));
        result.put("reranker", Map.of(
                "backend", rerank.available() ? rerank.backend() : "unavailable",
                "available", rerank.available(),
                "degraded", rerank.degraded()));
        result.put("vector_store", Map.of(
                "provider", "qdrant",
                "collection", qdrant.collection(),
                "available", qdrant.available(),
                "vector_size", qdrant.currentVectorSize()));
        return result;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("service", "body-service", "status", "ok");
    }

    public record KnowledgeRequest(String doc_id, String title, String content, String source) { }

    public record BatchRequest(List<KnowledgeRequest> documents, String source) { }

    public record RetrieveRequest(String query, Integer top_k, Boolean use_cache) { }
}
