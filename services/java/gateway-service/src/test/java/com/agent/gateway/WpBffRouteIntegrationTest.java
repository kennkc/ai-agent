package com.agent.gateway;

import com.agent.gateway.security.JwtService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.auth.secret=0123456789abcdef0123456789abcdef",
                "app.auth.dev-token-endpoint-enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "spring.cloud.gateway.discovery.locator.enabled=false"
        })
@AutoConfigureWebTestClient
class WpBffRouteIntegrationTest {

    private static HttpServer bff;
    private static int bffPort;
    private static final AtomicReference<String> LAST_TENANT = new AtomicReference<>("");

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtService jwtService;

    @BeforeAll
    static void startFakeBff() throws IOException {
        bff = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bffPort = bff.getAddress().getPort();
        bff.createContext("/api/wp/healthz", WpBffRouteIntegrationTest::health);
        bff.start();
    }

    @AfterAll
    static void stopFakeBff() {
        if (bff != null) bff.stop(0);
    }

    @DynamicPropertySource
    static void routeProperties(DynamicPropertyRegistry registry) {
        registry.add("WP_BFF_URI", () -> "http://127.0.0.1:" + bffPort);
    }

    @Test
    void exposesPrometheusMetricsEndpoint() {
        webTestClient.get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("jvm_memory_used_bytes"));
    }

    @Test
    void routesApiWpThroughGatewayAndInjectsTenant() {
        String token = jwtService.sign("tenant-route");
        webTestClient.get().uri("/api/wp/healthz")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.status").isEqualTo("up")
                .jsonPath("$.data.service").isEqualTo("wp-bff");

        assertThat(LAST_TENANT.get()).isEqualTo("tenant-route");
    }

    private static void health(HttpExchange exchange) throws IOException {
        LAST_TENANT.set(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
        byte[] body = "{\"data\":{\"status\":\"up\",\"service\":\"wp-bff\"}}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}