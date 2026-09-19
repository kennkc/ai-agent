package com.agent.body.service;

import com.agent.body.client.EmbeddingClient;
import com.agent.body.client.QdrantClient;
import com.agent.body.client.RerankClient;
import com.agent.body.common.BizException;
import com.agent.body.common.BudgetGuard;
import com.agent.body.store.HotCacheStore;
import com.agent.body.store.InMemoryMetadataStore;
import com.agent.body.store.StorageFacade;
import com.agent.body.store.TierRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * R3-05/06/08 检索链路验收：缓存优先、重排降级不阻断、上游不可用不返回假结果。
 */
class RetrievalServiceTest {

    private EmbeddingClient embeddingClient;
    private QdrantClient qdrant;
    private RerankClient rerankClient;
    private HotCacheStore hotCache;
    private KnowledgeMetrics metrics;
    private RetrievalService retrievalService;

    @BeforeEach
    void setUp() {
        embeddingClient = Mockito.mock(EmbeddingClient.class);
        qdrant = Mockito.mock(QdrantClient.class);
        rerankClient = Mockito.mock(RerankClient.class);
        hotCache = Mockito.mock(HotCacheStore.class);
        metrics = new KnowledgeMetrics();
        StorageFacade storage = new StorageFacade(new InMemoryMetadataStore(), hotCache, qdrant,
                new TierRouter(3600, 2.0, 1.0));
        retrievalService = new RetrievalService(embeddingClient, qdrant, rerankClient, storage, metrics, 50, 0);
        // 预算 0 = 显式不限：本类的既有用例关注降级/排序语义，与时间无关。
        // 端到端预算行为见下方「GAP-05 端到端预算」小节。
        Mockito.when(hotCache.backend()).thenReturn("redis");
    }

    private QdrantClient.ScoredPoint point(String chunkId, double score) {
        return new QdrantClient.ScoredPoint(chunkId, "doc-1", 0, "小节", "manual", "正文-" + chunkId, score,
                System.currentTimeMillis());
    }

    private void stubEmbedding() {
        Mockito.when(embeddingClient.embed(any()))
                .thenReturn(new EmbeddingClient.EmbedBatch(List.of(new double[]{0.1, 0.2}), 2, "hash-ngram-768",
                        true, 1));
    }

    @Test
    void blankQueryIsRejected() {
        assertThrows(BizException.class, () -> retrievalService.retrieve("t1", "  ", 5, true));
        assertThrows(BizException.class, () -> retrievalService.retrieve("t1", null, 5, true));
    }

    @Test
    void cacheHitShortCircuitsVectorSearch() {
        RetrievalService.SearchHit cached = new RetrievalService.SearchHit("c1", "doc-1", "标题", 0, "小节",
                "正文", 0.9, 0.95, "manual", System.currentTimeMillis(), "");
        Mockito.doReturn(Optional.of(List.of(cached)))
                .when(hotCache).get(anyString(), anyString(), any());

        RetrievalService.RetrievalOutcome outcome = retrievalService.retrieve("t1", "缓存问题", 5, true);

        assertTrue(outcome.cacheHit());
        assertEquals("hot", outcome.tier());
        assertEquals(1, outcome.hits().size());
        Mockito.verifyNoInteractions(qdrant);
        assertEquals(1.0, metrics.cacheHitRate(), 1e-9);
    }

    @Test
    void rerankDegradationFallsBackToRecallOrderWithoutBreakingRetrieval() {
        stubEmbedding();
        Mockito.when(qdrant.search(anyString(), any(), anyInt()))
                .thenReturn(List.of(point("c1", 0.9), point("c2", 0.8), point("c3", 0.7)));
        // 重排后端不可用 → 返回空批次 + degraded
        Mockito.when(rerankClient.rerank(anyString(), any(), anyInt()))
                .thenReturn(new RerankClient.RerankBatch(List.of(), "unavailable", true));

        RetrievalService.RetrievalOutcome outcome = retrievalService.retrieve("t1", "检索问题", 2, false);

        assertEquals(2, outcome.hits().size(), "降级后仍应按召回分返回 TOP-K");
        assertEquals("c1", outcome.hits().get(0).chunkId());
        assertEquals("warm", outcome.tier());
        assertEquals(1L, metrics.snapshot().get("rerank_degraded"));
    }

