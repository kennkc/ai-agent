package com.agent.tool.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
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
 * 统一异常出口（R1-08）：与 body-service / sense-service 同源形状
 * {@code {code, message, details}}，覆盖业务 / 参数 / 路由层（404·405·415）/ 兜底五档。
 *
 * <p>"未映射路由 → 404"这条不能少：否则 planned 端点会被兜底吞成 500，
 * 前端无法据状态码区分「未实现」与「服务故障」（见《异常流程归纳》§2.2）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Map<String, Object>> handleBiz(BizException e) {
        HttpStatus status = switch (e.errorCode()) {
            case AGENT_UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case AGENT_FORBIDDEN, AGENT_TOOL_NOT_ALLOWED, AGENT_TOOL_ARGS_BLOCKED,
                 AGENT_SANDBOX_REJECTED -> HttpStatus.FORBIDDEN;
            case AGENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case AGENT_METHOD_NOT_ALLOWED -> HttpStatus.METHOD_NOT_ALLOWED;
            case AGENT_CONFLICT, AGENT_DUPLICATE -> HttpStatus.CONFLICT;
            case AGENT_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case AGENT_BUS_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case AGENT_UPSTREAM_UNAVAILABLE -> HttpStatus.BAD_GATEWAY;
            case AGENT_TOOL_CIRCUIT_OPEN -> HttpStatus.SERVICE_UNAVAILABLE;
            case AGENT_TOOL_ARGS_INVALID, AGENT_TOOL_EXEC_FAILED -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(body(e.errorCode().code(), e.getMessage(), e.details()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(body(ErrorCode.AGENT_BAD_REQUEST.code(), e.getMessage(), new HashMap<>()));
    }

    /** 未映射路由 → 404（**不得**落到兜底分支变 500） */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNoRoute(Exception e) {
        String path = e instanceof NoResourceFoundException noResource ? noResource.getResourcePath() : e.getMessage();
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(body(ErrorCode.AGENT_NOT_FOUND.code(), "接口不存在：" + path, new HashMap<>()));
    }

    /** 路径存在但方法不支持 → 405，与 404 区分；**必须带 Allow 头** */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        Map<String, String> details = new HashMap<>();
        details.put("method", String.valueOf(e.getMethod()));
        details.put("supported", e.getSupportedHttpMethods() == null ? "" : e.getSupportedHttpMethods().toString());
        // 注意：必须用可变 HttpHeaders 承载 Allow —— 直接改 ResponseEntity.body() 之后
        // 的 headers 会落到 ReadOnlyHttpHeaders 上抛 UnsupportedOperationException。
        HttpHeaders headers = new HttpHeaders();
        if (e.getSupportedHttpMethods() != null) headers.setAllow(e.getSupportedHttpMethods());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .headers(headers)
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
                .body(body(ErrorCode.AGENT_INTERNAL_ERROR.code(),
                        ErrorCode.AGENT_INTERNAL_ERROR.defaultMessage(), new HashMap<>()));
    }

    private Map<String, Object> body(String code, String message, Map<String, String> details) {
        return Map.of("code", code, "message", message, "details", details == null ? new HashMap<>() : details);
    }
}