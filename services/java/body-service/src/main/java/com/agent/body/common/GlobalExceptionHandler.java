package com.agent.body.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;

/**
 * 统一异常出口（R1-08）：body-service 侧补齐（此前未接入统一错误码体系）。
 *
 * <p><b>2026-09-18 修复（三服务同源缺陷）</b>：此前本类只有「业务异常 / 参数异常 / 兜底 Exception」
 * 三档，导致**未映射路由被兜底分支吞成 500 `AGENT_INTERNAL_ERROR`**——
 * 排障时"接口不存在"与"服务内部错误"无法区分：实测 `GET /api/body/knowledge/reconcile`
 * 在未部署该端点的进程上返回 500 而非 404，被误读为服务故障。
 * 现补齐路由层异常映射：404（无此路由）/ 405（方法不支持）/ 415（媒体类型不支持）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Map<String, Object>> handleBiz(BizException e) {
        HttpStatus status = switch (e.errorCode()) {
            case AGENT_UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case AGENT_FORBIDDEN -> HttpStatus.FORBIDDEN;
            case AGENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case AGENT_METHOD_NOT_ALLOWED -> HttpStatus.METHOD_NOT_ALLOWED;
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

    /** 未映射路由 → 404（**不得**落到兜底分支变 500） */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNoRoute(Exception e) {
        String path = e instanceof NoResourceFoundException noResource ? noResource.getResourcePath() : e.getMessage();
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(body(ErrorCode.AGENT_NOT_FOUND.code(), "接口不存在：" + path, new HashMap<>()));
    }

    /** 路径存在但方法不支持（如对只读路径发 POST）→ 405，与 404 区分 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        Map<String, String> details = new HashMap<>();
        details.put("method", String.valueOf(e.getMethod()));
        details.put("supported", e.getSupportedHttpMethods() == null ? "" : e.getSupportedHttpMethods().toString());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(body(ErrorCode.AGENT_METHOD_NOT_ALLOWED.code(),
                        "请求方法不被支持：" + e.getMethod() + "，允许 " + details.get("supported"), details));
    }

    /** Content-Type 不支持 → 415（此前会走兜底变 500） */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaType(HttpMediaTypeNotSupportedException e) {
        Map<String, String> details = new HashMap<>();
        details.put("content_type", String.valueOf(e.getContentType()));
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(body(ErrorCode.AGENT_BAD_REQUEST.code(),
                        "不支持的 Content-Type：" + details.get("content_type") + "（需 application/json）", details));
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