    @Test
    void rerankResultDrivesFinalOrderAndScore() {
        stubEmbedding();
        Mockito.when(qdrant.search(anyString(), any(), anyInt()))
                .thenReturn(List.of(point("c1", 0.9), point("c2", 0.8)));
        Mockito.when(rerankClient.rerank(anyString(), any(), anyInt()))
                .thenReturn(new RerankClient.RerankBatch(List.of(
                        new RerankClient.RerankedHit("c2", 0.8, 0.97, 1, 0),
                        new RerankClient.RerankedHit("c1", 0.9, 0.42, 0, 1)), "lexical", false));

        RetrievalService.RetrievalOutcome outcome = retrievalService.retrieve("t1", "检索问题", 2, false);

        assertEquals("c2", outcome.hits().get(0).chunkId(), "重排后首条应为重排分最高者");
        assertEquals(0.97, outcome.hits().get(0).rerankScore(), 1e-9);
        assertEquals(0.8, outcome.hits().get(0).score(), 1e-9, "召回分须保留，供前端对照");
    }

    @Test
    void vectorStoreFailureSurfacesAsUpstreamUnavailable() {
        stubEmbedding();
        Mockito.when(qdrant.search(anyString(), any(), anyInt()))
                .thenThrow(new BizException(com.agent.body.common.ErrorCode.AGENT_UPSTREAM_UNAVAILABLE, "Qdrant 检索失败"));

        BizException exception = assertThrows(BizException.class,
                () -> retrievalService.retrieve("t1", "检索问题", 5, false));
        assertEquals(com.agent.body.common.ErrorCode.AGENT_UPSTREAM_UNAVAILABLE, exception.errorCode());
    }

    @Test
    void emptyResultIsReportedAsNoHitNotFabricated() {
        stubEmbedding();
        Mockito.when(qdrant.search(anyString(), any(), anyInt())).thenReturn(List.of());
        Mockito.when(rerankClient.rerank(anyString(), any(), anyInt()))
                .thenReturn(new RerankClient.RerankBatch(List.of(), "skipped", false));

        RetrievalService.RetrievalOutcome outcome = retrievalService.retrieve("t1", "无结果问题", 5, false);

        assertTrue(outcome.hits().isEmpty());
        assertEquals(0.0, metrics.searchHitRate(), 1e-9);
    }

    @Test
    void tierViewExposesHotnessAndStore() {
        Mockito.when(hotCache.accessCount(anyString(), anyString())).thenReturn(5L);
        var view = retrievalService.tierView("t1", "问题");
        assertNotNull(view.get("tier"));
        assertNotNull(view.get("store"));
        assertTrue(((Number) view.get("hotness")).doubleValue() > 0);
    }

    // ─────────── IN-05 迭代检索预留（接口就绪，循环未实现须如实标注）───────────

    @Test
    void retrievePlanRunsSinglePassAndDeclaresLoopNotImplemented() {
        stubEmbedding();
        Mockito.when(qdrant.search(anyString(), any(), anyInt())).thenReturn(List.of(point("c1", 0.9)));
        Mockito.when(rerankClient.rerank(anyString(), any(), anyInt()))
                .thenReturn(new RerankClient.RerankBatch(
                        List.of(new RerankClient.RerankedHit("c1", 0.9, 0.95, 0, 0)), "lexical", false));

        RetrievalService.PlanOutcome single = retrievalService.retrievePlan("t1", "原始问题", null, 1, 5, false);

        assertEquals("原始问题", single.effectiveQuery());
        assertEquals("single_pass", single.iterationLoop());
        assertEquals(3, single.maxIterations());
        assertEquals(1, single.hits().size());
    }

