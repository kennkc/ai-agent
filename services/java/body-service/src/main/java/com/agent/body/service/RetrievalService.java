package com.agent.body.service;

import com.agent.body.client.EmbeddingClient;
import com.agent.body.client.QdrantClient;
import com.agent.body.client.RerankClient;
import com.agent.body.common.BizException;
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
 * </ul>
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final EmbeddingClient embeddingClient;
    private final QdrantClient qdrant;
    private final RerankClient rerankClient;
    private final StorageFacade storage;
    private final KnowledgeMetrics metrics;
    private final int candidateTopK;

    public RetrievalService(EmbeddingClient embeddingClient, QdrantClient qdrant, RerankClient rerankClient,
                            StorageFacade storage, KnowledgeMetrics metrics,
                            @Value("${app.body.retrieval.candidate-top-k:50}") int candidateTopK) {
        this.embeddingClient = embeddingClient;
        this.qdrant = qdrant;
        this.rerankClient = rerankClient;
        this.storage = storage;
        this.metrics = metrics;
        this.candidateTopK = Math.max(1, candidateTopK);
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

        double[] vector = embeddingClient.embedOne(query);
        List<QdrantClient.ScoredPoint> candidates = qdrant.search(tenantId, vector, candidateTopK);
        RerankOutcome reranked = rerank(query, candidates, effectiveTopK);
        long latency = System.currentTimeMillis() - started;

        if (useCache) {
            storage.hot().put(tenantId, query, reranked.hits());
        }
        metrics.recordSearch(latency, !reranked.hits().isEmpty(), false, !candidates.isEmpty(), reranked.degraded());
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

    /** 该查询当前的热度分层（R3-01 分层可观测：HOT/WARM/COLD） */
    public Map<String, Object> tierView(String tenantId, String query) {
        long accessCount = storage.hot().accessCount(tenantId, query);
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
}
