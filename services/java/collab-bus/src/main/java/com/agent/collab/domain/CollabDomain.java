package com.agent.collab.domain;

import java.time.Instant;

/**
 * 协作域元数据（R-MC01-01）。
 *
 * <p><b>唯一真相源是 PostgreSQL 的 collab_domain 表</b>。域元数据是核心状态而非旁路信息 ——
 * 内存降级会导致「域创建了、重启后找不回」，因此 PG 不可用时 {@link DomainRepository}
 * 明确上报不可用（503），<b>不做内存降级</b>（与审计这类旁路数据的策略相反）。
 *
 * @param domainId         域 ID（格式 dom-xxxxxxxx）
 * @param tenantId         租户（跨租户访问一律 404，不泄露存在性）
 * @param name             显示名
 * @param state            active | closed
 * @param concurrencyLimit 域并发上限（本切片仅登记，Phase 6 使用）
 * @param createdAt        创建时间
 * @param closedAt         关闭时间（未关闭为 null）
 */
public record CollabDomain(
        String domainId,
        String tenantId,
        String name,
        String state,
        int concurrencyLimit,
        Instant createdAt,
        Instant closedAt) {

    public static final String STATE_ACTIVE = "active";
    public static final String STATE_CLOSED = "closed";

    public boolean closed() {
        return STATE_CLOSED.equals(state);
    }
}
