package com.agent.body.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/** 统一异常出口（R1-08）：body-service 侧补齐（此前未接入统一错误码体系） */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Map<String, Object>> handleBiz(BizException e) {
        HttpStatus status = switch (e.errorCode()) {
            case AGENT_UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case AGENT_FORBIDDEN -> HttpStatus.FORBIDDEN;
            case AGENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case AGENT_CONFLICT, AGENT_DUPLICATE -> HttpStatus.CONFLICT;
            case AGENT_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case AGENT_BUS_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case AGENT_UPSTREAM_UNAVAILABLE -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(body(e.errorCode().code(), e.getMessage(), e.details()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(body(ErrorCode.AGENT_BAD_REQUEST.code(), e.getMessage(), new HashMap<>()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnknown(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.internalServerError()
                .body(body(ErrorCode.AGENT_INTERNAL_ERROR.code(), ErrorCode.AGENT_INTERNAL_ERROR.defaultMessage(), new HashMap<>()));
    }

    private Map<String, Object> body(String code, String message, Map<String, String> details) {
        return Map.of("code", code, "message", message, "details", details == null ? new HashMap<>() : details);
    }
}
