package com.agent.body.service;

import com.agent.body.client.EmbeddingClient;
import com.agent.body.client.QdrantClient;
import com.agent.body.client.RerankClient;
import com.agent.body.common.BizException;
import com.agent.body.common.BudgetGuard;
import com.agent.body.common.ErrorCode;
import com.agent.body.store.StorageFacade;
import com.agent.body.store.TierRouter;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 语义检索服务（R3-05 主链路 + R3-06 重排 + R3-08 缓存优先）
 *
 * <p>管线：Query → 缓存查（Redis）→ 未命中 → 向量化 → Qdrant TOP-50（tenant 过滤）
 * → 重排 TOP-K → 写缓存 → 返回（附 source / score / ingest_time）。
 *
 * <p>降级语义（诚实上报，不伪造）：
 * <ul>
 *   <li>重排不可用 → 按召回分返回并标记 {@code rerank_degraded}（Should 项不阻断主链路）</li>
 *   <li>缓存不可用 → 直接走 Qdrant，{@code cache_backend=unavailable}，命中率不虚增</li>
 *   <li>Qdrant / 嵌入不可用 → 抛 {@code AGENT_UPSTREAM_UNAVAILABLE}（检索是 Must 项，不返回假结果）</li>
 *   <li>warm 管线超出端到端预算（{@code app.body.retrieval.total-budget-ms}，默认 10s）→
 *       抛 {@code AGENT_TIMEOUT}（504）—— 「慢」必须如实上报，不塌缩成「无结果」（GAP-05）</li>
 * </ul>
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    /** IN-05 迭代检索轮数上限（设计：检索→验证→再检索 ≤3 轮） */
    public static final int MAX_ITERATIONS = 3;

    private final EmbeddingClient embeddingClient;
    private final QdrantClient qdrant;
    private final RerankClient rerankClient;
    private final StorageFacade storage;
    private final KnowledgeMetrics metrics;
    private final int candidateTopK;
    /** 检索端到端预算（登记表 TB-16 的 downstream 口径）：向量化 + Qdrant + 重排共享。<=0 显式不限。 */
    private final long totalBudgetMs;

    public RetrievalService(EmbeddingClient embeddingClient, QdrantClient qdrant, RerankClient rerankClient,
                            StorageFacade storage, KnowledgeMetrics metrics,
                            @Value("${app.body.retrieval.candidate-top-k:50}") int candidateTopK,
                            @Value("${app.body.retrieval.total-budget-ms:10000}") long totalBudgetMs) {
        this.embeddingClient = embeddingClient;
        this.qdrant = qdrant;
        this.rerankClient = rerankClient;
        this.storage = storage;
        this.metrics = metrics;
        this.candidateTopK = Math.max(1, candidateTopK);
        this.totalBudgetMs = Math.max(0, totalBudgetMs);
    }

    public RetrievalOutcome retrieve(String tenantId, String query, int topK, boolean useCache) {
        if (query == null || query.isBlank()) {
            throw new BizException(ErrorCode.AGENT_BAD_REQUEST, "query must not be blank");
        }
        int effectiveTopK = Math.max(1, Math.min(topK <= 0 ? 5 : topK, 50));
        long started = System.currentTimeMillis();
        storage.hot().recordAccess(tenantId, query);

        if (useCache) {
            Optional<List<SearchHit>> cached = storage.hot().get(tenantId, query, new TypeReference<>() { });
            if (cached.isPresent()) {
                List<SearchHit> hits = cached.get();
                long latency = System.currentTimeMillis() - started;
                metrics.recordSearch(latency, !hits.isEmpty(), true, false, false);
                return new RetrievalOutcome(hits, true, latency, hits.size(), false, "hot", storage.hot().backend());
            }
        }

        // 未命中缓存 → warm 管线（向量化 + Qdrant + 重排）在**端到端预算**内执行。
        // TB-16 的下游最坏耗时因此是一个常量（默认 10s），而不是「各段超时之和」（≈24s）；
        // 超预算 → 504 AGENT_TIMEOUT，绝不返回空结果冒充「没有数据」（检索是 Must 项）。
        // 缓存命中路径是 Redis 单次往返（毫秒级），不包预算。
        RetrievalOutcome outcome = BudgetGuard.runWithBudget(
                () -> retrieveWarm(tenantId, query, effectiveTopK, useCache, started),
                totalBudgetMs, "retrieve");

        metrics.recordSearch(outcome.latencyMs(), !outcome.hits().isEmpty(), false,
                outcome.candidateCount() > 0, outcome.rerankCalled());
        return outcome;
    }

    /** warm 检索管线（缓存未命中）：向量化 → Qdrant TOP-50 → 重排 TOP-K → 写缓存。 */
    private RetrievalOutcome retrieveWarm(String tenantId, String query, int effectiveTopK,
                                          boolean useCache, long started) {
        double[] vector = embeddingClient.embedOne(query);
        List<QdrantClient.ScoredPoint> candidates = qdrant.search(tenantId, vector, candidateTopK);
        RerankOutcome reranked = rerank(query, candidates, effectiveTopK);
        long latency = System.currentTimeMillis() - started;

        if (useCache) {
            storage.hot().put(tenantId, query, reranked.hits());
        }
        if (log.isDebugEnabled()) {
            log.debug("检索完成 tenant={} candidates={} hits={} latency={}ms rerankDegraded={}",
                    tenantId, candidates.size(), reranked.hits().size(), latency, reranked.degraded());
        }
        return new RetrievalOutcome(reranked.hits(), false, latency, candidates.size(), !candidates.isEmpty(),
                "warm", storage.hot().backend());
    }

    /** 重排：不可用时按召回分返回并标记降级（Should 项不得阻断检索） */
    private RerankOutcome rerank(String query, List<QdrantClient.ScoredPoint> candidates, int topK) {
        if (candidates.isEmpty()) {
            return new RerankOutcome(List.of(), false, "skipped");
        }
        Map<String, QdrantClient.ScoredPoint> byId = new LinkedHashMap<>();
        List<RerankClient.RerankCandidate> rerankCandidates = new ArrayList<>();
        for (QdrantClient.ScoredPoint point : candidates) {
            byId.put(point.chunkId(), point);
            rerankCandidates.add(new RerankClient.RerankCandidate(point.chunkId(), point.content(), point.score()));
        }
        RerankClient.RerankBatch batch = rerankClient.rerank(query, rerankCandidates, topK);
        if (batch.hits().isEmpty()) {
            List<SearchHit> fallback = candidates.stream().limit(topK)
                    .map(point -> toHit(point, point.score())).toList();
            return new RerankOutcome(fallback, true, batch.backend());
        }
        List<SearchHit> hits = new ArrayList<>();
        for (RerankClient.RerankedHit hit : batch.hits()) {
            QdrantClient.ScoredPoint point = byId.get(hit.id());
            if (point != null) {
                hits.add(toHit(point, hit.rerankScore()));
            }
        }
        return new RerankOutcome(hits, batch.degraded(), batch.backend());
    }

    private SearchHit toHit(QdrantClient.ScoredPoint point, double score) {
        String title = point.heading() == null || point.heading().isBlank() ? point.docId() : point.heading();
        return new SearchHit(point.chunkId(), point.docId(), title, point.chunkIndex(), point.heading(),
                point.content(), round(point.score()), round(score), point.source(), point.ingestTime(),
                point.ingestTime() > 0 ? Instant.ofEpochMilli(point.ingestTime()).toString() : "");
    }

    /**
     * 迭代检索预留（IN-05 Agentic RAG · 设计：预留 iteration / refine_query，M1 后实现循环）。
     *
     * <p>可信边界：本方法**只执行一轮**检索。
     * <ul>
     *   <li>{@code refine_query} 非空 → 以它作为实际检索词（等价调用方已自行改写查询）；</li>
     *   <li>{@code iteration > 1} → 如实返回 {@code iterationLoop=not_enabled}，**不伪造多轮结果**；</li>
     *   <li>{@code iteration == 1} → {@code single_pass}（本轮即完整链路）。</li>
     * </ul>
     * 多轮「检索→验证→再检索」循环由 M1 在本骨架内填充，接口与语义已冻结。
     */
    public PlanOutcome retrievePlan(String tenantId, String query, String refineQuery, int iteration,
                                    int topK, boolean useCache) {
        int effectiveIteration = Math.max(1, Math.min(iteration, MAX_ITERATIONS));
        String refined = refineQuery == null ? "" : refineQuery.trim();
        String effectiveQuery = refined.isEmpty() ? query : refined;
        RetrievalOutcome outcome = retrieve(tenantId, effectiveQuery, topK, useCache);
        return new PlanOutcome(query, effectiveQuery, refined, effectiveIteration, MAX_ITERATIONS,
                effectiveIteration > 1 ? "not_enabled" : "single_pass", outcome.hits(), outcome.latencyMs(),
                outcome.cacheHit(), outcome.tier());
    }

    /** 该查询当前的热度分层（R3-01 分层可观测：HOT/WARM/COLD） */
    public Map<String, Object> tierView(String tenantId, String query) {        long accessCount = storage.hot().accessCount(tenantId, query);
        TierRouter.Tier tier = storage.router().tierFor((int) accessCount, 0);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("tier", tier.name());
        view.put("store", tier.store());
        view.put("description", tier.description());
        view.put("access_count", accessCount);
        view.put("hotness", Math.round(storage.router().hotness((int) accessCount, 0) * 1000.0) / 1000.0);
        return view;
    }

    private double round(double value) {
        return Math.round(value * 1000000.0) / 1000000.0;
    }

    private record RerankOutcome(List<SearchHit> hits, boolean degraded, String backend) { }

    /** 检索命中（字段与 contracts/work-platform-bff-openapi.yaml 输出契约对齐） */
    public record SearchHit(String chunkId, String docId, String title, int chunkIndex, String heading,
                            String content, double score, double rerankScore, String source, long ingestTime,
                            String ingestTimeIso) { }

    public record RetrievalOutcome(List<SearchHit> hits, boolean cacheHit, long latencyMs, int candidateCount,
                                   boolean rerankCalled, String tier, String cacheBackend) { }

    /** 迭代检索计划（IN-05 预留；M1 后由多轮循环填充 rounds） */
    public record PlanOutcome(String query, String effectiveQuery, String refineQuery, int iteration,
                              int maxIterations, String iterationLoop, List<SearchHit> hits, long latencyMs,
                              boolean cacheHit, String tier) { }
}
