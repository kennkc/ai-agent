package com.agent.collab.domain;

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
 * 协作域仓储（PG 唯一真相源）。
 *
 * <p>策略：<b>PG 不可用即明确失败</b>，不降级到内存 —— 域元数据丢失会让域不可寻址，
 * 比"明确报错"危险得多。{@link #available()} 供健康端点如实暴露。
 */
@Repository
public class DomainRepository {
    private static final Logger log = LoggerFactory.getLogger(DomainRepository.class);

    private static final String DDL = "CREATE TABLE IF NOT EXISTS collab_domain ("
            + " domain_id VARCHAR(64) PRIMARY KEY,"
            + " tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',"
            + " name VARCHAR(160) NOT NULL DEFAULT '',"
            + " state VARCHAR(16) NOT NULL DEFAULT 'active',"
            + " concurrency_limit INTEGER NOT NULL DEFAULT 8,"
            + " created_at TIMESTAMPTZ NOT NULL DEFAULT now(),"
            + " closed_at TIMESTAMPTZ"
            + ")";
    private static final String INDEX_DDL =
            "CREATE INDEX IF NOT EXISTS idx_collab_domain_tenant ON collab_domain(tenant_id, state)";

    private final JdbcTemplate jdbc;
    private volatile boolean available = false;
    private volatile String lastError = "";

    public DomainRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void initSchema() {
        try {
            jdbc.execute(DDL);
            jdbc.execute(INDEX_DDL);
            available = true;
            lastError = "";
            log.info("协作域表就绪：collab_domain（backend=postgres）");
        } catch (Exception e) {
            available = false;
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("PG 不可用，协作域管理不可用（不做内存降级）：{}", lastError);
        }
    }

    public boolean available() {
        return available;
    }

    public String lastError() {
        return lastError;
    }

    public void insert(CollabDomain d) {
        jdbc.update("INSERT INTO collab_domain(domain_id, tenant_id, name, state, concurrency_limit, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                d.domainId(), d.tenantId(), d.name(), d.state(), d.concurrencyLimit(),
                Timestamp.from(d.createdAt()));
    }

    /** 按 (tenant, domain) 查询 —— 跨租户访问天然查不到，调用方统一转 404。 */
    public Optional<CollabDomain> find(String tenantId, String domainId) {
        List<CollabDomain> rows = jdbc.query(
                "SELECT * FROM collab_domain WHERE tenant_id = ? AND domain_id = ?", MAPPER, tenantId, domainId);
        return rows.stream().findFirst();
    }

    public List<CollabDomain> listByTenant(String tenantId) {
        return jdbc.query("SELECT * FROM collab_domain WHERE tenant_id = ? ORDER BY created_at DESC",
                MAPPER, tenantId);
    }

    /** 关闭域：仅当仍为 active 时生效（幂等）。返回是否发生状态变更。 */
    public boolean close(String tenantId, String domainId) {
        int updated = jdbc.update("UPDATE collab_domain SET state = ?, closed_at = now() "
                        + "WHERE tenant_id = ? AND domain_id = ? AND state = ?",
                CollabDomain.STATE_CLOSED, tenantId, domainId, CollabDomain.STATE_ACTIVE);
        return updated > 0;
    }

    private static Instant toInstant(Timestamp ts) throws SQLException {
        return ts == null ? null : ts.toInstant();
    }

    private static final RowMapper<CollabDomain> MAPPER = (ResultSet rs, int rowNum) -> new CollabDomain(
            rs.getString("domain_id"),
            rs.getString("tenant_id"),
            rs.getString("name"),
            rs.getString("state"),
            rs.getInt("concurrency_limit"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("closed_at")));
}
