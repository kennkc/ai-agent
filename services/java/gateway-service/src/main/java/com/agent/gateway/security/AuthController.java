package com.agent.gateway.security;

import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

/**
 * Token 签发端点（R1-02 · dev 用途）
 * POST /api/auth/token?tenant_id=default → 返回有效 JWT（24h）
 * 生产环境替换为 OAuth2（P7 演进，见 D5-3）
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @PostMapping("/token")
    public Map<String, String> issueToken(@RequestParam(defaultValue = "default") String tenantId) {
        String token = JwtUtil.sign(tenantId, Duration.ofHours(24));
        return Map.of(
                "token", token,
                "tenant_id", tenantId,
                "expires_in", "86400",
                "token_type", "Bearer"
        );
    }
}
