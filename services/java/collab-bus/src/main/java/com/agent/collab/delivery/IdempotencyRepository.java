package com.agent.collab.delivery;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 幂等键仓储（R-MC01-02）。
 *
 * <p>消费端按 {@code request_id} 去重，保证「同一逻辑消息重投不产生重复副作用」。
 * 键的作用域是 <b>(domain_id, request_id)</b> —— 同一 request_id 在不同域中是相互独立的语义。
 *
 * <p>原子性：用 {@code INSERT ... ON CONFLICT DO NOTHING} 实现「首次标记」，
 * 影响行数 1 表示本次是首次（可执行）；0 表示已见过（直接 ack 丢弃）。
 * 依赖数据库唯一约束而非"先查后插"，因此并发重投也安全。
 *
 * <p>与域元数据同样属于**核心状态**：PG 不可用时明确失败（503），不做内存降级 ——
 * 内存去重表在重启后清空，会让重投消息被当作新消息处理，直接破坏幂等承诺。
 */
@Repository
public class IdempotencyRepository {
    private static final Logger log = LoggerFactory.getLogger(IdempotencyRepository.class);

    private static final String DDL = "CREATE TABLE IF NOT EXISTS collab_seen_request ("
            + " domain_id VARCHAR(64) NOT NULL,"
            + " request_id VARCHAR(128) NOT NULL,"
            + " member_id VARCHAR(64) NOT NULL DEFAULT '',"
            + " seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),"
            + " PRIMARY KEY (domain_id, request_id)"
            + ")";
    private static final String INDEX_DDL =
            "CREATE INDEX IF NOT EXISTS idx_collab_seen_seen_at ON collab_seen_request(seen_at)";

    private final JdbcTemplate jdbc;
    private volatile boolean available = false;
    private volatile String lastError = "";

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void initSchema() {
        try {
            jdbc.execute(DDL);
            jdbc.execute(INDEX_DDL);
            available = true;
            lastError = "";
            log.info("幂等键表就绪：collab_seen_request（backend=postgres）");
        } catch (Exception e) {
            available = false;
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("PG 不可用，幂等去重不可用（不做内存降级）：{}", lastError);
        }
    }

    public boolean available() {
        return available;
    }

    public String lastError() {
        return lastError;
    }

    /**
     * 标记 request_id 已被处理。
     *
     * @return {@code true} = 首次（调用方应执行业务）；{@code false} = 重复（调用方直接 ack 丢弃）
     */
    public boolean markSeen(String domainId, String requestId, String memberId) {
        int inserted = jdbc.update(
                "INSERT INTO collab_seen_request(domain_id, request_id, member_id) VALUES (?, ?, ?) "
                        + "ON CONFLICT (domain_id, request_id) DO NOTHING",
                domainId, requestId, memberId == null ? "" : memberId);
        return inserted > 0;
    }

    public boolean seen(String domainId, String requestId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM collab_seen_request WHERE domain_id = ? AND request_id = ?",
                Integer.class, domainId, requestId);
        return n != null && n > 0;
    }

    public int countByDomain(String domainId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM collab_seen_request WHERE domain_id = ?", Integer.class, domainId);
        return n == null ? 0 : n;
    }

    /** 清理过期幂等键（与消息保留期一致，避免表无限增长）。 */
    public int cleanupOlderThanHours(int hours) {
        return jdbc.update(
                "DELETE FROM collab_seen_request WHERE seen_at < now() - (? || ' hours')::interval",
                String.valueOf(Math.max(1, hours)));
    }
}
