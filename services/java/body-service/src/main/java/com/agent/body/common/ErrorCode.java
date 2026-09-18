package com.agent.body.common;

/**
 * 统一错误码（R1-08 体系，body-service 侧对齐副本）。
 * 格式：{ "code": "AGENT_XXX", "message": "...", "details": {} }
 */
public enum ErrorCode {

    AGENT_BAD_REQUEST("AGENT_BAD_REQUEST", "请求参数不合法"),
    AGENT_NOT_FOUND("AGENT_NOT_FOUND", "资源不存在"),
    /** 路径存在但不支持该 HTTP 方法（如对只读路径发 POST）——与 404 区分，避免误判为"接口不存在" */
    AGENT_METHOD_NOT_ALLOWED("AGENT_METHOD_NOT_ALLOWED", "请求方法不被支持"),
    AGENT_UNAUTHORIZED("AGENT_UNAUTHORIZED", "未认证或 Token 无效"),
    AGENT_FORBIDDEN("AGENT_FORBIDDEN", "无权限访问"),
    AGENT_CONFLICT("AGENT_CONFLICT", "资源冲突"),
    AGENT_DUPLICATE("AGENT_DUPLICATE", "资源已存在"),
    AGENT_TIMEOUT("AGENT_TIMEOUT", "调用超时"),
    AGENT_BUS_UNAVAILABLE("AGENT_BUS_UNAVAILABLE", "总线通道不可用"),
    AGENT_UPSTREAM_UNAVAILABLE("AGENT_UPSTREAM_UNAVAILABLE", "上游服务不可用"),
    AGENT_INTERNAL_ERROR("AGENT_INTERNAL_ERROR", "服务内部错误");

    private final String code;
    private final String defaultMessage;

    ErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String code() { return code; }
    public String defaultMessage() { return defaultMessage; }
}
