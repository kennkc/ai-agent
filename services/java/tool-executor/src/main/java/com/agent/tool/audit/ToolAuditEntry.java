package com.agent.tool.audit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条工具调用审计记录（R5-08 验收：全量记录 工具/参数/结果/耗时/租户）。
 *
 * @param argsSummary 参数**摘要**（不含完整敏感值，避免审计库本身成为泄露面）
 */
public record ToolAuditEntry(
        String auditId,
        String callId,
        String tenantId,
        String toolName,
        String toolVersion,
        String argsSummary,
        boolean success,
        String output,
        String errorCode,
        String errorMessage,
        long latencyMs,
        boolean sandboxed,
        String sandboxBackend,
        boolean degraded,
        long createdAt,
        String requestedBy
) {
    public Map<String, Object> toMap() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("audit_id", auditId);
        row.put("call_id", callId);
        row.put("tenant_id", tenantId);
        row.put("tool_name", toolName);
        row.put("tool_version", toolVersion);
        row.put("args", argsSummary);
        row.put("success", success);
        row.put("output", output);
        row.put("error_code", errorCode);
        row.put("error_message", errorMessage);
        row.put("latency_ms", latencyMs);
        row.put("sandboxed", sandboxed);
        row.put("sandbox_backend", sandboxBackend);
        row.put("degraded", degraded);
        row.put("created_at", createdAt);
        row.put("requested_by", requestedBy);
        return row;
    }
}