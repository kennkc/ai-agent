package com.agent.tool.tools;

import com.agent.tool.guard.ToolGuard;
import com.agent.tool.model.ToolContext;
import com.agent.tool.model.ToolHandler;
import com.agent.tool.model.ToolOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * HTTP 请求工具（R5-04）——**白名单域名 + 内网地址拦截（SSRF）**。
 *
 * <p>风险应对（开发设计 §6）："HTTP 工具 SSRF → 域名白名单 + 内网 IP 拦截"。
 * 两道都实装：
 * <ul>
 *   <li>域名白名单：{@code HTTP_ALLOW_DOMAINS}，支持精确域与 {@code *.suffix} 通配；</li>
 *   <li>内网拦截：交给 {@link ToolGuard#checkNotInternalAddress}，拒绝 localhost / 127. / 10. /
 *       172.16-31. / 192.168. / 169.254.（云元数据）/ metadata 等地址。</li>
 * </ul>
 * 响应体截断到 {@code max-body-bytes}，避免把大响应灌进 LLM 上下文。
 */
@Component
public class HttpTool implements ToolHandler {
    private static final Logger log = LoggerFactory.getLogger(HttpTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "url":    { "type": "string", "minLength": 8, "maxLength": 2048 },
                "method": { "type": "string", "enum": ["GET", "POST"] },
                "body":   { "type": "string", "maxLength": 8192 },
                "timeout_ms": { "type": "integer", "minimum": 100, "maximum": 15000 }
              },
              "required": ["url"],
              "additionalProperties": false
            }""";

    private final ToolGuard guard;
    private final Set<String> allowDomains = new LinkedHashSet<>();
    private final int maxBodyBytes;
    private final HttpClient client;

    public HttpTool(ToolGuard guard,
                    @Value("${app.tool.http.allow-domains:api.open-meteo.com,httpbin.org,api.github.com}") String domains,
                    @Value("${app.tool.http.max-body-bytes:8192}") int maxBodyBytes) {
        this.guard = guard;
        this.maxBodyBytes = maxBodyBytes;
        Arrays.stream(domains.split(",")).map(item -> item.trim().toLowerCase(Locale.ROOT))
                .filter(item -> !item.isEmpty()).forEach(allowDomains::add);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        log.info("HTTP 工具域名白名单：{}", allowDomains);
    }

    public static String schema() { return SCHEMA; }

    @Override public String name() { return "http"; }

    @Override
    public ToolOutcome execute(Map<String, Object> arguments, ToolContext context) {
        Object rawUrl = arguments == null ? null : arguments.get("url");
        if (rawUrl == null) return ToolOutcome.fail("AGENT_TOOL_ARGS_INVALID", "缺少参数 url");
        String url = String.valueOf(rawUrl);
        String method = arguments.get("method") == null ? "GET" : String.valueOf(arguments.get("method")).toUpperCase(Locale.ROOT);
        int timeoutMs = arguments.get("timeout_ms") == null ? 8000
                : ((Number) arguments.get("timeout_ms")).intValue();

        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            return ToolOutcome.fail("AGENT_TOOL_ARGS_INVALID", "URL 不合法：" + url);
        }
        if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
            return ToolOutcome.fail("AGENT_TOOL_ARGS_INVALID", "仅支持 http/https");
        }
        if (uri.getHost() == null) {
            return ToolOutcome.fail("AGENT_TOOL_ARGS_INVALID", "URL 缺少主机名：" + url);
        }

        // SSRF 闸：内网地址一律拒绝（在域名白名单之前判，避免白名单里误配内网域也放行）
        guard.checkNotInternalAddress(url, name());

        // 域名白名单闸
        if (!allowed(uri.getHost())) {
            return ToolOutcome.fail("AGENT_TOOL_ARGS_BLOCKED",
                    "域名不在白名单：" + uri.getHost() + "（允许：" + String.join(", ", allowDomains) + "）",
                    Map.of("host", uri.getHost(), "allow_domains", String.join(",", allowDomains)));
        }

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(Math.min(timeoutMs, 15000)))
                    .header("User-Agent", "Agent-Lifeform-ToolExecutor/0.1");
            String body = arguments.get("body") == null ? null : String.valueOf(arguments.get("body"));
            if ("POST".equals(method)) {
                builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body))
                        .header("Content-Type", "application/json");
            } else {
                builder.GET();
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String responseBody = truncate(response.body());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", response.statusCode());
            data.put("url", url);
            data.put("method", method);
            data.put("content_type", response.headers().firstValue("content-type").orElse(""));
            data.put("body", responseBody);
            data.put("truncated", response.body() != null && response.body().length() > maxBodyBytes);
            return ToolOutcome.ok(responseBody, data);
        } catch (Exception e) {
            log.warn("HTTP 工具执行失败 url={} err={}", url, e.toString());
            return ToolOutcome.fail("AGENT_TOOL_EXEC_FAILED", "HTTP 请求失败：" + e.getMessage());
        }
    }

    /** 域名白名单判定（包可见：便于单测，不发真实请求） */
    boolean allowed(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        for (String allowed : allowDomains) {
            if (allowed.startsWith("*.")) {
                String suffix = allowed.substring(1);     // ".example.com"
                if (normalized.endsWith(suffix) || normalized.equals(allowed.substring(2))) return true;
            } else if (normalized.equals(allowed)) {
                return true;
            }
        }
        return false;
    }

    private String truncate(String text) {
        if (text == null) return null;
        if (text.length() <= maxBodyBytes) return text;
        return text.substring(0, maxBodyBytes) + "\n…（已截断，原始长度 " + text.length() + " 字符）";
    }

    public List<String> allowDomains() { return new ArrayList<>(allowDomains); }
}