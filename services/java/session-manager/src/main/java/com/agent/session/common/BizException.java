package com.agent.session.common;

import java.util.HashMap;
import java.util.Map;

/**
 * 业务异常（R1-08）：携带错误码 + 明细
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, String> details;

    public BizException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
        this.details = new HashMap<>();
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.details = new HashMap<>();
    }

    public BizException(ErrorCode errorCode, String message, Map<String, String> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details == null ? new HashMap<>() : details;
    }

    public ErrorCode errorCode() { return errorCode; }
    public Map<String, String> details() { return details; }
}
