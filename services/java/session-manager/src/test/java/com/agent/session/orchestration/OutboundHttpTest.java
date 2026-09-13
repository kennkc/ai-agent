package com.agent.session.orchestration;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 缺陷 D-1 回归守卫：跨服务调用必须走明文 HTTP/1.1，不得再发送 h2c 升级握手。
 *
 * <p>JDK HttpClient 默认 HTTP/2，对明文连接会先发 {@code Upgrade: h2c} + {@code HTTP2-Settings}；
 * uvicorn(h11) 不兼容该升级会丢弃请求体（FastAPI 报 422）。这里用裸 ServerSocket 抓取原始请求报文，
 * 直接断言「请求行是 HTTP/1.1」且「没有升级头」，避免缺陷再次被端口级健康检查掩盖。
 */
class OutboundHttpTest {

    @Test
    void clientIsLockedToHttp11() {
        assertEquals(HttpClient.Version.HTTP_1_1, OutboundHttp.client().version());
    }

    @Test
    void restClientSendsPlainHttp11RequestWithIntactBody() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<String> rawRequest = new CompletableFuture<>();
            Thread serverThread = new Thread(() -> serveOnce(server, rawRequest), "raw-http-probe");
            serverThread.setDaemon(true);
            serverThread.start();

            String baseUrl = "http://127.0.0.1:" + server.getLocalPort();
            String response = OutboundHttp.restClient(baseUrl)
                    .post().uri("/api/nlp/intent")
                    .header("Content-Type", "application/json")
                    .body("{\"text\":\"probe\"}")
                    .retrieve().body(String.class);

            String raw = rawRequest.get(10, TimeUnit.SECONDS);
            String lower = raw.toLowerCase();
            assertTrue(raw.startsWith("POST /api/nlp/intent HTTP/1.1"), raw);
            assertFalse(lower.contains("h2c"), raw);
            assertFalse(lower.contains("http2-settings"), raw);
            assertTrue(raw.contains("{\"text\":\"probe\"}"), raw);
            assertEquals("{\"ok\":true}", response);
        }
    }

    private static void serveOnce(ServerSocket server, CompletableFuture<String> rawRequest) {
        try (Socket socket = server.accept()) {
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int contentLength = -1;
            byte[] chunk = new byte[256];
            while (true) {
                int read = in.read(chunk);
                if (read < 0) {
                    break;
                }
                buffer.write(chunk, 0, read);
                String soFar = buffer.toString(StandardCharsets.UTF_8);
                int headerEnd = soFar.indexOf("\r\n\r\n");
                if (contentLength < 0 && headerEnd >= 0) {
                    contentLength = headerValue(soFar, "content-length");
                }
                if (headerEnd >= 0 && (contentLength <= 0 || soFar.length() - (headerEnd + 4) >= contentLength)) {
                    break;
                }
            }
            rawRequest.complete(buffer.toString(StandardCharsets.UTF_8));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                    + body.length + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(head.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();
        } catch (Exception e) {
            rawRequest.completeExceptionally(e);
        }
    }

    private static int headerValue(String raw, String name) {
        for (String line : raw.split("\r\n")) {
            int idx = line.indexOf(':');
            if (idx > 0 && line.substring(0, idx).trim().equalsIgnoreCase(name)) {
                try {
                    return Integer.parseInt(line.substring(idx + 1).trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }
}
