package com.agent.collab.common;

import java.util.LinkedHashMap;
import java.util.Map;

/** 业务异常：携带错误码与可选明细，由 {@link GlobalExceptionHandler} 统一转成错误信封。 */
public class BizException extends RuntimeException {
    private final ErrorCode code;
    private final transient Map<String, Object> details;

    public BizException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public BizException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : new LinkedHashMap<>(details);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
