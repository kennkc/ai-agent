package com.agent.collab.domain;

import com.agent.collab.common.BizException;
import com.agent.collab.common.ErrorCode;
import com.agent.collab.nats.NatsConnection;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.StorageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 协作域服务（R-MC01-01）。
 *
 * <p>职责：域生命周期（创建/关闭/查询）+ 为每个域创建**隔离的 JetStream stream**。
 *
 * <p>主题规范（P0 定稿，本切片落地）：{@code collab.<domain_id>.<type>.<member_id>}，
 * type ∈ dispatch | result | heartbeat | negotiate。按域隔离 stream 的目的：单域消费积压
 * 不拖垮其他域，且便于按域清理。
 *
 * <p>可靠性策略：PG 不可用时**明确失败（503）**，不做内存降级（域元数据是核心状态）；
 * NATS 不可用时同样明确失败 —— 否则会出现"域已建库但没有 stream"的半成品状态。
 */
@Service
public class DomainService {
    private static final Logger log = LoggerFactory.getLogger(DomainService.class);

    private final DomainRepository repository;
    private final NatsConnection nats;
    private final int defaultConcurrencyLimit;
    private final Duration streamMaxAge;

    public DomainService(DomainRepository repository,
                         NatsConnection nats,
                         @Value("${app.collab.domain-concurrency-limit:8}") int defaultConcurrencyLimit,
                         @Value("${app.collab.stream-max-age-hours:24}") long streamMaxAgeHours) {
        this.repository = repository;
        this.nats = nats;
        this.defaultConcurrencyLimit = defaultConcurrencyLimit;
        this.streamMaxAge = Duration.ofHours(streamMaxAgeHours);
    }

    public CollabDomain create(String tenantId, String name) {
        requireReady();
        String domainId = "dom-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        CollabDomain domain = new CollabDomain(domainId, tenantId, name == null ? "" : name,
                CollabDomain.STATE_ACTIVE, defaultConcurrencyLimit, Instant.now(), null);
        repository.insert(domain);
        try {
            addStream(domainId);
        } catch (Exception e) {
            // stream 创建失败：域记录保留但明确报错，由调用方决定是否清理；
            // 不静默吞掉 —— 否则会出现"有域无总线"的假成功。
            log.error("域 {} 的 stream 创建失败：{}", domainId, e.getMessage());
            throw new BizException(ErrorCode.AGENT_COLLAB_BUS_UNAVAILABLE,
                    "协作域已建库但总线 stream 创建失败：" + e.getMessage(),
                    Map.of("domain_id", domainId));
        }
        log.info("协作域已创建：{}（tenant={}）", domainId, tenantId);
        return domain;
    }

    public CollabDomain close(String tenantId, String domainId) {
        // 关闭只涉及元数据状态变更、不触碰 stream —— 因此只要求 PG 可用。
        // 要求 NATS 可用属过度约束：总线故障时仍应允许把域收口（否则域会永久卡在 active）。
        requirePg();
        CollabDomain domain = get(tenantId, domainId);
        if (domain.closed()) {
            return domain; // 幂等：重复关闭返回当前状态，不报错
        }
        repository.close(tenantId, domainId);
        log.info("协作域已关闭：{}（tenant={}）", domainId, tenantId);
        return get(tenantId, domainId);
    }

    public CollabDomain get(String tenantId, String domainId) {
        requirePg();
        return repository.find(tenantId, domainId)
                .orElseThrow(() -> new BizException(ErrorCode.AGENT_COLLAB_DOMAIN_NOT_FOUND,
                        "协作域不存在或不属于当前租户", Map.of("domain_id", domainId)));
    }

    public List<CollabDomain> list(String tenantId) {
        requirePg();
        return repository.listByTenant(tenantId);
    }

    /** 域的运行态摘要（供后续心跳聚合与 BFF 使用）。 */
    public Map<String, Object> summary(String tenantId, String domainId) {
        CollabDomain domain = get(tenantId, domainId);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("domain_id", domain.domainId());
        row.put("name", domain.name());
        row.put("state", domain.state());
        row.put("concurrency_limit", domain.concurrencyLimit());
        row.put("created_at", domain.createdAt() == null ? null : domain.createdAt().toString());
        row.put("closed_at", domain.closedAt() == null ? null : domain.closedAt().toString());
        row.put("stream", streamName(domainId));
        row.put("subject_pattern", subjectPattern(domainId));
        return row;
    }

    static String streamName(String domainId) {
        return "COLLAB_" + domainId.toUpperCase().replace("-", "_");
    }

    static String subjectPattern(String domainId) {
        return "collab." + domainId + ".>";
    }

    private void addStream(String domainId) throws Exception {
        String name = streamName(domainId);
        if (nats.jetStreamManagement().getStreamNames().contains(name)) {
            return; // 已存在（重试场景）
        }
        StreamConfiguration config = StreamConfiguration.builder()
                .name(name)
                .subjects(subjectPattern(domainId))
                .storageType(StorageType.File)
                .maxAge(streamMaxAge)
                .build();
        nats.jetStreamManagement().addStream(config);
        log.info("已为域 {} 创建 stream：{}（subjects={}）", domainId, name, subjectPattern(domainId));
    }

    private void requireReady() {
        requirePg();
        if (!nats.jetStreamAvailable()) {
            throw new BizException(ErrorCode.AGENT_COLLAB_BUS_UNAVAILABLE,
                    "NATS/JetStream 不可用，协作域暂不可创建（降级不静默）", Map.of());
        }
    }

    private void requirePg() {
        if (!repository.available()) {
            throw new BizException(ErrorCode.AGENT_COLLAB_BUS_UNAVAILABLE,
                    "PG 不可用，协作域元数据无法读写：" + repository.lastError(), Map.of());
        }
    }
}
