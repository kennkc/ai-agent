package com.agent.gateway.security;

import io.jsonwebtoken.Claims;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 全局 JWT 鉴权过滤器（R1-02）
 * - 白名单放行：/actuator/**（健康检查）
 * - 校验 Authorization: Bearer <token>
 * - 有效 → 透传 X-Tenant-Id；无效 → 401 统一错误格式
 */
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String TENANT_HEADER = "X-Tenant-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        // 白名单：健康检查、Token 签发端点
        if (path.startsWith("/actuator") || path.startsWith("/api/auth")) {
            return chain.filter(exchange);
        }
        String auth = exchange.getRequest().getHeaders().getFirst(AUTH_HEADER);
        if (auth == null || !auth.startsWith(BEARER_PREFIX)) {
            return reject(exchange, "AGENT_UNAUTHORIZED", "缺少或无效的 Authorization 头");
        }
        String token = auth.substring(BEARER_PREFIX.length());
        try {
            Claims claims = JwtUtil.parse(token);
            String tenantId = claims.get("tenant_id", String.class);
            if (tenantId == null || tenantId.isBlank()) {
                return reject(exchange, "AGENT_FORBIDDEN", "Token 缺少 tenant_id");
            }
            // 透传租户上下文到下游
            ServerHttpRequest mutated = exchange.getRequest().mutate()
                    .header(TENANT_HEADER, tenantId)
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        } catch (Exception e) {
            return reject(exchange, "AGENT_UNAUTHORIZED", "Token 无效或已过期: " + e.getMessage());
        }
    }

    private Mono<Void> reject(ServerWebExchange exchange, String code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"details\":{}}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -100; // 最高优先级，最先执行
    }
}
