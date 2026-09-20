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
 * <p>域状态为 {@code creating | active | failed | closed}。创建域时先落 creating，
 * 等 JetStream stream 与 durable consumer 都就绪后再转 active；失败转 failed，
 * 从根源上避免“库里有 active 域、总线里却没有 stream”的半成品。
 */
@Repository
public class DomainRepository {
    private static final Logger log = LoggerFactory.getLogger(DomainRepository.class);

    private static final String DDL = "CREATE TABLE IF NOT EXISTS collab_domain ("
            + " domain_id VARCHAR(64) PRIMARY KEY,"
            + " tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',"
            + " name VARCHAR(160) NOT NULL DEFAULT '',"
            + " state VARCHAR(16) NOT NULL DEFAULT 'creating',"
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

    /** 启动恢复与对账使用：跨租户列出全部域。 */
    public List<CollabDomain> listAll() {
        return jdbc.query("SELECT * FROM collab_domain ORDER BY created_at", MAPPER);
    }

    public boolean markActive(String tenantId, String domainId) {
        return jdbc.update("UPDATE collab_domain SET state = ?, closed_at = NULL "
                        + "WHERE tenant_id = ? AND domain_id = ? AND state IN (?, ?)",
                CollabDomain.STATE_ACTIVE, tenantId, domainId,
                CollabDomain.STATE_CREATING, CollabDomain.STATE_FAILED) > 0;
    }

    public boolean markFailed(String tenantId, String domainId) {
        return jdbc.update("UPDATE collab_domain SET state = ? WHERE tenant_id = ? AND domain_id = ?",
                CollabDomain.STATE_FAILED, tenantId, domainId) > 0;
    }

    public boolean delete(String tenantId, String domainId) {
        return jdbc.update("DELETE FROM collab_domain WHERE tenant_id = ? AND domain_id = ?",
                tenantId, domainId) > 0;
    }

    /** 关闭域：非 closed 域均可关闭（含创建失败域）；重复关闭幂等。 */
    public boolean close(String tenantId, String domainId) {
        int updated = jdbc.update("UPDATE collab_domain SET state = ?, closed_at = now() "
                        + "WHERE tenant_id = ? AND domain_id = ? AND state <> ?",
                CollabDomain.STATE_CLOSED, tenantId, domainId, CollabDomain.STATE_CLOSED);
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