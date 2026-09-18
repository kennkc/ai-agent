package com.agent.body.store;

/**
 * 三级存储分层路由（R3-01）
 *
 * <p>热度模型（设计 §3.1）：{@code 热度 = 访问频率 × 新鲜度权重}。
 * <ul>
 *   <li>高热度 → {@link Tier#HOT}：Redis（TTL 1h，缓存/高频）</li>
 *   <li>中热度 → {@link Tier#WARM}：Qdrant（向量主库，温存储）</li>
 *   <li>冷数据 → {@link Tier#COLD}：PostgreSQL（关系元数据全量归档 · 真相源；向量由 Qdrant 独占，冷层不存向量）</li>
 * </ul>
 *
 * <p>分层规则**可配置**（验收要求"分层规则可配置"）：热/温阈值来自
 * {@code app.body.tier.hot-threshold} 与 {@code app.body.tier.warm-threshold}。
 */
public class TierRouter {

    /** 新鲜度半衰期（秒）：默认 1 小时，超过后新鲜度权重衰减到 0.5 */
    private final double halfLifeSeconds;
    private final double hotThreshold;
    private final double warmThreshold;

    public TierRouter(double halfLifeSeconds, double hotThreshold, double warmThreshold) {
        if (halfLifeSeconds <= 0) {
            throw new IllegalArgumentException("halfLifeSeconds must be positive");
        }
        if (hotThreshold < warmThreshold) {
            throw new IllegalArgumentException("hotThreshold must not be lower than warmThreshold");
        }
        this.halfLifeSeconds = halfLifeSeconds;
        this.hotThreshold = hotThreshold;
        this.warmThreshold = warmThreshold;
    }

    /** 新鲜度权重：指数衰减（0~1，越新越接近 1） */
    public double freshnessWeight(long ageSeconds) {
        if (ageSeconds <= 0) return 1.0;
        return Math.pow(0.5, ageSeconds / halfLifeSeconds);
    }

    /** 热度 = 访问频率 × 新鲜度权重（频率用对数压缩，避免热点数据无限膨胀） */
    public double hotness(int accessCount, long ageSeconds) {
        double frequency = 1.0 + Math.log1p(Math.max(accessCount, 0));
        return frequency * freshnessWeight(ageSeconds);
    }

    public Tier tierOf(double hotness) {
        if (hotness >= hotThreshold) return Tier.HOT;
        if (hotness >= warmThreshold) return Tier.WARM;
        return Tier.COLD;
    }

    public Tier tierFor(int accessCount, long ageSeconds) {
        return tierOf(hotness(accessCount, ageSeconds));
    }

    public double hotThreshold() { return hotThreshold; }

    public double warmThreshold() { return warmThreshold; }

    /** 存储分层 */
    public enum Tier {
        HOT("redis", "热层 · 缓存/高频（TTL 1h）"),
        WARM("qdrant", "温层 · 向量主库（HNSW 检索）"),
        COLD("postgres", "冷层 · 全量元数据归档（真相源）");

        private final String store;
        private final String description;

        Tier(String store, String description) {
            this.store = store;
            this.description = description;
        }

        public String store() { return store; }
        public String description() { return description; }
    }
}
