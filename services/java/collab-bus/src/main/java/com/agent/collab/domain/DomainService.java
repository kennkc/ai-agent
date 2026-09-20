package com.agent.collab.domain;

import com.agent.collab.common.BizException;
import com.agent.collab.common.ErrorCode;
import com.agent.collab.delivery.ReliableConsumer;
import com.agent.collab.nats.NatsConnection;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.StorageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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
 * <p>域生命周期：{@code creating → active → closed}，创建失败进入 {@code failed}。
 * stream 与 durable consumer 都就绪后才标记 active；失败会停止 consumer、清理 stream
 * 并标记 failed，避免“有域无总线”的半成品。
 */
@Service
public class DomainService {
    private static final Logger log = LoggerFactory.getLogger(DomainService.class);

    private final DomainRepository repository;
    private final NatsConnection nats;
    private final ReliableConsumer consumer;
    private final int defaultConcurrencyLimit;
    private final Duration streamMaxAge;

    public DomainService(DomainRepository repository,
                         NatsConnection nats,
                         ReliableConsumer consumer,
                         @Value("${app.collab.domain-concurrency-limit:8}") int defaultConcurrencyLimit,
                         @Value("${app.collab.stream-max-age-hours:24}") long streamMaxAgeHours) {
        this.repository = repository;
        this.nats = nats;
        this.consumer = consumer;
        this.defaultConcurrencyLimit = defaultConcurrencyLimit;
        this.streamMaxAge = Duration.ofHours(streamMaxAgeHours);
    }

    public CollabDomain create(String tenantId, String name) {
        requireReady();
        String domainId = "dom-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        CollabDomain domain = new CollabDomain(domainId, tenantId, name == null ? "" : name,
                CollabDomain.STATE_CREATING, defaultConcurrencyLimit, Instant.now(), null);
        repository.insert(domain);
        try {
            addStream(domainId);
            consumer.startDomain(domainId);
            if (!repository.markActive(tenantId, domainId)) {
                throw new IllegalStateException("协作域状态未能转为 active");
            }
            log.info("协作域已创建：{}（tenant={}）", domainId, tenantId);
            return get(tenantId, domainId);
        } catch (Exception e) {
            consumer.stopDomain(domainId);
            deleteStreamQuietly(domainId);
            repository.markFailed(tenantId, domainId);
            log.error("域 {} 创建失败，已标记 failed：{}", domainId, e.getMessage());
            throw new BizException(ErrorCode.AGENT_COLLAB_BUS_UNAVAILABLE,
                    "协作域创建失败，已标记 failed：" + e.getMessage(),
                    Map.of("domain_id", domainId, "state", CollabDomain.STATE_FAILED));
        }
    }

    public CollabDomain close(String tenantId, String domainId) {
        // 关闭先改 PG 状态，再停止 consumer；NATS 暂时不可用不应阻止域收口。
        requirePg();
        CollabDomain domain = get(tenantId, domainId);
        if (domain.closed()) {
            return domain;
        }
        repository.close(tenantId, domainId);
        consumer.stopDomain(domainId);
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

    /** 启动时恢复未完成域与 active 域 consumer。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverIncompleteDomains() {
        if (!repository.available() || !nats.jetStreamAvailable()) {
            log.warn("PG/NATS 未就绪，跳过协作域启动对账");
            return;
        }
        for (CollabDomain domain : repository.listAll()) {
            if (domain.closed() || CollabDomain.STATE_FAILED.equals(domain.state())) {
                continue;
            }
            try {
                addStream(domain.domainId());
                consumer.startDomain(domain.domainId());
                if (CollabDomain.STATE_CREATING.equals(domain.state())) {
                    repository.markActive(domain.tenantId(), domain.domainId());
                    log.info("已恢复未完成协作域：{} → active", domain.domainId());
                }
            } catch (Exception e) {
                log.warn("协作域对账失败：domain={} state={} err={}",
                        domain.domainId(), domain.state(), e.getMessage());
            }
        }
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

    public static String streamName(String domainId) {
        return "COLLAB_" + domainId.toUpperCase().replace("-", "_");
    }

    static String subjectPattern(String domainId) {
        return "collab." + domainId + ".>";
    }

    private void addStream(String domainId) throws Exception {
        String name = streamName(domainId);
        if (nats.jetStreamManagement().getStreamNames().contains(name)) {
            return;
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

    private void deleteStreamQuietly(String domainId) {
        try {
            nats.jetStreamManagement().deleteStream(streamName(domainId));
        } catch (Exception e) {
            log.warn("清理域 {} stream 失败（可启动对账恢复）：{}", domainId, e.getMessage());
        }
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