package com.agent.tool.common;

import java.util.HashMap;
import java.util.Map;

/** 业务异常（R1-08）：携带错误码 + 明细 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, String> details = new HashMap<>();

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message, Map<String, String> details) {
        super(message);
        this.errorCode = errorCode;
        if (details != null) this.details.putAll(details);
    }

    public ErrorCode errorCode() { return errorCode; }
    public Map<String, String> details() { return details; }

    public BizException with(String key, String value) {
        if (value != null) this.details.put(key, value);
        return this;
    }
}