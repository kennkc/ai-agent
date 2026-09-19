package com.agent.tool.model;

import java.util.Map;

/** 工具执行上下文（租户来自网关注入的请求头，**不信任请求体**） */
public record ToolContext(String tenantId, String callId, long deadlineEpochMs, String requestedBy) {

    public static ToolContext of(String tenantId, String callId) {
        return new ToolContext(tenantId, callId, System.currentTimeMillis() + 30_000, "anonymous");
    }

    public boolean expired() {
        return deadlineEpochMs > 0 && System.currentTimeMillis() > deadlineEpochMs;
    }

    /** 供工具实现拼接日志/审计用的不可变视图 */
    public Map<String, Object> describe() {
        return Map.of("tenant_id", tenantId, "call_id", callId, "requested_by", requestedBy);
    }
}