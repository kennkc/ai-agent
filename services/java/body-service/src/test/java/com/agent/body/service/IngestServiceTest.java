package com.agent.body.service;

import com.agent.body.chunk.ChunkProcessor;
import com.agent.body.client.EmbeddingClient;
import com.agent.body.client.QdrantClient;
import com.agent.body.common.BizException;
import com.agent.body.common.ErrorCode;
import com.agent.body.store.HotCacheStore;
import com.agent.body.store.InMemoryMetadataStore;
import com.agent.body.store.MetadataStore;
import com.agent.body.store.StorageFacade;
import com.agent.body.store.TierRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * R3-02/03/04/09 入库管道验收：状态机（PENDING→INDEXED/FAILED）、不伪造成功、重入库无残留。
 */
class IngestServiceTest {

    private MetadataStore metadata;
    private EmbeddingClient embeddingClient;
    private QdrantClient qdrant;
    private IngestService ingestService;

    @BeforeEach
    void setUp() {
        metadata = new InMemoryMetadataStore();
        embeddingClient = Mockito.mock(EmbeddingClient.class);
        qdrant = Mockito.mock(QdrantClient.class);
        HotCacheStore hotCache = Mockito.mock(HotCacheStore.class);
        StorageFacade storage = new StorageFacade(metadata, hotCache, qdrant, new TierRouter(3600, 2.0, 1.0));
        ingestService = new IngestService(new ChunkProcessor(800, 200, 50), embeddingClient, qdrant, storage, 16);
    }

    private void stubEmbedding(int chunkCount) {
        List<double[]> vectors = java.util.stream.IntStream.range(0, chunkCount)
                .mapToObj(i -> new double[]{0.1, 0.2, 0.3})
                .toList();
        Mockito.when(embeddingClient.embed(any()))
                .thenReturn(new EmbeddingClient.EmbedBatch(vectors, 3, "hash-ngram-768", true, 1));
    }

    @Test
    void successfulIngestEndsAsIndexedWithChunksAndVectors() {
        // 构造 3 个语义段落，期望分块成 >=1 块
        String content = "# 标题甲\n内容甲。\n\n# 标题乙\n内容乙。\n\n# 标题丙\n内容丙。";
        stubEmbedding(3);
        Mockito.when(qdrant.ensureCollection(anyInt())).thenReturn(false);

        IngestService.IngestOutcome outcome = ingestService.ingest("t1", "doc-1", "测试文档", content, "manual");

        assertEquals("doc-1", outcome.docId());
        assertTrue(outcome.chunkCount() > 0);
        assertEquals(com.agent.body.store.DocumentStatus.INDEXED, outcome.status());
        assertEquals("hash-ngram-768", outcome.vectorBackend());
        assertTrue(outcome.degraded(), "哈希降级后端须如实标记");

        var stored = metadata.findDocument("t1", "doc-1");
        assertEquals(com.agent.body.store.DocumentStatus.INDEXED, stored.status());
        assertEquals(outcome.chunkCount(), stored.chunkCount());
        assertEquals(outcome.chunkCount(), metadata.countChunks("t1"));

        ArgumentCaptor<List<QdrantClient.VectorPoint>> pointsCaptor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(qdrant).upsert(pointsCaptor.capture());
        assertEquals(outcome.chunkCount(), pointsCaptor.getValue().size());
        assertEquals("t1", pointsCaptor.getValue().get(0).payload().get("tenant_id"));
        // Qdrant 只接受整数或 UUID 作为 point id：入库侧必须派生成 UUID，且可读 chunk_id 落在 payload
        for (QdrantClient.VectorPoint point : pointsCaptor.getValue()) {
            assertEquals(QdrantClient.pointId(String.valueOf(point.payload().get("chunk_id"))), point.id());
            assertTrue(point.payload().get("chunk_id").toString().startsWith("doc-1#"));
        }
    }

    @Test
    void embeddingFailureMarksDocumentFailedAndRethrows() {
        Mockito.when(embeddingClient.embed(any()))
                .thenThrow(new BizException(ErrorCode.AGENT_UPSTREAM_UNAVAILABLE, "嵌入服务不可用"));

        assertThrows(BizException.class,
                () -> ingestService.ingest("t1", "doc-bad", "坏文档", "# 标题\n正文内容。", "manual"));

        var stored = metadata.findDocument("t1", "doc-bad");
        assertEquals(com.agent.body.store.DocumentStatus.FAILED, stored.status());
        assertNotNull(stored.error(), "失败必须记录原因，不允许静默");
        Mockito.verify(qdrant, Mockito.never()).upsert(any());
    }

    @Test
    void qdrantFailureMarksDocumentFailed() {
        stubEmbedding(1);
        Mockito.when(qdrant.ensureCollection(anyInt())).thenReturn(false);
        Mockito.doThrow(new BizException(ErrorCode.AGENT_UPSTREAM_UNAVAILABLE, "Qdrant 写入失败"))
                .when(qdrant).upsert(any());

        assertThrows(BizException.class,
                () -> ingestService.ingest("t1", "doc-q", "文档", "# 标题\n正文内容。", "manual"));
        assertEquals(com.agent.body.store.DocumentStatus.FAILED, metadata.findDocument("t1", "doc-q").status());
    }

    @Test
    void reingestClearsPreviousVectors() {
        stubEmbedding(2);
        Mockito.when(qdrant.ensureCollection(anyInt())).thenReturn(false);

        ingestService.ingest("t1", "doc-r", "文档", "# 标题\n正文内容一。", "manual");
        ingestService.ingest("t1", "doc-r", "文档", "# 标题\n正文内容一。\n\n# 标题二\n正文内容二。", "manual");

        // 每次重入库都需按 doc 清理旧向量，否则会重复召回
        Mockito.verify(qdrant, Mockito.times(2)).deleteByDocument(anyString(), anyString());
        Mockito.verify(qdrant, Mockito.times(2)).upsert(any());
    }

    @Test
    void blankContentIsRejected() {
        assertThrows(RuntimeException.class,
                () -> ingestService.ingest("t1", "doc-empty", "空文档", "   ", "manual"));
    }
}
