package com.agent.sense.common;

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

/**
 * 统一异常出口的路由层映射（2026-09-18 缺陷回归）。
 *
 * <p>兜底 {@code @ExceptionHandler(Exception.class)} 曾把**未映射路由**吞成
 * 500 {@code AGENT_INTERNAL_ERROR}，使"接口不存在"与"服务内部错误"无法区分。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unmappedRouteMapsTo404NotServerError() {
        ResponseEntity<Map<String, Object>> response = handler.handleNoRoute(
                new NoResourceFoundException(HttpMethod.GET, "api/sense/definitely-missing"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(), "未映射路由必须是 404，不能是 500");
        assertEquals(ErrorCode.AGENT_NOT_FOUND.code(), response.getBody().get("code"));
    }

    @Test
    void noHandlerFoundAlsoMapsTo404() {
        ResponseEntity<Map<String, Object>> response = handler.handleNoRoute(
                new NoHandlerFoundException("GET", "/api/sense/nope", new HttpHeaders()));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void wrongMethodMapsTo405() {
        ResponseEntity<Map<String, Object>> response = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("DELETE", java.util.List.of("GET")));

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode(), "方法不支持要与 404 区分");
        assertEquals(ErrorCode.AGENT_METHOD_NOT_ALLOWED.code(), response.getBody().get("code"));
    }

    @Test
    void unsupportedMediaTypeMapsTo415() {
        ResponseEntity<Map<String, Object>> response = handler.handleMediaType(
                new HttpMediaTypeNotSupportedException("text/plain"));

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, response.getStatusCode());
    }

    @Test
    void genuineServerFaultStillMapsTo500() {
        ResponseEntity<Map<String, Object>> response = handler.handleUnknown(new IllegalStateException("采集通道异常"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode(),
                "真实内部故障仍须是 500——修复不能把 500 一起改掉");
    }
}
