package com.agent.collab.heartbeat;

import java.time.Instant;
import java.util.Set;

/**
 * 成员心跳快照（R-MC01-03）。
 *
 * <p>{@code state} 是上报时的原始状态；查询聚合结果时会根据 {@code reportedAt}
 * 与失联阈值计算有效的 {@code state=stale}，不会把数据库里的历史上报状态覆盖掉。
 *
 * @param domainId     协作域 ID
 * @param memberId     成员 ID
 * @param progress     0..100 的进度
 * @param state        上报状态：idle | working | blocked | done
 * @param weight       聚合权重，必须为正数；等权场景使用 1.0
 * @param reportedAt   成员侧上报时间（当前 REST 入口由服务端接收时间代替）
 * @param updatedAt    记录最后写入时间，用于降频窗口判断
 * @param throttled    最近一次上报是否处于降频窗口
 */
public record MemberHeartbeat(
        String domainId,
        String memberId,
        double progress,
        String state,
        double weight,
        Instant reportedAt,
        Instant updatedAt,
        boolean throttled) {

    /** 可由客户端上报的状态；stale 是服务端派生的有效状态，不允许客户端直接上报。 */
    public static final Set<String> REPORTABLE_STATES = Set.of("idle", "working", "blocked", "done");
    public static final String STATE_STALE = "stale";

    /** 在给定时间点，该心跳是否已经超过失联阈值。 */
    public boolean staleAt(Instant now, long staleMs) {
        if (reportedAt == null || now == null) {
            return false;
        }
        long threshold = Math.max(1L, staleMs);
        return !reportedAt.plusMillis(threshold).isAfter(now);
    }
}