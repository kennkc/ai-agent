package com.agent.gateway.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(prefix = "app.auth", name = "dev-token-endpoint-enabled", havingValue = "true")
public class AuthController {
    private final JwtService jwtService;

    public AuthController(JwtService jwtService) { this.jwtService = jwtService; }

    @PostMapping("/token")
    public Map<String, String> issueToken(@RequestParam(defaultValue = "default") String tenantId,
                                          ServerWebExchange exchange) {
        try {
            InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
            if (remote == null || !InetAddress.getByName(remote.getHostString()).isLoopbackAddress()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "dev token endpoint is loopback-only");
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "dev token endpoint is loopback-only");
        }
        return Map.of("token", jwtService.sign(tenantId), "tenant_id", tenantId, "token_type", "Bearer");
    }
}
