package com.agent.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 工具（R1-02）：签发/解析/校验
 * 算法 HS256；secret 从环境变量注入（JWT_SECRET），默认 dev 密钥
 */
public class JwtUtil {

    private static final String DEFAULT_SECRET = "agent-lifeform-dev-secret-key-2026-change-me-in-prod";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(
            System.getenv().getOrDefault("JWT_SECRET", DEFAULT_SECRET).getBytes(StandardCharsets.UTF_8));

    private JwtUtil() {}

    /** 签发 Token：携带 tenant_id + 过期时间 */
    public static String sign(String tenantId, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("lifeform-agent")
                .claim("tenant_id", tenantId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(KEY)
                .compact();
    }

    /** 解析 Token：无效/过期抛出 JwtException */
    public static Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(KEY)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** 快速校验：有效返回 true */
    public static boolean isValid(String token) {
        try {
            parse(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
