package com.agent.collab.heartbeat;

import com.agent.collab.common.BizException;
import com.agent.collab.common.ErrorCode;
import com.agent.collab.domain.CollabDomain;
import com.agent.collab.domain.DomainRepository;
import com.agent.collab.observability.CollabBusMetrics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 心跳聚合服务（R-MC01-03）。
 *
 * <p>写入策略：窗口内上报只更新“最后一次值”缓冲区，默认每 5 秒最多落 PG 一次；
 * 查询聚合时会合并缓冲区，因此最新进度立即可见，同时避免高频心跳造成写风暴。
 * 缓冲区是心跳这种短时效旁路状态：进程崩溃最多丢失一个窗口，失联会由 stale 状态暴露。
 */
@Service
public class HeartbeatService {
    private static final Logger log = LoggerFactory.getLogger(HeartbeatService.class);
    private static final double DEFAULT_WEIGHT = 1.0d;
    private static final double MAX_WEIGHT = 100.0d;

    private final HeartbeatRepository repository;
    private final DomainRepository domains;
    private final CollabBusMetrics metrics;
    private final long minIntervalMs;
    private final long staleMs;
    private final ConcurrentHashMap<String, MemberHeartbeat> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastAccepted = new ConcurrentHashMap<>();
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "collab-heartbeat-flusher");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean flushLoopStarted = new AtomicBoolean(false);

    private volatile Instant lastFlushAt;
    private volatile int lastFlushCount;
    private volatile String lastFlushError = "";

    public HeartbeatService(HeartbeatRepository repository,
                            DomainRepository domains,
                            CollabBusMetrics metrics,
                            @Value("${app.collab.heartbeat-min-interval-ms:5000}") long minIntervalMs,
                            @Value("${app.collab.member-stale-ms:30000}") long staleMs) {
        this.repository = repository;
        this.domains = domains;
        this.metrics = metrics;
        this.minIntervalMs = Math.max(1L, minIntervalMs);
        this.staleMs = Math.max(1L, staleMs);
    }

    @PostConstruct
    void startFlusher() {
        if (flushLoopStarted.compareAndSet(false, true)) {
            flusher.scheduleWithFixedDelay(this::flushPendingSafely,
                    minIntervalMs, minIntervalMs, TimeUnit.MILLISECONDS);
        }
    }

    /** REST 上报入口：使用服务端当前时间作为快照时间。 */
    public Map<String, Object> report(String tenantId, String domainId, String memberId,
                                      Double progress, String state, Double weight) {
        return reportAt(tenantId, domainId, memberId, progress, state, weight, Instant.now());
    }

    /**
     * 可注入时间的核心上报逻辑。
     *
     * <p>窗口内不执行 PG upsert，只覆盖 pending 中的最后值；下一次立即写或周期 flush
     * 才落库。查询时会把 pending 覆盖回结果，确保“限流不等于丢数据”。
     */
    Map<String, Object> reportAt(String tenantId, String domainId, String memberId,
                                 Double progress, String state, Double weight, Instant now) {
        if (now == null) {
            throw invalid("心跳时间不能为空");
        }
        requireActiveDomain(tenantId, domainId);
        String normalizedMember = requireText(memberId, "member_id", 64);
        double normalizedProgress = requireProgress(progress);
        String normalizedState = requireState(state);
        double normalizedWeight = requireWeight(weight);
        requirePg();

        String key = key(domainId, normalizedMember);
        Instant previous = lastAccepted.get(key);
        boolean throttled = previous != null
                && Duration.between(previous, now).toMillis() < minIntervalMs;
        lastAccepted.put(key, now);

        MemberHeartbeat heartbeat = new MemberHeartbeat(
                domainId, normalizedMember, normalizedProgress, normalizedState,
                normalizedWeight, now, now, throttled);
        if (throttled) {
            pending.put(key, heartbeat);
        } else {
            pending.remove(key);
            repository.upsert(heartbeat);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accepted", true);
        body.put("throttled", throttled);
        body.put("persisted", !throttled);
        body.put("queued", throttled);
        body.put("throttle_window_ms", minIntervalMs);
        body.put("domain_id", domainId);
        body.put("member_id", normalizedMember);
        body.put("progress", normalizedProgress);
        body.put("state", normalizedState);
        body.put("weight", normalizedWeight);
        body.put("reported_at", now.toString());
        metrics.heartbeatReport(throttled);
        metrics.pendingHeartbeatsChanged(pending.size());
        return body;
    }

    /** 成员列表：stale 是查询时派生的有效状态，不写回数据库。 */
    public Map<String, Object> members(String tenantId, String domainId) {
        return membersAt(tenantId, domainId, Instant.now());
    }

    Map<String, Object> membersAt(String tenantId, String domainId, Instant now) {
        requireReadableDomain(tenantId, domainId);
        requirePg();
        List<Map<String, Object>> items = snapshot(domainId).stream()
                .map(item -> memberView(item, now))
                .toList();
        long staleCount = items.stream().filter(item -> Boolean.TRUE.equals(item.get("stale"))).count();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", items.size());
        body.put("stale_count", staleCount);
        body.put("stale_after_ms", staleMs);
        body.put("checked_at", now.toString());
        body.put("items", items);
        return body;
    }

    /** 域进度聚合：包含 stale 成员的最后已知值，并同时暴露 stale_count。 */
    public Map<String, Object> aggregate(String tenantId, String domainId) {
        return aggregateAt(tenantId, domainId, Instant.now());
    }

    Map<String, Object> aggregateAt(String tenantId, String domainId, Instant now) {
        requireReadableDomain(tenantId, domainId);
        requirePg();
        List<Map<String, Object>> members = new ArrayList<>();
        double weightedSum = 0d;
        double totalWeight = 0d;
        long staleCount = 0L;
        Instant updatedAt = null;

        for (MemberHeartbeat item : snapshot(domainId)) {
            Map<String, Object> view = memberView(item, now);
            members.add(view);
            double weight = item.weight();
            weightedSum += weight * item.progress();
            totalWeight += weight;
            if (Boolean.TRUE.equals(view.get("stale"))) {
                staleCount++;
            }
            if (updatedAt == null || (item.updatedAt() != null && item.updatedAt().isAfter(updatedAt))) {
                updatedAt = item.updatedAt();
            }
        }

        double progress = totalWeight > 0d ? weightedSum / totalWeight : 0d;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("domain_id", domainId);
        body.put("progress", round(progress));
        body.put("member_count", members.size());
        body.put("stale_count", staleCount);
        body.put("total_weight", round(totalWeight));
        body.put("updated_at", updatedAt == null ? null : updatedAt.toString());
        body.put("members", members);
        return body;
    }

    /** 健康端点暴露 pending / flush 状态，便于观察合并是否真的生效。 */
    public Map<String, Object> status() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pending_updates", pending.size());
        row.put("last_flush_at", lastFlushAt == null ? null : lastFlushAt.toString());
        row.put("last_flush_count", lastFlushCount);
        row.put("last_flush_error", lastFlushError);
        row.put("min_interval_ms", minIntervalMs);
        row.put("stale_after_ms", staleMs);
        return row;
    }

    /** 域关闭/失败时清理该域缓冲区，避免长期运行后的键泄漏。 */
    public void forgetDomain(String domainId) {
        String prefix = domainId + "|";
        pending.keySet().removeIf(key -> key.startsWith(prefix));
        lastAccepted.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** 可测试的窗口 flush；scheduled 线程只调用带当前时间的版本。 */
    void flushPendingAt(Instant now) {
        if (!repository.available()) {
            lastFlushError = repository.lastError();
            return;
        }
        List<Map.Entry<String, MemberHeartbeat>> snapshot = new ArrayList<>(pending.entrySet());
        int flushed = 0;
        int failed = 0;
        for (Map.Entry<String, MemberHeartbeat> entry : snapshot) {
            MemberHeartbeat item = entry.getValue();
            try {
                repository.upsert(new MemberHeartbeat(
                        item.domainId(), item.memberId(), item.progress(), item.state(), item.weight(),
                        item.reportedAt(), now, true));
                pending.remove(entry.getKey(), item);
                flushed++;
            } catch (Exception e) {
                failed++;
                lastFlushError = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("心跳窗口 flush 失败：domain={} member={} err={}",
                        item.domainId(), item.memberId(), e.getMessage());
            }
        }
        lastFlushAt = now;
        lastFlushCount = flushed;
        metrics.heartbeatFlushed(flushed);
        metrics.pendingHeartbeatsChanged(pending.size());
        if (failed == 0) {
            lastFlushError = "";
        }
        if (flushed > 0) {
            log.debug("心跳窗口已 flush：count={}", flushed);
        }
    }

    private void flushPendingSafely() {
        try {
            flushPendingAt(Instant.now());
        } catch (Exception e) {
            lastFlushError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("心跳窗口 flush 异常：{}", e.getMessage());
        }
    }

    /** 合并 PG 快照与尚未落库的 pending；pending 的 last-write-wins。 */
    private List<MemberHeartbeat> snapshot(String domainId) {
        Map<String, MemberHeartbeat> merged = new LinkedHashMap<>();
        for (MemberHeartbeat item : repository.list(domainId)) {
            merged.put(item.memberId(), item);
        }
        for (MemberHeartbeat item : pending.values()) {
            if (domainId.equals(item.domainId())) {
                merged.put(item.memberId(), item);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private Map<String, Object> memberView(MemberHeartbeat item, Instant now) {
        boolean stale = item.staleAt(now, staleMs);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("member_id", item.memberId());
        view.put("progress", item.progress());
        view.put("state", stale ? MemberHeartbeat.STATE_STALE : item.state());
        view.put("reported_state", item.state());
        view.put("weight", item.weight());
        view.put("reported_at", item.reportedAt() == null ? null : item.reportedAt().toString());
        view.put("updated_at", item.updatedAt() == null ? null : item.updatedAt().toString());
        view.put("stale", stale);
        view.put("throttled", item.throttled());
        return view;
    }

    private CollabDomain requireActiveDomain(String tenantId, String domainId) {
        CollabDomain domain = requireReadableDomain(tenantId, domainId);
        if (domain.closed()) {
            throw new BizException(ErrorCode.AGENT_COLLAB_DOMAIN_CLOSED,
                    "协作域已关闭，拒绝新心跳", Map.of("domain_id", domainId, "state", domain.state()));
        }
        if (!domain.active()) {
            throw new BizException(ErrorCode.AGENT_COLLAB_BUS_UNAVAILABLE,
                    "协作域尚未就绪，拒绝新心跳", Map.of("domain_id", domainId, "state", domain.state()));
        }
        return domain;
    }

    private CollabDomain requireReadableDomain(String tenantId, String domainId) {
        requirePg();
        return domains.find(tenantId == null || tenantId.isBlank() ? "default" : tenantId, domainId)
                .orElseThrow(() -> new BizException(ErrorCode.AGENT_COLLAB_DOMAIN_NOT_FOUND,
                        "协作域不存在或不属于当前租户", Map.of("domain_id", domainId)));
    }

    private void requirePg() {
        if (!repository.available()) {
            throw new BizException(ErrorCode.AGENT_COLLAB_BUS_UNAVAILABLE,
                    "PG 不可用，心跳聚合不可用（不做内存降级）：" + repository.lastError(), Map.of());
        }
        if (!domains.available()) {
            throw new BizException(ErrorCode.AGENT_COLLAB_BUS_UNAVAILABLE,
                    "PG 不可用，协作域元数据无法读取：" + domains.lastError(), Map.of());
        }
    }

    private static double requireProgress(Double progress) {
        if (progress == null || !Double.isFinite(progress) || progress < 0d || progress > 100d) {
            throw invalid("progress 必须在 0..100 之间");
        }
        return round(progress);
    }

    private static String requireState(String state) {
        String normalized = state == null || state.isBlank() ? "working" : state.trim().toLowerCase();
        if (!MemberHeartbeat.REPORTABLE_STATES.contains(normalized)) {
            throw invalid("state 必须是 " + MemberHeartbeat.REPORTABLE_STATES);
        }
        return normalized;
    }

    private static double requireWeight(Double weight) {
        double normalized = weight == null ? DEFAULT_WEIGHT : weight;
        if (!Double.isFinite(normalized) || normalized <= 0d || normalized > MAX_WEIGHT) {
            throw invalid("weight 必须是 (0, " + MAX_WEIGHT + "] 之间的有限数");
        }
        return normalized;
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " 不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw invalid(field + " 长度不能超过 " + maxLength);
        }
        return normalized;
    }

    private static BizException invalid(String message) {
        return new BizException(ErrorCode.AGENT_COLLAB_HEARTBEAT_INVALID, message, Map.of());
    }

    private static String key(String domainId, String memberId) {
        return domainId + "|" + memberId;
    }

    private static double round(double value) {
        return Math.round(value * 100d) / 100d;
    }

    @PreDestroy
    void shutdown() {
        flusher.shutdownNow();
        flushPendingSafely();
    }
}