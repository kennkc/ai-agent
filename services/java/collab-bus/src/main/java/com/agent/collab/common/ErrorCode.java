package com.agent.collab.common;

/**
 * 协作总线错误码（沿用项目统一错误信封 {code, message, details}）。
 *
 * <p>与既有约定一致：4xx 表示调用方问题、5xx 表示服务侧问题；404 与 5xx 必须可区分
 * —— 这是前端判定「端点未实现」与「服务故障」的前提。
 */
public enum ErrorCode {
    AGENT_BAD_REQUEST(400),
    AGENT_UNAUTHORIZED(401),
    AGENT_FORBIDDEN(403),
    AGENT_NOT_FOUND(404),
    AGENT_METHOD_NOT_ALLOWED(405),
    AGENT_CONFLICT(409),
    AGENT_INTERNAL(500),
    AGENT_UNAVAILABLE(503),
    AGENT_TIMEOUT(504),
    // MC-01 专属
    AGENT_COLLAB_DOMAIN_NOT_FOUND(404),
    AGENT_COLLAB_DOMAIN_CLOSED(409),
    AGENT_COLLAB_BUS_UNAVAILABLE(503),
    AGENT_COLLAB_HEARTBEAT_INVALID(400);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
