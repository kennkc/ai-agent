package com.agent.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {
    private String secret;
    private Duration tokenTtl = Duration.ofHours(24);
    private boolean devTokenEndpointEnabled;

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public Duration getTokenTtl() { return tokenTtl; }
    public void setTokenTtl(Duration tokenTtl) { this.tokenTtl = tokenTtl; }
    public boolean isDevTokenEndpointEnabled() { return devTokenEndpointEnabled; }
    public void setDevTokenEndpointEnabled(boolean devTokenEndpointEnabled) { this.devTokenEndpointEnabled = devTokenEndpointEnabled; }
}
