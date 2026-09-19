package com.agent.body.controller;

import com.agent.body.client.EmbeddingClient;
import com.agent.body.client.QdrantClient;
import com.agent.body.client.RerankClient;
import com.agent.body.service.IngestService;
import com.agent.body.service.KnowledgeMetrics;
import com.agent.body.service.RagPipeline;
import com.agent.body.service.RetrievalService;
import com.agent.body.store.DocumentStatus;
import com.agent.body.store.HotCacheStore;
import com.agent.body.store.StorageFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识写入必须失效检索热缓存（一致性回归）。
 *
 * <p>背景：热层缓存键是「租户 + 问题指纹」，一旦命中就直接返回**旧结果集**。
 * 若入库不失效缓存，就会出现「文档明明入库了、同一个问题还是检索不到」的缺陷，
 * 且新知识的可见性要等到 TTL 到期才恢复。删除路径一直有失效，写入路径此前漏了。
 */
class KnowledgeIngestInvalidationTest {

    private IngestService ingestService;
    private StorageFacade storage;
    private HotCacheStore hot;
    private KnowledgeController controller;

    @BeforeEach
    void setUp() {
        ingestService = Mockito.mock(IngestService.class);
        storage = Mockito.mock(StorageFacade.class);
        hot = Mockito.mock(HotCacheStore.class);
        when(storage.hot()).thenReturn(hot);

        controller = new KnowledgeController(
                ingestService,
                Mockito.mock(RetrievalService.class),
                Mockito.mock(RagPipeline.class),
                Mockito.mock(KnowledgeMetrics.class),
                storage,
                Mockito.mock(EmbeddingClient.class),
                Mockito.mock(RerankClient.class),
                Mockito.mock(QdrantClient.class));
    }

    private void stubIngest(String docId) {
        when(ingestService.ingest(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(new IngestService.IngestOutcome(
                        docId, 3, DocumentStatus.INDEXED, "hash-ngram-768", false, "hash", 100));
    }

    @Test
    void singleIngestInvalidatesTenantHotCache() {
        stubIngest("doc-1");
        controller.ingest("t1", new KnowledgeController.KnowledgeRequest(
                "doc-1", "标题", "正文内容", "manual", "md"));

        verify(hot, times(1)).invalidateTenant("t1");
    }

    @Test
    void batchIngestInvalidatesTenantHotCacheOnce() {
        stubIngest("doc-b");
        controller.ingestBatch("t2", new KnowledgeController.BatchRequest(
                List.of(new KnowledgeController.KnowledgeRequest("doc-b", "标题", "正文", "manual", "md")), "manual"));

        verify(hot, times(1)).invalidateTenant("t2");
    }

    @Test
    void batchWithNoSuccessfulWriteDoesNotInvalidate() {
        when(ingestService.ingest(anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("embedding down"));

        Map<String, Object> result = controller.ingestBatch("t3", new KnowledgeController.BatchRequest(
                List.of(new KnowledgeController.KnowledgeRequest("doc-c", "标题", "正文", "manual", "md")), "manual"));

        org.junit.jupiter.api.Assertions.assertEquals(0, result.get("succeeded"));
        verify(hot, never()).invalidateTenant(anyString());
    }

    @Test
    void deleteAlsoInvalidatesTenantHotCache() {
        com.agent.body.store.MetadataStore metadata = Mockito.mock(com.agent.body.store.MetadataStore.class);
        when(storage.metadata()).thenReturn(metadata);
        when(storage.warm()).thenReturn(Mockito.mock(QdrantClient.class));

        controller.delete("t4", "doc-d");

        verify(hot, times(1)).invalidateTenant(eq("t4"));
    }
}