package com.agent.body.config;

import com.agent.body.store.InMemoryMetadataStore;
import com.agent.body.store.MetadataStore;
import com.agent.body.store.PgMetadataStore;
import com.agent.body.store.TierRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 躯体层存储装配（R3-01）
 *
 * <p>冷层元数据存储按可用性选择：PG 能建表就用 {@link PgMetadataStore}，
 * 否则回落 {@link InMemoryMetadataStore} 并**显式告警**（进程重启数据即丢失，不允许静默）。
 *
 * <p>DataSource 用 {@link ObjectProvider} 软依赖获取，避免未配置 PG 时启动即失败，
 * 保证「中间件未就绪也能起服务」的可演示性。
 */
@Configuration
public class BodyStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(BodyStorageConfig.class);

    @Bean
    public MetadataStore metadataStore(ObjectProvider<DataSource> dataSourceProvider) {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            log.warn("未检测到 DataSource 配置，冷层回落内存元数据存储（重启即丢失，仅限演示）");
            return new InMemoryMetadataStore();
        }
        try {
            PgMetadataStore store = new PgMetadataStore(new JdbcTemplate(dataSource));
            log.info("冷层元数据存储：PostgreSQL（真相源）");
            return store;
        } catch (Exception e) {
            log.warn("PG 冷层不可用，回落内存元数据存储（重启即丢失，仅限演示）：{}", e.getMessage());
            return new InMemoryMetadataStore();
        }
    }

    /** 分层规则可配置（R3-01 验收：分层规则可配置） */
    @Bean
    public TierRouter tierRouter(@Value("${app.body.tier.half-life-seconds:3600}") double halfLifeSeconds,
                                 @Value("${app.body.tier.hot-threshold:2.0}") double hotThreshold,
                                 @Value("${app.body.tier.warm-threshold:1.0}") double warmThreshold) {
        return new TierRouter(halfLifeSeconds, hotThreshold, warmThreshold);
    }
}
