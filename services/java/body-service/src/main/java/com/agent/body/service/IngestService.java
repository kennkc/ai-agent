package com.agent.body.service;

import com.agent.body.chunk.ChunkProcessor;
import com.agent.body.client.EmbeddingClient;
import com.agent.body.client.QdrantClient;
import com.agent.body.common.BizException;
import com.agent.body.common.ErrorCode;
import com.agent.body.store.DocumentStatus;
import com.agent.body.store.MetadataStore;
import com.agent.body.store.StoredChunk;
import com.agent.body.store.StoredDocument;
import com.agent.body.store.StorageFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识入库管道（R3-02 分块 → R3-03 嵌入 → R3-04 向量入库 → R3-09 索引状态）
 *
 * <p>状态机：写入 PENDING → 分块 → 批量嵌入 → Qdrant upsert → PG 元数据 → INDEXED；
 * 任一步失败写 FAILED 并记录原因（**不允许"入库失败但显示成功"**）。
 *
 * <p>重入库语义：先按 doc_id 删除旧向量与旧切片，再写入新版本（避免重复召回）。
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final ChunkProcessor chunkProcessor;
    private final EmbeddingClient embeddingClient;
    private final QdrantClient qdrant;
    private final StorageFacade storage;
    private final int embedBatchSize;

    public IngestService(ChunkProcessor chunkProcessor, EmbeddingClient embeddingClient, QdrantClient qdrant,
                         StorageFacade storage,
                         @Value("${app.body.ingest.embed-batch-size:16}") int embedBatchSize) {
        this.chunkProcessor = chunkProcessor;
        this.embeddingClient = embeddingClient;
        this.qdrant = qdrant;
        this.storage = storage;
        this.embedBatchSize = Math.max(1, embedBatchSize);
    }

    public IngestOutcome ingest(String tenantId, String docId, String title, String content, String source) {
        String resolvedDocId = docId == null || docId.isBlank() ? "doc-" + UUID.randomUUID() : docId;
        String resolvedTitle = title == null || title.isBlank() ? resolvedDocId : title;
        long now = System.currentTimeMillis();
        MetadataStore metadata = storage.metadata();

        StoredDocument pending = new StoredDocument(resolvedDocId, tenantId, resolvedTitle,
                source == null ? "manual" : source, content == null ? 0 : content.length(), 0,
                DocumentStatus.PENDING, null, now, now, null);
        metadata.saveDocument(pending);

        try {
            List<ChunkProcessor.Chunk> chunks = chunkProcessor.chunk(content);
            if (chunks.isEmpty()) {
                throw new BizException(ErrorCode.AGENT_BAD_REQUEST, "分块结果为空");
            }
            EmbeddingClient.EmbedBatch batch = embed(chunks);
            qdrant.ensureCollection(batch.dim());
            qdrant.deleteByDocument(tenantId, resolvedDocId);

            List<StoredChunk> storedChunks = new ArrayList<>();
            List<QdrantClient.VectorPoint> points = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                ChunkProcessor.Chunk chunk = chunks.get(i);
                String chunkId = resolvedDocId + "#" + chunk.index();
                storedChunks.add(new StoredChunk(chunkId, resolvedDocId, tenantId, chunk.index(), chunk.heading(),
                        chunk.content(), chunk.content().length(), batch.backend(), now));
                // point id 必须满足 Qdrant 约束（整数/UUID），由 chunkId 稳定派生
                points.add(new QdrantClient.VectorPoint(QdrantClient.pointId(chunkId), batch.vectors().get(i),
                        payload(tenantId, resolvedDocId, chunkId, resolvedTitle, source, chunk, batch.backend(), now)));
            }
            qdrant.upsert(points);
            metadata.replaceChunks(tenantId, resolvedDocId, storedChunks);
            metadata.saveDocument(pending.withStatus(DocumentStatus.INDEXED, storedChunks.size(), null));

            log.info("知识入库完成 tenant={} doc={} chunks={} backend={} degraded={}",
                    tenantId, resolvedDocId, storedChunks.size(), batch.backend(), batch.degraded());
            return new IngestOutcome(resolvedDocId, storedChunks.size(), DocumentStatus.INDEXED,
                    batch.backend(), batch.degraded(), contentHash(content));
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            metadata.saveDocument(pending.withStatus(DocumentStatus.FAILED, 0, truncate(reason)));
            log.warn("知识入库失败 tenant={} doc={} reason={}", tenantId, resolvedDocId, reason);
            throw e;
        }
    }

    private EmbeddingClient.EmbedBatch embed(List<ChunkProcessor.Chunk> chunks) {
        List<double[]> vectors = new ArrayList<>();
        String backend = "unknown";
        boolean degraded = false;
        int dim = 0;
        for (int start = 0; start < chunks.size(); start += embedBatchSize) {
            int end = Math.min(start + embedBatchSize, chunks.size());
            List<String> texts = chunks.subList(start, end).stream().map(ChunkProcessor.Chunk::content).toList();
            EmbeddingClient.EmbedBatch batch = embeddingClient.embed(texts);
            vectors.addAll(batch.vectors());
            backend = batch.backend();
            degraded = batch.degraded();
            dim = batch.dim();
        }
        return new EmbeddingClient.EmbedBatch(vectors, dim, backend, degraded, 0);
    }

    private Map<String, Object> payload(String tenantId, String docId, String chunkId, String title, String source,
                                        ChunkProcessor.Chunk chunk, String backend, long ingestTime) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenant_id", tenantId);
        payload.put("doc_id", docId);
        payload.put("chunk_id", chunkId);
        payload.put("title", title);
        payload.put("source", source == null ? "manual" : source);
        payload.put("chunk_index", chunk.index());
        payload.put("heading", chunk.heading() == null ? "" : chunk.heading());
        payload.put("content", chunk.content());
        payload.put("ingest_time", ingestTime);
        payload.put("vector_backend", backend);
        return payload;
    }

    private String contentHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(String.valueOf(content).getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String value) {
        return value.length() > 900 ? value.substring(0, 900) : value;
    }

    /** 入库结果（含向量后端与降级标记，供前端躯体视图如实展示） */
    public record IngestOutcome(String docId, int chunkCount, DocumentStatus status, String vectorBackend,
                                boolean degraded, String contentHash) { }
}
