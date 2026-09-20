package com.agent.collab.delivery;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 幂等与投递状态仓储（R-MC01-02）。
 *
 * <p>与最初的“看到 request_id 就永久丢弃”不同，这里把状态拆成：
 * {@code pending}（已领取，等待处理）、{@code processed}（业务成功）、
 * {@code failed}（本次失败，可重投）、{@code dlq}（已进入死信）。
 * 这样既保留消费幂等，又允许失败消息按重投上限继续尝试，最后进入死信。
 *
 * <p>作用域是 {@code (domain_id, request_id)}；同一 request_id 在不同域中相互独立。
 */
@Repository
public class IdempotencyRepository {
    private static final Logger log = LoggerFactory.getLogger(IdempotencyRepository.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private static final String DDL = "CREATE TABLE IF NOT EXISTS collab_seen_request ("
            + " domain_id VARCHAR(64) NOT NULL,"
            + " request_id VARCHAR(128) NOT NULL,"
            + " member_id VARCHAR(64) NOT NULL DEFAULT '',"
            + " status VARCHAR(16) NOT NULL DEFAULT 'pending',"
            + " attempts INTEGER NOT NULL DEFAULT 0,"
            + " last_error TEXT NOT NULL DEFAULT '',"
            + " seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),"
            + " updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),"
            + " PRIMARY KEY (domain_id, request_id)"
            + ")";
    private static final String[] MIGRATIONS = {
            "ALTER TABLE collab_seen_request ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'pending'",
            "ALTER TABLE collab_seen_request ADD COLUMN IF NOT EXISTS attempts INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE collab_seen_request ADD COLUMN IF NOT EXISTS last_error TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE collab_seen_request ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now()",
            "CREATE INDEX IF NOT EXISTS idx_collab_seen_status ON collab_seen_request(status, updated_at)"
    };

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
            for (String migration : MIGRATIONS) {
                jdbc.execute(migration);
            }
            available = true;
            lastError = "";
            log.info("投递状态表就绪：collab_seen_request（backend=postgres）");
        } catch (Exception e) {
            available = false;
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("PG 不可用，可靠投递幂等状态机不可用（不做内存降级）：{}", lastError);
        }
    }

    public boolean available() {
        return available;
    }

    public String lastError() {
        return lastError;
    }

    /**
     * 原子领取一次处理权。
     *
     * <p>首次插入为 {@code CLAIMED}；已有 pending/failed 行时递增 attempts：
     * 未超过上限为 {@code RETRY}，超过上限为 {@code EXHAUSTED}。
     * processed / dlq 行返回重复结果，保证重复投递不再执行业务。
     */
    public ClaimResult claim(String domainId, String requestId, String memberId, int maxAttempts) {
        int inserted = jdbc.update(
                "INSERT INTO collab_seen_request"
                        + "(domain_id, request_id, member_id, status, attempts, seen_at, updated_at) "
                        + "VALUES (?, ?, ?, 'pending', 1, now(), now()) "
                        + "ON CONFLICT (domain_id, request_id) DO NOTHING",
                domainId, requestId, memberId == null ? "" : memberId);
        if (inserted > 0) {
            return new ClaimResult(ClaimState.CLAIMED, 1);
        }

        int updated = jdbc.update(
                "UPDATE collab_seen_request SET attempts = attempts + 1, status = 'pending',"
                        + " last_error = '', updated_at = now() "
                        + "WHERE domain_id = ? AND request_id = ? AND status IN ('pending', 'failed')",
                domainId, requestId);
        if (updated > 0) {
            int attempts = attempts(domainId, requestId);
            if (attempts > Math.max(1, maxAttempts)) {
                return new ClaimResult(ClaimState.EXHAUSTED, attempts);
            }
            return new ClaimResult(ClaimState.RETRY, attempts);
        }

        String status = status(domainId, requestId);
        if ("processed".equals(status)) {
            return new ClaimResult(ClaimState.PROCESSED, attempts(domainId, requestId));
        }
        if ("dlq".equals(status)) {
            return new ClaimResult(ClaimState.DEAD_LETTER, attempts(domainId, requestId));
        }
        return new ClaimResult(ClaimState.PROCESSED, attempts(domainId, requestId));
    }

    public void markProcessed(String domainId, String requestId) {
        jdbc.update("UPDATE collab_seen_request SET status = 'processed', last_error = '', updated_at = now() "
                + "WHERE domain_id = ? AND request_id = ?", domainId, requestId);
    }

    public void markFailed(String domainId, String requestId, String error) {
        jdbc.update("UPDATE collab_seen_request SET status = 'failed', last_error = ?, updated_at = now() "
                        + "WHERE domain_id = ? AND request_id = ?",
                truncate(error), domainId, requestId);
    }

    public void markDeadLetter(String domainId, String requestId, String error) {
        jdbc.update("UPDATE collab_seen_request SET status = 'dlq', last_error = ?, updated_at = now() "
                        + "WHERE domain_id = ? AND request_id = ?",
                truncate(error), domainId, requestId);
    }

    public String status(String domainId, String requestId) {
        List<String> rows = jdbc.query(
                "SELECT status FROM collab_seen_request WHERE domain_id = ? AND request_id = ?",
                (rs, rowNum) -> rs.getString(1), domainId, requestId);
        return rows.isEmpty() ? "" : rows.get(0);
    }
    public int attempts(String domainId, String requestId) {
        List<Integer> rows = jdbc.query(
                "SELECT attempts FROM collab_seen_request WHERE domain_id = ? AND request_id = ?",
                (rs, rowNum) -> rs.getInt(1), domainId, requestId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }
    public boolean seen(String domainId, String requestId) {
        return !status(domainId, requestId).isBlank();
    }

    public int countByDomain(String domainId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM collab_seen_request WHERE domain_id = ?", Integer.class, domainId);
        return n == null ? 0 : n;
    }

    /** 清理过期幂等记录（与消息保留期一致）。 */
    public int cleanupOlderThanHours(int hours) {
        return jdbc.update(
                "DELETE FROM collab_seen_request WHERE updated_at < now() - (? || ' hours')::interval",
                String.valueOf(Math.max(1, hours)));
    }

    private static String truncate(String error) {
        String value = error == null ? "" : error;
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    public enum ClaimState {
        CLAIMED,
        RETRY,
        PROCESSED,
        EXHAUSTED,
        DEAD_LETTER
    }

    public record ClaimResult(ClaimState state, int attempts) {
        public boolean shouldExecute() {
            return state == ClaimState.CLAIMED || state == ClaimState.RETRY;
        }

        public boolean duplicate() {
            return state == ClaimState.PROCESSED || state == ClaimState.DEAD_LETTER;
        }
    }
}