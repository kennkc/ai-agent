package com.agent.collab.delivery;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * PostgreSQL-backed domain ownership lease.
 *
 * <p>Each collab-bus instance has a stable owner UUID. A domain can be owned by one
 * instance at a time; expired leases are reclaimed by another instance. The lease
 * table is intentionally separate from durable consumer state so an instance crash
 * degrades to a bounded takeover delay instead of permanent consumer loss.
 */
@Repository
public class DomainLeaseRepository {
    private static final Logger log = LoggerFactory.getLogger(DomainLeaseRepository.class);
    private static final String DDL = "CREATE TABLE IF NOT EXISTS collab_domain_lease ("
            + " domain_id VARCHAR(64) PRIMARY KEY,"
            + " owner_id VARCHAR(64) NOT NULL,"
            + " lease_until TIMESTAMPTZ NOT NULL,"
            + " updated_at TIMESTAMPTZ NOT NULL DEFAULT now()"
            + ")";
    private static final String INDEX_DDL =
            "CREATE INDEX IF NOT EXISTS idx_collab_domain_lease_until ON collab_domain_lease(lease_until)";

    private final JdbcTemplate jdbc;
    private final String ownerId = UUID.randomUUID().toString();
    private volatile boolean available = false;
    private volatile String lastError = "";

    public DomainLeaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        try {
            jdbc.execute(DDL);
            jdbc.execute(INDEX_DDL);
            available = true;
            lastError = "";
            log.info("协作域租约表就绪：collab_domain_lease（owner={}）", ownerId);
        } catch (Exception e) {
            available = false;
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("协作域租约表不可用：{}", lastError);
        }
    }

    public boolean tryAcquire(String domainId, long leaseMs) {
        if (!available) return false;
        String sql = "INSERT INTO collab_domain_lease(domain_id, owner_id, lease_until, updated_at) "
                + "VALUES (?, ?, now() + (? * interval '1 millisecond'), now()) "
                + "ON CONFLICT(domain_id) DO UPDATE "
                + "SET owner_id = EXCLUDED.owner_id, lease_until = EXCLUDED.lease_until, updated_at = now() "
                + "WHERE collab_domain_lease.lease_until < now() "
                + "   OR collab_domain_lease.owner_id = EXCLUDED.owner_id "
                + "RETURNING owner_id";
        try {
            List<String> owners = jdbc.queryForList(sql, String.class, domainId, ownerId, Math.max(1000L, leaseMs));
            return !owners.isEmpty() && ownerId.equals(owners.get(0));
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("协作域租约申请失败 domain={} err={}", domainId, e.getMessage());
            return false;
        }
    }

    public void renew(String domainId, long leaseMs) {
        if (!available) return;
        try {
            jdbc.update("UPDATE collab_domain_lease SET lease_until = now() + (? * interval '1 millisecond'), "
                            + "updated_at = now() WHERE domain_id = ? AND owner_id = ?",
                    Math.max(1000L, leaseMs), domainId, ownerId);
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("协作域租约续约失败 domain={} err={}", domainId, e.getMessage());
        }
    }

    public void release(String domainId) {
        if (!available) return;
        try {
            jdbc.update("DELETE FROM collab_domain_lease WHERE domain_id = ? AND owner_id = ?", domainId, ownerId);
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("协作域租约释放失败 domain={} err={}", domainId, e.getMessage());
        }
    }

    public String ownerId() { return ownerId; }

    public boolean available() { return available; }

    public String lastError() { return lastError; }

    @PreDestroy
    void cleanupOwnedLeases() {
        if (!available) return;
        try {
            jdbc.update("DELETE FROM collab_domain_lease WHERE owner_id = ?", ownerId);
        } catch (Exception e) {
            log.warn("清理实例租约失败 owner={} err={}", ownerId, e.getMessage());
        }
    }
}