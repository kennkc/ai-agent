package com.agent.session.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

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
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(body(e.errorCode().code(), e.getMessage(), e.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> details = new HashMap<>();
        e.getBindingResult().getFieldErrors().forEach(fe -> details.put(fe.getField(), fe.getDefaultMessage()));
        return ResponseEntity.badRequest().body(body(ErrorCode.AGENT_BAD_REQUEST.code(), ErrorCode.AGENT_BAD_REQUEST.defaultMessage(), details));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(body(ErrorCode.AGENT_BAD_REQUEST.code(), e.getMessage(), new HashMap<>()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnknown(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.internalServerError().body(body(ErrorCode.AGENT_INTERNAL_ERROR.code(), ErrorCode.AGENT_INTERNAL_ERROR.defaultMessage(), new HashMap<>()));
    }

    private Map<String, Object> body(String code, String message, Map<String, String> details) {
        return Map.of("code", code, "message", message, "details", details == null ? new HashMap<>() : details);
    }
}
