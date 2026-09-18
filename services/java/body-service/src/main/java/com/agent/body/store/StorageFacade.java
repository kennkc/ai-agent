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
    public Map<String, Object> tierStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
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

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
