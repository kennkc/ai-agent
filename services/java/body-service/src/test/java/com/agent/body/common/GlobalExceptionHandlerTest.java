package com.agent.body.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一异常出口的路由层映射（2026-09-18 缺陷回归）。
 *
 * <p>缺陷背景：兜底 {@code @ExceptionHandler(Exception.class)} 会把**未映射路由**
 * 吞成 500 {@code AGENT_INTERNAL_ERROR}——实测在未部署新端点的进程上，
 * {@code GET /api/body/knowledge/reconcile} 返回 500 而非 404，
 * 排障时"接口不存在"与"服务内部错误"无法区分（切换 API 数据源时曾据此误判）。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unmappedRouteMapsTo404NotServerError() {
        ResponseEntity<Map<String, Object>> response = handler.handleNoRoute(
                new NoResourceFoundException(HttpMethod.GET, "api/body/knowledge/reconcile"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(), "未映射路由必须是 404，不能是 500");
        assertNotNull(response.getBody());
        assertEquals(ErrorCode.AGENT_NOT_FOUND.code(), response.getBody().get("code"));
        assertTrue(String.valueOf(response.getBody().get("message")).contains("api/body/knowledge/reconcile"),
                "错误消息需带路径，便于定位");
    }

    @Test
    void noHandlerFoundAlsoMapsTo404() {
        ResponseEntity<Map<String, Object>> response = handler.handleNoRoute(
                new NoHandlerFoundException("GET", "/api/body/nope", new HttpHeaders()));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(ErrorCode.AGENT_NOT_FOUND.code(), response.getBody().get("code"));
    }

    @Test
    void wrongMethodMapsTo405WithSupportedMethods() {
        ResponseEntity<Map<String, Object>> response = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("DELETE", java.util.List.of("GET", "POST")));

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode(), "方法不支持要与 404 区分");
        assertEquals(ErrorCode.AGENT_METHOD_NOT_ALLOWED.code(), response.getBody().get("code"));
        assertEquals("DELETE", response.getBody().get("details") instanceof Map<?, ?> details
                ? details.get("method") : null);
    }

    @Test
    void unsupportedMediaTypeMapsTo415() {
        ResponseEntity<Map<String, Object>> response = handler.handleMediaType(
                new HttpMediaTypeNotSupportedException("text/plain"));

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, response.getStatusCode());
        assertEquals(ErrorCode.AGENT_BAD_REQUEST.code(), response.getBody().get("code"));
    }

    @Test
    void bizExceptionMethodNotAllowedMapsTo405() {
        ResponseEntity<Map<String, Object>> response = handler.handleBiz(
                new BizException(ErrorCode.AGENT_METHOD_NOT_ALLOWED, "只读路径不接受写入"));

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        assertEquals(ErrorCode.AGENT_METHOD_NOT_ALLOWED.code(), response.getBody().get("code"));
    }

    @Test
    void genuineServerFaultStillMapsTo500() {
        ResponseEntity<Map<String, Object>> response = handler.handleUnknown(new IllegalStateException("数据库连接池耗尽"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode(),
                "真实内部故障仍须是 500——修复不能把 500 一起改掉");
        assertEquals(ErrorCode.AGENT_INTERNAL_ERROR.code(), response.getBody().get("code"));
    }
}
