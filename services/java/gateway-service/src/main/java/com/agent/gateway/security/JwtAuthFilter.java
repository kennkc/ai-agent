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

@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {
    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String TENANT_HEADER = "X-Tenant-Id";
    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) { this.jwtService = jwtService; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (path.startsWith("/actuator") || path.startsWith("/api/auth")) return chain.filter(exchange);
        String auth = exchange.getRequest().getHeaders().getFirst(AUTH_HEADER);
        if (auth == null || !auth.startsWith(BEARER_PREFIX)) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "AGENT_UNAUTHORIZED", "Missing or invalid Authorization header");
        }
        try {
            Claims claims = jwtService.parse(auth.substring(BEARER_PREFIX.length()));
            String tenantId = claims.get("tenant_id", String.class);
            if (tenantId == null || tenantId.isBlank()) {
                return reject(exchange, HttpStatus.FORBIDDEN, "AGENT_FORBIDDEN", "Token missing tenant_id");
            }
            ServerHttpRequest mutated = exchange.getRequest().mutate().header(TENANT_HEADER, tenantId).build();
            return chain.filter(exchange.mutate().request(mutated).build());
        } catch (Exception e) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "AGENT_UNAUTHORIZED", "Invalid or expired token");
        }
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"details\":{}}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override public int getOrder() { return -100; }
}
