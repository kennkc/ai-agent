package com.agent.collab.heartbeat;

import com.agent.collab.common.BizException;
import com.agent.collab.common.ErrorCode;
import com.agent.collab.domain.CollabDomain;
import com.agent.collab.domain.DomainRepository;
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

/**
 * 心跳聚合服务（R-MC01-03）。
 *
 * <p>语义：
 * <ul>
 *   <li><b>限流不丢数据</b>：小于最小间隔的上报标 {@code throttled=true}，但仍 upsert 最新值；</li>
 *   <li><b>权重聚合</b>：域进度 = Σ(weight × progress) / Σ(weight)；</li>
 *   <li><b>失联可见</b>：超过 stale 阈值时查询结果返回 {@code state=stale}，原条目保留。</li>
 * </ul>
 */
@Service
public class HeartbeatService {
    private static final Logger log = LoggerFactory.getLogger(HeartbeatService.class);
    private static final double DEFAULT_WEIGHT = 1.0d;
    private static final double MAX_WEIGHT = 100.0d;

    private final HeartbeatRepository repository;
    private final DomainRepository domains;
    private final long minIntervalMs;
    private final long staleMs;

    public HeartbeatService(HeartbeatRepository repository,
                            DomainRepository domains,
                            @Value("${app.collab.heartbeat-min-interval-ms:5000}") long minIntervalMs,
                            @Value("${app.collab.member-stale-ms:30000}") long staleMs) {
        this.repository = repository;
        this.domains = domains;
        this.minIntervalMs = Math.max(1L, minIntervalMs);
        this.staleMs = Math.max(1L, staleMs);
    }

    /** REST 上报入口：使用服务端当前时间作为快照时间。 */
    public Map<String, Object> report(String tenantId, String domainId, String memberId,
                                      Double progress, String state, Double weight) {
        return reportAt(tenantId, domainId, memberId, progress, state, weight, Instant.now());
    }

    /**
     * 可注入时间的核心上报逻辑，供单元/集成测试验证降频窗口。
     *
     * <p>包级可见，避免把测试时间注入能力暴露成生产 API。
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

        Optional<MemberHeartbeat> previous = repository.find(domainId, normalizedMember);
        boolean throttled = previous
                .filter(item -> item.updatedAt() != null)
                .map(item -> Duration.between(item.updatedAt(), now).toMillis() < minIntervalMs)
                .orElse(false);

        MemberHeartbeat heartbeat = new MemberHeartbeat(
                domainId, normalizedMember, normalizedProgress, normalizedState,
                normalizedWeight, now, now, throttled);
        repository.upsert(heartbeat);
        log.debug("心跳已写入：domain={} member={} progress={} throttled={}",
                domainId, normalizedMember, normalizedProgress, throttled);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accepted", true);
        body.put("throttled", throttled);
        body.put("throttle_window_ms", minIntervalMs);
        body.put("domain_id", domainId);
        body.put("member_id", normalizedMember);
        body.put("progress", normalizedProgress);
        body.put("state", normalizedState);
        body.put("weight", normalizedWeight);
        body.put("reported_at", now.toString());
        return body;
    }

    /** 成员列表：stale 是查询时派生的有效状态，不写回数据库。 */
    public Map<String, Object> members(String tenantId, String domainId) {
        return membersAt(tenantId, domainId, Instant.now());
    }

    Map<String, Object> membersAt(String tenantId, String domainId, Instant now) {
        requireReadableDomain(tenantId, domainId);
        requirePg();
        List<Map<String, Object>> items = repository.list(domainId).stream()
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

        for (MemberHeartbeat item : repository.list(domainId)) {
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

    private static double round(double value) {
        return Math.round(value * 100d) / 100d;
    }
}