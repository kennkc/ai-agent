package com.agent.sense.util;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 缺陷 D-1 回归守卫：感官渠道构建的 HTTP 客户端必须是 HTTP/1.1，
 * 否则调用 nlp-service（uvicorn/h11）时会因 h2c 升级握手被丢弃请求体。
 */
class HttpClientsTest {

    @Test
    void buildsHttp11ClientWithConnectTimeout() {
        HttpClient client = HttpClients.builder(Duration.ofMillis(1200)).build();
        assertEquals(HttpClient.Version.HTTP_1_1, client.version());
        assertTrue(client.connectTimeout().isPresent());
        assertEquals(1200, client.connectTimeout().orElseThrow().toMillis());
    }
}
