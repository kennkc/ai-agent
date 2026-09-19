package com.agent.tool.tools;

import com.agent.tool.common.BizException;
import com.agent.tool.guard.ToolGuard;
import com.agent.tool.model.ToolContext;
import com.agent.tool.model.ToolOutcome;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 工具（R5-04）：域名白名单 + SSRF 拦截（测试案例 TC-03）。
 *
 * <p>全部用例**离线可跑**：白名单/SSRF 判定都发生在发出请求之前，
 * 因此断言的是"闸有没有拦"，不依赖外网可达。
 */
class HttpToolTest {

    private final ToolGuard guard = new ToolGuard("calculator,http,code");
    private final HttpTool tool = new HttpTool(guard, "api.open-meteo.com,httpbin.org", 8192);
    private final ToolContext context = ToolContext.of("test", "call-http");

    @Test
    void domainOutsideWhitelistIsRejected() {
        ToolOutcome outcome = tool.execute(Map.of("url", "https://evil.example.com/steal"), context);
        assertFalse(outcome.success());
        assertEquals("AGENT_TOOL_ARGS_BLOCKED", outcome.errorCode());
        assertTrue(outcome.errorMessage().contains("不在白名单"));
    }

    @Test
    void internalAddressRejectedBeforeWhitelist() {
        BizException e = assertThrows(BizException.class,
                () -> tool.execute(Map.of("url", "http://169.254.169.254/latest/meta-data/"), context));
        assertEquals("AGENT_TOOL_ARGS_BLOCKED", e.errorCode().code(),
                "云元数据地址必须被 SSRF 闸拦住，不能走到白名单判定");
    }

    @Test
    void nonHttpSchemeRejected() {
        ToolOutcome outcome = tool.execute(Map.of("url", "file:///etc/passwd"), context);
        assertFalse(outcome.success());
        assertEquals("AGENT_TOOL_ARGS_INVALID", outcome.errorCode());
    }

    @Test
    void malformedUrlRejected() {
        ToolOutcome outcome = tool.execute(Map.of("url", "not a url"), context);
        assertFalse(outcome.success());
        assertEquals("AGENT_TOOL_ARGS_INVALID", outcome.errorCode());
    }

    @Test
    void missingUrlRejected() {
        assertEquals("AGENT_TOOL_ARGS_INVALID", tool.execute(Map.of(), context).errorCode());
    }

    @Test
    void allowListExposesConfiguredDomainsForRegistry() {
        assertTrue(tool.allowDomains().contains("api.open-meteo.com"));
        assertTrue(tool.allowDomains().contains("httpbin.org"));
    }

    @Test
    void wildcardDomainMatchesSubdomainsWithoutNetworkCall() {
        HttpTool wildcard = new HttpTool(guard, "*.example.com", 4096);
        assertTrue(wildcard.allowed("api.example.com"), "通配 *.example.com 应放行子域");
        assertTrue(wildcard.allowed("deep.api.example.com"));
        assertTrue(wildcard.allowed("example.com"), "通配也应放行裸域");
        assertFalse(wildcard.allowed("example.com.evil.net"), "后缀不匹配的域不得放行");
        assertFalse(wildcard.allowed("notexample.com"));
    }

    @Test
    void exactDomainMatchIsCaseInsensitiveAndExact() {
        assertTrue(tool.allowed("api.open-meteo.com"));
        assertTrue(tool.allowed("API.Open-Meteo.COM"));
        assertFalse(tool.allowed("open-meteo.com"), "精确域不匹配则拒绝");
        assertFalse(tool.allowed("api.open-meteo.com.evil.net"));
    }
}