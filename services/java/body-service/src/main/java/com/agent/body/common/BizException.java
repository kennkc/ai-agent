package com.agent.body.common;

import java.util.HashMap;
import java.util.Map;

/** 业务异常（R1-08）：携带错误码 + 明细 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, String> details;

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.details = new HashMap<>();
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = new HashMap<>();
    }

    public ErrorCode errorCode() { return errorCode; }
    public Map<String, String> details() { return details; }
}
