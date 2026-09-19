package com.agent.tool.common;

/**
 * 统一错误码（R1-08 体系，tool-executor 侧对齐副本）。
 * 格式：{ "code": "AGENT_XXX", "message": "...", "details": {} }
 */
public enum ErrorCode {

    AGENT_BAD_REQUEST("AGENT_BAD_REQUEST", "请求参数不合法"),
    AGENT_NOT_FOUND("AGENT_NOT_FOUND", "资源不存在"),
    /** 路径存在但不支持该 HTTP 方法——与 404 区分，避免误判为"接口不存在" */
    AGENT_METHOD_NOT_ALLOWED("AGENT_METHOD_NOT_ALLOWED", "请求方法不被支持"),
    AGENT_UNAUTHORIZED("AGENT_UNAUTHORIZED", "未认证或 Token 无效"),
    AGENT_FORBIDDEN("AGENT_FORBIDDEN", "无权限访问"),
    AGENT_CONFLICT("AGENT_CONFLICT", "资源冲突"),
    AGENT_DUPLICATE("AGENT_DUPLICATE", "资源已存在"),
    AGENT_TIMEOUT("AGENT_TIMEOUT", "调用超时"),
    AGENT_BUS_UNAVAILABLE("AGENT_BUS_UNAVAILABLE", "总线通道不可用"),
    AGENT_UPSTREAM_UNAVAILABLE("AGENT_UPSTREAM_UNAVAILABLE", "上游服务不可用"),
    AGENT_INTERNAL_ERROR("AGENT_INTERNAL_ERROR", "服务内部错误"),
    // ── Phase 5 四肢期（R5-07 / R5-03）：工具层专属错误码 ──
    /** 工具不在白名单（含未注册）——**调用被拒绝，未触达执行器** */
    AGENT_TOOL_NOT_ALLOWED("AGENT_TOOL_NOT_ALLOWED", "工具不在白名单或被禁用"),
    /** 参数 JSON Schema 校验失败 */
    AGENT_TOOL_ARGS_INVALID("AGENT_TOOL_ARGS_INVALID", "工具参数校验失败"),
    /** 参数命中敏感模式（危险命令 / 文件删除 / 提权 / SSRF 内网地址） */
    AGENT_TOOL_ARGS_BLOCKED("AGENT_TOOL_ARGS_BLOCKED", "参数命中安全拦截规则"),
    /** 沙箱拒绝执行该任务 */
    AGENT_SANDBOX_REJECTED("AGENT_SANDBOX_REJECTED", "沙箱拒绝执行"),
    /** 连续失败触发熔断，快速失败 */
    AGENT_TOOL_CIRCUIT_OPEN("AGENT_TOOL_CIRCUIT_OPEN", "工具熔断中，暂不可用"),
    /** 工具执行返回业务失败（非平台故障） */
    AGENT_TOOL_EXEC_FAILED("AGENT_TOOL_EXEC_FAILED", "工具执行失败");

    private final String code;
    private final String defaultMessage;

    ErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String code() { return code; }
    public String defaultMessage() { return defaultMessage; }
}