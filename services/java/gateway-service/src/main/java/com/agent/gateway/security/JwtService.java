package com.agent.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {
    private final SecretKey key;
    private final Duration tokenTtl;

    public JwtService(AuthProperties properties) {
        if (properties.getSecret() == null || properties.getSecret().isBlank()) {
            throw new IllegalStateException("JWT_SECRET must be configured. Refusing insecure default key.");
        }
        byte[] bytes = properties.getSecret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes for HS256.");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.tokenTtl = properties.getTokenTtl() == null ? Duration.ofHours(24) : properties.getTokenTtl();
    }

    public String sign(String tenantId) {
        Instant now = Instant.now();
        return Jwts.builder().subject("lifeform-agent").claim("tenant_id", tenantId)
                .issuedAt(Date.from(now)).expiration(Date.from(now.plus(tokenTtl)))
                .signWith(key).compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
