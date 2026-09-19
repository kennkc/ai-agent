package com.agent.tool.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 标准化调用结果（R5-02）。
 *
 * <p>无论成功、参数错、被拦、超时还是熔断，**形状恒定**——
 * 这是"错误友好"（R5-02 验收）与"planned 三态判定"（前端据状态码区分降级/故障）的前提。
 * 字段级 {@code details} 一并带回，便于 LLM 自我修正重试。
 */
public final class ToolCallResult {

    private final String callId;
    private final String toolName;
    private final String toolVersion;
    private final boolean success;
    private final String output;
    private final String errorCode;
    private final String errorMessage;
    private final Map<String, String> details;
    private final long latencyMs;
    private final boolean sandboxed;
    private final String sandboxBackend;
    private final boolean degraded;
    private final String auditId;

    private ToolCallResult(String callId, String toolName, String toolVersion, boolean success, String output,
                           String errorCode, String errorMessage, Map<String, String> details, long latencyMs,
                           boolean sandboxed, String sandboxBackend, boolean degraded, String auditId) {
        this.callId = callId;
        this.toolName = toolName;
        this.toolVersion = toolVersion;
        this.success = success;
        this.output = output;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.details = details == null ? Map.of() : Map.copyOf(details);
        this.latencyMs = latencyMs;
        this.sandboxed = sandboxed;
        this.sandboxBackend = sandboxBackend;
        this.degraded = degraded;
        this.auditId = auditId;
    }

    public static ToolCallResult failure(String callId, String toolName, String toolVersion, String errorCode,
                                         String errorMessage, long latencyMs) {
        return failure(callId, toolName, toolVersion, errorCode, errorMessage, latencyMs, Map.of());
    }

    public static ToolCallResult failure(String callId, String toolName, String toolVersion, String errorCode,
                                         String errorMessage, long latencyMs, Map<String, String> details) {
        return new ToolCallResult(callId, toolName, toolVersion, false, null, errorCode, errorMessage,
                details, latencyMs, false, null, false, null);
    }

    public static ToolCallResult success(String callId, ToolDefinition definition, String output, long latencyMs,
                                         boolean sandboxed, String sandboxBackend, boolean degraded) {
        return new ToolCallResult(callId, definition.meta().name(), definition.meta().version(), true, output, null,
                null, Map.of(), latencyMs, sandboxed, sandboxBackend, degraded, null);
    }

    public ToolCallResult withAudit(String auditId) {
        return new ToolCallResult(callId, toolName, toolVersion, success, output, errorCode, errorMessage,
                details, latencyMs, sandboxed, sandboxBackend, degraded, auditId);
    }

    public ToolCallResult withSandbox(boolean sandboxed, String sandboxBackend, boolean degraded) {
        return new ToolCallResult(callId, toolName, toolVersion, success, output, errorCode, errorMessage,
                details, latencyMs, sandboxed, sandboxBackend, degraded, auditId);
    }

    public String callId() { return callId; }
    public String toolName() { return toolName; }
    public String toolVersion() { return toolVersion; }
    public boolean success() { return success; }
    public String output() { return output; }
    public String errorCode() { return errorCode; }
    public String errorMessage() { return errorMessage; }
    public Map<String, String> details() { return details; }
    public long latencyMs() { return latencyMs; }
    public boolean sandboxed() { return sandboxed; }
    public String sandboxBackend() { return sandboxBackend; }
    public boolean degraded() { return degraded; }
    public String auditId() { return auditId; }

    public Map<String, Object> toMap() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("call_id", callId);
        row.put("tool_name", toolName);
        row.put("tool_version", toolVersion);
        row.put("success", success);
        row.put("output", output);
        row.put("error_code", errorCode);
        row.put("error_message", errorMessage);
        if (!details.isEmpty()) row.put("details", details);
        row.put("latency_ms", latencyMs);
        row.put("sandboxed", sandboxed);
        row.put("sandbox_backend", sandboxBackend);
        row.put("degraded", degraded);
        row.put("audit_id", auditId);
        return row;
    }

    public static String newCallId() {
        return "call-" + UUID.randomUUID().toString().substring(0, 8);
    }
}