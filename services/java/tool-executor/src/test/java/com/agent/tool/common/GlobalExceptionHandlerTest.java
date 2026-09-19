package com.agent.tool.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一异常出口（R1-08）——tool-executor 侧的路由层映射回归。
 *
 * <p>新服务最容易漏的就是这一档：只写"业务/参数/兜底"，未映射路由会被吞成 500，
 * 于是前端把"端点不存在"读成"服务故障"（《异常流程归纳》§2.2 / 高危清单 6）。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unmappedRouteMapsTo404NotServerError() {
        ResponseEntity<Map<String, Object>> response = handler.handleNoRoute(
                new NoResourceFoundException(HttpMethod.GET, "api/tool/definitely-not-a-route"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ErrorCode.AGENT_NOT_FOUND.code(), response.getBody().get("code"));
        assertTrue(String.valueOf(response.getBody().get("message")).contains("definitely-not-a-route"));
    }

    @Test
    void wrongMethodMapsTo405AndCarriesAllowHeader() {
        ResponseEntity<Map<String, Object>> response = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("PUT", List.of("GET", "POST")));

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        assertEquals(ErrorCode.AGENT_METHOD_NOT_ALLOWED.code(), response.getBody().get("code"));
        assertTrue(response.getHeaders().containsKey("Allow"), "405 必须带 Allow 头（规范化口径）");
    }

    @Test
    void unsupportedMediaTypeMapsTo415() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleMediaType(new HttpMediaTypeNotSupportedException("text/plain"));
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, response.getStatusCode());
    }

    @Test
    void toolNotAllowedMapsTo403() {
        ResponseEntity<Map<String, Object>> response = handler.handleBiz(
                new BizException(ErrorCode.AGENT_TOOL_NOT_ALLOWED, "工具不在白名单：shell"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(ErrorCode.AGENT_TOOL_NOT_ALLOWED.code(), response.getBody().get("code"));
    }

    @Test
    void blockedArgumentsMapTo403AndKeepDetails() {
        ResponseEntity<Map<String, Object>> response = handler.handleBiz(
                new BizException(ErrorCode.AGENT_TOOL_ARGS_BLOCKED, "命中安全规则",
                        Map.of("rule", "rm -rf")));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("rm -rf", ((Map<?, ?>) response.getBody().get("details")).get("rule"));
    }

    @Test
    void invalidArgumentsMapTo400() {
        ResponseEntity<Map<String, Object>> response = handler.handleBiz(
                new BizException(ErrorCode.AGENT_TOOL_ARGS_INVALID, "缺少 expr"));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void circuitOpenMapsTo503SoClientCanRetryLater() {
        ResponseEntity<Map<String, Object>> response = handler.handleBiz(
                new BizException(ErrorCode.AGENT_TOOL_CIRCUIT_OPEN, "熔断中"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    }

    @Test
    void timeoutMapsTo504() {
        ResponseEntity<Map<String, Object>> response = handler.handleBiz(
                new BizException(ErrorCode.AGENT_TIMEOUT, "工具执行超时"));
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, response.getStatusCode());
    }

    @Test
    void genuineServerFaultStillMapsTo500() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleUnknown(new IllegalStateException("连接池耗尽"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(ErrorCode.AGENT_INTERNAL_ERROR.code(), response.getBody().get("code"));
    }
}