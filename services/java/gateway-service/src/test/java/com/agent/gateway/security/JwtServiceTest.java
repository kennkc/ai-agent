package com.agent.gateway.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {
    private AuthProperties props(String secret) {
        AuthProperties p = new AuthProperties();
        p.setSecret(secret);
        return p;
    }

    @Test
    void signsAndParsesTenant() {
        JwtService service = new JwtService(props("0123456789abcdef0123456789abcdef"));
        String token = service.sign("tenant-a");
        assertEquals("tenant-a", service.parse(token).get("tenant_id", String.class));
    }

    @Test
    void rejectsWeakSecret() {
        assertThrows(IllegalStateException.class, () -> new JwtService(props("short-secret")));
    }
}
