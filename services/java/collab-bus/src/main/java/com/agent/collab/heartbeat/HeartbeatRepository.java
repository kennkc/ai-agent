package com.agent.collab.heartbeat;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 心跳快照仓储（R-MC01-03）。
 *
 * <p>心跳是协作视图的实时状态，不是可随意丢弃的旁路数据：PG 不可用时
 * {@link #available()} 会明确暴露不可用，服务层统一返回 503，不做内存降级。
 */
@Repository
public class HeartbeatRepository {
    private static final Logger log = LoggerFactory.getLogger(HeartbeatRepository.class);

    private static final String DDL = "CREATE TABLE IF NOT EXISTS collab_member_heartbeat ("
            + " domain_id VARCHAR(64) NOT NULL,"
            + " member_id VARCHAR(64) NOT NULL,"
            + " progress DOUBLE PRECISION NOT NULL DEFAULT 0,"
            + " state VARCHAR(16) NOT NULL DEFAULT 'working',"
            + " weight DOUBLE PRECISION NOT NULL DEFAULT 1,"
            + " reported_at TIMESTAMPTZ NOT NULL,"
            + " updated_at TIMESTAMPTZ NOT NULL,"
            + " throttled BOOLEAN NOT NULL DEFAULT false,"
            + " PRIMARY KEY (domain_id, member_id)"
            + ")";
    private static final String INDEX_DDL =
            "CREATE INDEX IF NOT EXISTS idx_collab_member_heartbeat_domain_time "
                    + "ON collab_member_heartbeat(domain_id, reported_at DESC)";

    private static final RowMapper<MemberHeartbeat> MAPPER = (rs, rowNum) -> map(rs);

    private final JdbcTemplate jdbc;
    private volatile boolean available = false;
    private volatile String lastError = "";

    public HeartbeatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void initSchema() {
        try {
            jdbc.execute(DDL);
            jdbc.execute(INDEX_DDL);
            available = true;
            lastError = "";
            log.info("心跳快照表就绪：collab_member_heartbeat（backend=postgres）");
        } catch (Exception e) {
            available = false;
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("PG 不可用，心跳聚合不可用（不做内存降级）：{}", lastError);
        }
    }

    public boolean available() {
        return available;
    }

    public String lastError() {
        return lastError;
    }

    public Optional<MemberHeartbeat> find(String domainId, String memberId) {
        List<MemberHeartbeat> rows = jdbc.query(
                "SELECT domain_id, member_id, progress, state, weight, reported_at, updated_at, throttled "
                        + "FROM collab_member_heartbeat WHERE domain_id = ? AND member_id = ?",
                MAPPER, domainId, memberId);
        return rows.stream().findFirst();
    }

    public List<MemberHeartbeat> list(String domainId) {
        return jdbc.query(
                "SELECT domain_id, member_id, progress, state, weight, reported_at, updated_at, throttled "
                        + "FROM collab_member_heartbeat WHERE domain_id = ? ORDER BY reported_at DESC, member_id",
                MAPPER, domainId);
    }

    /** 原子 upsert：同一成员始终保留最新快照，重复上报不会产生重复行。 */
    public int upsert(MemberHeartbeat heartbeat) {
        return jdbc.update(
                "INSERT INTO collab_member_heartbeat"
                        + "(domain_id, member_id, progress, state, weight, reported_at, updated_at, throttled) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (domain_id, member_id) DO UPDATE SET "
                        + " progress = EXCLUDED.progress, state = EXCLUDED.state, weight = EXCLUDED.weight,"
                        + " reported_at = EXCLUDED.reported_at, updated_at = EXCLUDED.updated_at,"
                        + " throttled = EXCLUDED.throttled",
                heartbeat.domainId(), heartbeat.memberId(), heartbeat.progress(), heartbeat.state(),
                heartbeat.weight(), Timestamp.from(heartbeat.reportedAt()), Timestamp.from(heartbeat.updatedAt()),
                heartbeat.throttled());
    }

    /** 仅供测试/生命周期收口使用；正常查询不会删除 stale 成员。 */
    public int deleteByDomain(String domainId) {
        return jdbc.update("DELETE FROM collab_member_heartbeat WHERE domain_id = ?", domainId);
    }

    private static MemberHeartbeat map(ResultSet rs) throws SQLException {
        return new MemberHeartbeat(
                rs.getString("domain_id"),
                rs.getString("member_id"),
                rs.getDouble("progress"),
                rs.getString("state"),
                rs.getDouble("weight"),
                toInstant(rs.getTimestamp("reported_at")),
                toInstant(rs.getTimestamp("updated_at")),
                rs.getBoolean("throttled"));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}