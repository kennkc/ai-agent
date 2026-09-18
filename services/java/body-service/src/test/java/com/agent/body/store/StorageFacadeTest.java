package com.agent.body.store;

import com.agent.body.client.QdrantClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R3-01 三层一致性对账验收（设计 §6 风险应对：「元数据为真相源，定期对账任务」）：
 * 一致/缺向量/孤儿向量三种判定可区分，**向量库不可用时不得报成"一致"**。
 */
class StorageFacadeTest {

    private InMemoryMetadataStore metadata;
    private HotCacheStore hotCache;
    private QdrantClient qdrant;
    private StorageFacade facade;

    @BeforeEach
    void setUp() {
        metadata = new InMemoryMetadataStore();
        hotCache = Mockito.mock(HotCacheStore.class);
        qdrant = Mockito.mock(QdrantClient.class);
        facade = new StorageFacade(metadata, hotCache, qdrant, new TierRouter(3600, 2.0, 1.0));
        seed("t1", "doc-1", 3);
    }

    private void seed(String tenantId, String docId, int chunkCount) {
        long now = System.currentTimeMillis();
        metadata.saveDocument(new StoredDocument(docId, tenantId, "文档-" + docId, "manual", 900, chunkCount,
                DocumentStatus.INDEXED, "hash-ngram-768", now, now, null));
        List<StoredChunk> chunks = java.util.stream.IntStream.range(0, chunkCount)
                .mapToObj(i -> new StoredChunk(docId + "#" + i, docId, tenantId, i, "小节" + i, "正文" + i, 10,
                        "hash-ngram-768", now))
                .toList();
        metadata.replaceChunks(tenantId, docId, chunks);
    }

    @Test
    void consistentWhenVectorPointsMatchTruthSource() {
        Mockito.when(qdrant.available()).thenReturn(true);
        Mockito.when(qdrant.count("t1")).thenReturn(3L);

        Map<String, Object> report = facade.reconcile("t1");

        assertEquals("postgres", report.get("truth_source"), "对账真相源必须是 PG 元数据");
        assertEquals(1L, report.get("documents"));
        assertEquals(3L, report.get("chunks"));
        assertEquals(3L, report.get("vector_points"));
        assertEquals(0L, report.get("missing_vectors"));
        assertEquals(0L, report.get("orphan_vectors"));
        assertEquals(true, report.get("consistent"));
        assertEquals("三层一致，无需处置", report.get("action"));
    }

    @Test
    void missingVectorsAreReportedInsteadOfBeingSilentlyTolerated() {
        Mockito.when(qdrant.available()).thenReturn(true);
        Mockito.when(qdrant.count("t1")).thenReturn(1L);

        Map<String, Object> report = facade.reconcile("t1");

        assertEquals(false, report.get("consistent"));
        assertEquals(2L, report.get("missing_vectors"));
        assertEquals(0L, report.get("orphan_vectors"));
        assertTrue(String.valueOf(report.get("action")).contains("缺向量"),
                "应给出重索引指引：" + report.get("action"));
    }

    @Test
    void orphanVectorsAreReported() {
        Mockito.when(qdrant.available()).thenReturn(true);
        Mockito.when(qdrant.count("t1")).thenReturn(5L);

        Map<String, Object> report = facade.reconcile("t1");

        assertEquals(false, report.get("consistent"));
        assertEquals(0L, report.get("missing_vectors"));
        assertEquals(2L, report.get("orphan_vectors"));
        assertTrue(String.valueOf(report.get("action")).contains("孤儿向量"),
                "应给出清理指引：" + report.get("action"));
    }

    @Test
    void unavailableVectorStoreIsNotReportedAsConsistent() {
        Mockito.when(qdrant.available()).thenReturn(false);

        Map<String, Object> report = facade.reconcile("t1");

        assertEquals(false, report.get("vector_store_available"));
        assertEquals(-1L, report.get("vector_points"), "不可用时用 -1 表示未知，不用 0 冒充");
        assertEquals(-1L, report.get("missing_vectors"));
        assertEquals(false, report.get("consistent"), "向量库不可用不得判为一致");
        Mockito.verify(qdrant, Mockito.never()).count(Mockito.anyString());
    }

    @Test
    void reconcileIsScopedToTenant() {
        seed("t2", "doc-2", 2);
        Mockito.when(qdrant.available()).thenReturn(true);
        Mockito.when(qdrant.count("t1")).thenReturn(3L);
        Mockito.when(qdrant.count("t2")).thenReturn(2L);

        assertEquals(1L, facade.reconcile("t1").get("documents"));
        assertEquals(3L, facade.reconcile("t1").get("chunks"));
        assertEquals(1L, facade.reconcile("t2").get("documents"));
        assertEquals(2L, facade.reconcile("t2").get("chunks"));
    }
}
