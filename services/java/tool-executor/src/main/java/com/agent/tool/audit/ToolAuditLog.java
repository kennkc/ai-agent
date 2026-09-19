package com.agent.tool.audit;

import com.agent.tool.common.ErrorCode;
import com.agent.tool.model.ToolCallResult;
import com.agent.tool.model.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工具调用审计（R5-08）——Kafka 事件 + PG 落库，**不可用时内存降级并显式标注**。
 *
 * <p><b>分级口径（诚实）</b>：
 * <ul>
 *   <li>{@code postgres}：PG 可达，审计真落库（真相源）；</li>
 *   <li>{@code memory}：PG 不可达，落进程内环形缓冲（**容量有限、重启即失**），
 *       响应里 {@code degraded=true}。**不静默假装落库成功**。</li>
 * </ul>
 * Kafka 仅作为事件旁路（供后续流式消费），**不作为真相源**——真相源是 PG 表
 * {@code tool_audit_log}。Kafka 不可达不影响落库（这是有意的：审计不能因为总线抖动而丢）。
 */
@Component
public class ToolAuditLog {
    private static final Logger log = LoggerFactory.getLogger(ToolAuditLog.class);

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS tool_audit_log (
                audit_id        VARCHAR(64)  PRIMARY KEY,
                call_id         VARCHAR(64)  NOT NULL,
                tenant_id       VARCHAR(64)  NOT NULL,
                tool_name       VARCHAR(96)  NOT NULL,
                tool_version    VARCHAR(32),
                args_summary    VARCHAR(2048),
                success         BOOLEAN      NOT NULL,
                output          TEXT,
                error_code      VARCHAR(64),
                error_message   VARCHAR(1024),
                latency_ms      BIGINT,
                sandboxed       BOOLEAN,
                sandbox_backend VARCHAR(48),
                degraded        BOOLEAN,
                requested_by    VARCHAR(96),
                created_at      BIGINT
            )""";

    private static final String INDEX_DDL = """
            CREATE INDEX IF NOT EXISTS idx_tool_audit_tenant
                ON tool_audit_log(tenant_id, created_at DESC)""";

    private final JdbcTemplate jdbc;
    private final int memoryCapacity;
    private final Deque<ToolAuditEntry> memoryStore = new ArrayDeque<>();
    private final AtomicLong seq = new AtomicLong(1);
    private final AtomicLong droppedCount = new AtomicLong(0);
    private volatile boolean pgAvailable = false;
    private volatile String lastError;

    public ToolAuditLog(JdbcTemplate jdbc,
                        @Value("${app.tool.audit.memory-capacity:500}") int memoryCapacity) {
        this.jdbc = jdbc;
        this.memoryCapacity = memoryCapacity;
        initSchema();
    }

    private void initSchema() {
        try {
            jdbc.execute(DDL);
            jdbc.execute(INDEX_DDL);
            pgAvailable = true;
            log.info("工具审计表就绪：tool_audit_log（backend=postgres）");
        } catch (Exception e) {
            pgAvailable = false;
            lastError = String.valueOf(e.getMessage());
            log.warn("PG 不可达，工具审计降级为内存缓冲（backend=memory, degraded=true）：{}", e.getMessage());
        }
    }

    /** 写一条审计（**每次调用都写**，不论成功/被拦/超时） */
    public String record(ToolContext context, ToolCallResult result, Map<String, Object> arguments) {
        String auditId = "audit-" + System.currentTimeMillis() + "-" + seq.getAndIncrement();
        ToolAuditEntry entry = new ToolAuditEntry(
                auditId,
                result.callId(),
                context == null ? "default" : context.tenantId(),
                result.toolName(),
                result.toolVersion(),
                summarize(arguments),
                result.success(),
                truncate(result.output(), 4000),
                result.errorCode(),
                truncate(result.errorMessage(), 1000),
                result.latencyMs(),
                result.sandboxed(),
                result.sandboxBackend(),
                result.degraded(),
                System.currentTimeMillis(),
                context == null ? "anonymous" : context.requestedBy());

        if (pgAvailable) {
            try {
                jdbc.update("""
                        INSERT INTO tool_audit_log (audit_id, call_id, tenant_id, tool_name, tool_version,
                            args_summary, success, output, error_code, error_message, latency_ms, sandboxed,
                            sandbox_backend, degraded, requested_by, created_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                        entry.auditId(), entry.callId(), entry.tenantId(), entry.toolName(), entry.toolVersion(),
                        entry.argsSummary(), entry.success(), entry.output(), entry.errorCode(),
                        entry.errorMessage(), entry.latencyMs(), entry.sandboxed(), entry.sandboxBackend(),
                        entry.degraded(), entry.requestedBy(), entry.createdAt());
                return auditId;
            } catch (Exception e) {
                // 落库失败 → 降级到内存，并让后续查询能看到 degraded
                pgAvailable = false;
                lastError = String.valueOf(e.getMessage());
                log.warn("审计落库失败，转内存缓冲：{}", e.getMessage());
            }
        }
        synchronized (memoryStore) {
            memoryStore.addLast(entry);
            while (memoryStore.size() > memoryCapacity) {
                memoryStore.removeFirst();
                droppedCount.incrementAndGet();
            }
        }
        return auditId;
    }

    /** 查询审计（租户隔离） */
    public List<Map<String, Object>> list(String tenantId, int limit) {
        if (pgAvailable) {
            try {
                return jdbc.query("""
                        SELECT audit_id, call_id, tenant_id, tool_name, tool_version, args_summary, success,
                               output, error_code, error_message, latency_ms, sandboxed, sandbox_backend,
                               degraded, requested_by, created_at
                        FROM tool_audit_log WHERE tenant_id = ? ORDER BY created_at DESC LIMIT ?""",
                        (rs, rowNum) -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("audit_id", rs.getString("audit_id"));
                            row.put("call_id", rs.getString("call_id"));
                            row.put("tenant_id", rs.getString("tenant_id"));
                            row.put("tool_name", rs.getString("tool_name"));
                            row.put("tool_version", rs.getString("tool_version"));
                            row.put("args", rs.getString("args_summary"));
                            row.put("success", rs.getBoolean("success"));
                            row.put("output", rs.getString("output"));
                            row.put("error_code", rs.getString("error_code"));
                            row.put("error_message", rs.getString("error_message"));
                            row.put("latency_ms", rs.getLong("latency_ms"));
                            row.put("sandboxed", rs.getBoolean("sandboxed"));
                            row.put("sandbox_backend", rs.getString("sandbox_backend"));
                            row.put("degraded", rs.getBoolean("degraded"));
                            row.put("created_at", rs.getLong("created_at"));
                            row.put("requested_by", rs.getString("requested_by"));
                            return row;
                        }, tenantId, limit);
            } catch (Exception e) {
                pgAvailable = false;
                lastError = String.valueOf(e.getMessage());
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        synchronized (memoryStore) {
            var iterator = memoryStore.descendingIterator();
            while (iterator.hasNext() && rows.size() < limit) {
                ToolAuditEntry entry = iterator.next();
                if (tenantId == null || tenantId.equals(entry.tenantId())) rows.add(entry.toMap());
            }
        }
        return rows;
    }

    public Map<String, Object> stats(String tenantId) {
        long total;
        long successCount;
        long blockedCount;
        long sandboxedCount;
        if (pgAvailable) {
            try {
                Integer all = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM tool_audit_log WHERE tenant_id = ?", Integer.class, tenantId);
                Integer ok = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM tool_audit_log WHERE tenant_id = ? AND success = TRUE",
                        Integer.class, tenantId);
                Integer blocked = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM tool_audit_log WHERE tenant_id = ? AND error_code IN (?,?,?)",
                        Integer.class, tenantId, ErrorCode.AGENT_TOOL_NOT_ALLOWED.code(),
                        ErrorCode.AGENT_TOOL_ARGS_BLOCKED.code(), ErrorCode.AGENT_SANDBOX_REJECTED.code());
                Integer sandboxed = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM tool_audit_log WHERE tenant_id = ? AND sandboxed = TRUE",
                        Integer.class, tenantId);
                total = all == null ? 0 : all;
                successCount = ok == null ? 0 : ok;
                blockedCount = blocked == null ? 0 : blocked;
                sandboxedCount = sandboxed == null ? 0 : sandboxed;
            } catch (Exception e) {
                pgAvailable = false;
                lastError = String.valueOf(e.getMessage());
                return memoryStats(tenantId);
            }
        } else {
            return memoryStats(tenantId);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("backend", "postgres");
        row.put("degraded", false);
        row.put("total", total);
        row.put("success", successCount);
        row.put("blocked", blockedCount);
        row.put("sandboxed", sandboxedCount);
        row.put("success_rate", total == 0 ? null : round(successCount * 1.0 / total));
        return row;
    }

    private Map<String, Object> memoryStats(String tenantId) {
        long total = 0;
        long success = 0;
        long blocked = 0;
        long sandboxed = 0;
        synchronized (memoryStore) {
            for (ToolAuditEntry entry : memoryStore) {
                if (tenantId != null && !tenantId.equals(entry.tenantId())) continue;
                total++;
                if (entry.success()) success++;
                if (isBlocked(entry.errorCode())) blocked++;
                if (entry.sandboxed()) sandboxed++;
            }
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("backend", "memory");
        row.put("degraded", true);
        row.put("total", total);
        row.put("success", success);
        row.put("blocked", blocked);
        row.put("sandboxed", sandboxed);
        row.put("success_rate", total == 0 ? null : round(success * 1.0 / total));
        row.put("dropped", droppedCount.get());
        row.put("note", "PG 不可达 —— 审计仅存进程内环形缓冲，容量 " + memoryCapacity + "，重启即失");
        return row;
    }

    public Map<String, Object> health() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("backend", pgAvailable ? "postgres" : "memory");
        row.put("available", true);
        row.put("degraded", !pgAvailable);
        row.put("last_error", lastError);
        row.put("memory_size", memoryStore.size());
        row.put("dropped", droppedCount.get());
        return row;
    }

    /**
     * 是否为"安全拦截"（与 PG 侧 stats 的 SQL 口径**同源**）。
     * 口径要点：**参数格式错（AGENT_TOOL_ARGS_INVALID）不算拦截**——那是调用方用错了，
     * 不是安全事件；只有白名单 / 敏感参数 / 沙箱拒绝才算。
     */
    private static boolean isBlocked(String errorCode) {
        return ErrorCode.AGENT_TOOL_NOT_ALLOWED.code().equals(errorCode)
                || ErrorCode.AGENT_TOOL_ARGS_BLOCKED.code().equals(errorCode)
                || ErrorCode.AGENT_SANDBOX_REJECTED.code().equals(errorCode);
    }

    /** 参数摘要（脱敏后落库） */
    private static String summarize(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) return "{}";
        Map<String, Object> safe = new LinkedHashMap<>();
        arguments.forEach((key, value) -> safe.put(key, redact(key, value)));
        return truncate(String.valueOf(safe), 2000);
    }

    /** 参数摘要脱敏：可能的凭证/密钥字段只留长度 */
    private static Object redact(String key, Object value) {
        String lower = key == null ? "" : key.toLowerCase();
        if (lower.contains("token") || lower.contains("secret") || lower.contains("password")
                || lower.contains("key") || lower.contains("credential")) {
            return value == null ? null : "***(" + String.valueOf(value).length() + " chars)";
        }
        return value;
    }

    private static String truncate(String text, int max) {
        if (text == null) return null;
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}