package com.agent.body.store;

import com.agent.body.client.QdrantClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一存储接口（R3-01 StorageFacade）
 *
 * <p>对外只暴露一个门面：调用方（入库/检索服务）不关心数据落在哪一层，
 * 由 {@link TierRouter} 依据"访问频率 × 新鲜度"决定分层；本门面负责
 * **如实上报三层可用性**（Redis / Qdrant / PG），供 R-C03 躯体视图展示健康度。
 */
@Component
public class StorageFacade {

    private final MetadataStore metadataStore;
    private final HotCacheStore hotCache;
    private final QdrantClient qdrant;
    private final TierRouter tierRouter;

    public StorageFacade(MetadataStore metadataStore, HotCacheStore hotCache,
                         QdrantClient qdrant, TierRouter tierRouter) {
        this.metadataStore = metadataStore;
        this.hotCache = hotCache;
        this.qdrant = qdrant;
        this.tierRouter = tierRouter;
    }

    public MetadataStore metadata() { return metadataStore; }

    public HotCacheStore hot() { return hotCache; }

    public QdrantClient warm() { return qdrant; }

    public TierRouter router() { return tierRouter; }

    /** 三层存储状态（R-C03 躯体视图数据源；探针失败如实标注，不虚构可用） */
    public Map<String, Object> tierStatus() {        Map<String, Object> status = new LinkedHashMap<>();
        status.put("hot", Map.of(
                "tier", "HOT",
                "store", "redis",
                "available", hotCache.available(),
                "detail", "缓存优先策略（R3-08）",
                "hit_rate", round(hotCache.hitRate()),
                "hits", hotCache.hits(),
                "misses", hotCache.misses()));
        status.put("warm", Map.of(
                "tier", "WARM",
                "store", "qdrant",
                "available", qdrant.available(),
                "detail", "向量主库（R3-04/R3-05）",
                "collection", qdrant.collection(),
                "vector_size", qdrant.currentVectorSize()));
        status.put("cold", Map.of(
                "tier", "COLD",
                "store", "postgres",
                "available", true,
                "detail", "元数据真相源",
                "backend", metadataStore.backend()));
        status.put("rules", Map.of(
                "hot_threshold", tierRouter.hotThreshold(),
                "warm_threshold", tierRouter.warmThreshold()));
        return status;
    }

    /**
     * 三层一致性对账（设计 §6 风险应对：「Qdrant 与 PG 数据不一致 → 元数据为真相源，定期对账任务」）。
     *
     * <p>口径：**PG 元数据是唯一真相源**，Qdrant 的向量点数应当与之相等。对账只做比对与定性
     * （缺向量 / 孤儿向量），**不做自动修复** —— 修复动作（重索引 / 清孤儿）留给人或定时任务，
     * 避免对账过程本身写坏数据。
     *
     * @return 对账报告；向量库不可用时 {@code vector_store_available=false} 且差值字段为 -1
     *         （**不以 0 冒充"完全一致"**）
     */
    public Map<String, Object> reconcile(String tenantId) {
        long documents = metadataStore.countDocuments(tenantId);
        long chunks = metadataStore.countChunks(tenantId);
        boolean vectorStoreAvailable = qdrant.available();
        long points = vectorStoreAvailable ? qdrant.count(tenantId) : -1;
        long missingVectors = vectorStoreAvailable ? Math.max(0, chunks - points) : -1;
        long orphanVectors = vectorStoreAvailable ? Math.max(0, points - chunks) : -1;
        boolean consistent = vectorStoreAvailable && missingVectors == 0 && orphanVectors == 0;

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("tenant_id", tenantId);
        report.put("truth_source", "postgres");
        report.put("documents", documents);
        report.put("chunks", chunks);
        report.put("vector_points", points);
        report.put("vector_store_available", vectorStoreAvailable);
        report.put("missing_vectors", missingVectors);
        report.put("orphan_vectors", orphanVectors);
        report.put("consistent", consistent);
        report.put("action", action(vectorStoreAvailable, consistent, missingVectors, orphanVectors));
        return report;
    }

    private String action(boolean vectorStoreAvailable, boolean consistent, long missing, long orphan) {
        if (!vectorStoreAvailable) {
            return "向量库不可用，无法对账；对账仅在 Qdrant 可达时有效";
        }
        if (consistent) {
            return "三层一致，无需处置";
        }
        StringBuilder builder = new StringBuilder();
        if (missing > 0) {
            builder.append("缺向量 ").append(missing).append(" 个 → 对相关文档重索引；");
        }
        if (orphan > 0) {
            builder.append("孤儿向量 ").append(orphan).append(" 个 → 按 doc_id 清理（删除已不存在的文档向量）；");
        }
        return builder.toString();
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