    @Test
    void retrievePlanUsesRefineQueryAndHonestlyMarksMultiRoundAsDisabled() {
        stubEmbedding();
        Mockito.when(qdrant.search(anyString(), any(), anyInt())).thenReturn(List.of(point("c1", 0.9)));
        Mockito.when(rerankClient.rerank(anyString(), any(), anyInt()))
                .thenReturn(new RerankClient.RerankBatch(List.of(), "unavailable", true));

        RetrievalService.PlanOutcome multi = retrievalService.retrievePlan("t1", "原始问题", "改写后问题", 5, 5, false);

        assertEquals("改写后问题", multi.effectiveQuery(), "refine_query 非空时应以它作为实际检索词");
        assertEquals(3, multi.iteration(), "迭代轮数应被裁剪到上限 3");
        assertEquals("not_enabled", multi.iterationLoop(), "多轮循环未实现必须如实标注，不得伪造多轮结果");
        assertFalse(multi.hits().isEmpty(), "降级仍应返回单轮召回结果");
    }

    @Test
    void retrievePlanBlankRefineQueryFallsBackToOriginalQuery() {
        stubEmbedding();
        Mockito.when(qdrant.search(anyString(), any(), anyInt())).thenReturn(List.of());
        Mockito.when(rerankClient.rerank(anyString(), any(), anyInt()))
                .thenReturn(new RerankClient.RerankBatch(List.of(), "skipped", false));

        RetrievalService.PlanOutcome plan = retrievalService.retrievePlan("t1", "原始问题", "   ", 1, 5, false);

        assertEquals("原始问题", plan.effectiveQuery());
        assertEquals("", plan.refineQuery());
    }

    // ─────────── GAP-05 端到端预算（2026-09-19 · 与 REC-01 同路线）───────────

    @Test
    void warmPipelineOverBudgetSurfacesAsTimeoutNotEmptyResult() {
        // 向量化挂死（超过预算）→ 必须抛 AGENT_TIMEOUT（504 语义），
        // **不得**返回空结果冒充「没有数据」—— 那会把「慢」塌缩成「无结果」。
        StorageFacade storage = new StorageFacade(new InMemoryMetadataStore(), hotCache, qdrant,
                new TierRouter(3600, 2.0, 1.0));
        RetrievalService tight = new RetrievalService(embeddingClient, qdrant, rerankClient, storage, metrics, 50, 200);
        stubEmbeddingSlow(2_000);

        BizException exception = assertThrows(BizException.class,
                () -> tight.retrieve("t1", "慢检索问题", 5, false));
        assertEquals(com.agent.body.common.ErrorCode.AGENT_TIMEOUT, exception.errorCode());
        Mockito.verify(qdrant, Mockito.never()).search(anyString(), any(), anyInt());
    }

    @Test
    void budgetGuardReturnsValueWhenWithinBudgetAndPropagatesBusinessErrors() {
        assertEquals("ok", BudgetGuard.runWithBudget(() -> "ok", 1_000, "op"));
        // 业务异常原样穿透 —— 预算执行器只负责时间，不改写业务错误语义。
        BizException biz = new BizException(com.agent.body.common.ErrorCode.AGENT_UPSTREAM_UNAVAILABLE, "嵌入不可用");
        BizException propagated = assertThrows(BizException.class,
                () -> BudgetGuard.runWithBudget(() -> { throw biz; }, 1_000, "op"));
        assertEquals(com.agent.body.common.ErrorCode.AGENT_UPSTREAM_UNAVAILABLE, propagated.errorCode());
    }

    @Test
    void budgetGuardZeroMeansExplicitlyUnlimitedNotInstantFailure() {
        // 与 Python 侧约定一致：<= 0 =「显式不限」，不是「0ms 内必须完成」。
        assertEquals("done", BudgetGuard.runWithBudget(() -> {
            try { Thread.sleep(30); } catch (InterruptedException ignored) { }
            return "done";
        }, 0, "op"));
    }

    private void stubEmbeddingSlow(long sleepMs) {
        // 注意：retrieve 调用的是 embedOne（mock 拦截后**不会**走到内部 embed），
        // 所以「慢」必须 stub 在 embedOne 上 —— stub embed 会被静默跳过，用例假绿。
        Mockito.when(embeddingClient.embedOne(anyString())).thenAnswer(invocation -> {
            try { Thread.sleep(sleepMs); } catch (InterruptedException ignored) { }
            return new double[]{0.1, 0.2};
        });
    }
}
