package com.agent.collab.nats;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamManagement;
import io.nats.client.Nats;
import io.nats.client.Options;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NATS / JetStream 连接管理（MC-01 的传输底座）。
 *
 * <p>设计约定：
 * <ul>
 *   <li><b>连接惰性建立 + 断线可恢复</b>：不因 NATS 暂时不可用而让服务启动失败</li>
 *   <li><b>状态如实上报</b>：{@link #status()} 暴露 connected / jetstreamAvailable / degraded，
 *       能力不可用时必须可见（沿用 2026-09-16 的降级可见化约定）</li>
 * </ul>
 */
@Component
public class NatsConnection {
    private static final Logger log = LoggerFactory.getLogger(NatsConnection.class);

    private final String natsUrl;
    private final int reconnectWaitMs;
    private volatile Connection connection;
    private volatile String lastError = "";

    public NatsConnection(@Value("${app.collab.nats-url:nats://127.0.0.1:4222}") String natsUrl,
                          @Value("${app.collab.nats-reconnect-wait-ms:2000}") int reconnectWaitMs) {
        this.natsUrl = natsUrl;
        this.reconnectWaitMs = reconnectWaitMs;
        connectQuietly();
    }

    private synchronized void connectQuietly() {
        if (connection != null && connection.getStatus() == Connection.Status.CONNECTED) {
            return;
        }
        try {
            Options options = new Options.Builder()
                    .server(natsUrl)
                    .connectionTimeout(Duration.ofSeconds(3))
                    .maxReconnects(-1)
                    .reconnectWait(Duration.ofMillis(reconnectWaitMs))
                    .build();
            connection = Nats.connect(options);
            lastError = "";
            log.info("NATS 已连接：{}", natsUrl);
        } catch (Exception e) {
            connection = null;
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("NATS 不可用（将按需重试）：{}", lastError);
        }
    }

    /** 当前连接（可能触发一次惰性重连）。 */
    public Connection connection() {
        if (connection == null || connection.getStatus() != Connection.Status.CONNECTED) {
            connectQuietly();
        }
        return connection;
    }

    public boolean connected() {
        Connection c = connection();
        return c != null && c.getStatus() == Connection.Status.CONNECTED;
    }

    /** JetStream 上下文（未连接时抛 IllegalStateException，由调用方转 503）。 */
    public JetStream jetStream() {
        Connection c = connection();
        if (c == null) {
            throw new IllegalStateException("NATS 未连接：" + lastError);
        }
        try {
            return c.jetStream();
        } catch (IOException e) {
            throw new IllegalStateException("获取 JetStream 上下文失败：" + e.getMessage(), e);
        }
    }

    public JetStreamManagement jetStreamManagement() {
        Connection c = connection();
        if (c == null) {
            throw new IllegalStateException("NATS 未连接：" + lastError);
        }
        try {
            return c.jetStreamManagement();
        } catch (IOException e) {
            throw new IllegalStateException("获取 JetStream 管理上下文失败：" + e.getMessage(), e);
        }
    }

    /** JetStream 是否可用（尝试取上下文，不抛异常）。 */
    public boolean jetStreamAvailable() {
        try {
            return jetStream() != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 健康/降级状态（供 /api/collab/health 与前端“沙箱/总线状态灯”使用）。 */
    public Map<String, Object> status() {
        boolean up = connected();
        boolean js = up && jetStreamAvailable();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nats_url", natsUrl);
        row.put("nats_connected", up);
        row.put("jetstream_available", js);
        row.put("degraded", !js);
        row.put("last_error", lastError);
        row.put("note", js
                ? "JetStream 可用（持久化投递前提，存储目录由 NATS 容器 --store_dir 指定）"
                : "NATS/JetStream 不可用 —— 可靠投递与心跳聚合将不可用（降级可见，不静默）");
        return row;
    }

    @PreDestroy
    public void close() {
        Connection c = connection;
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // 关闭失败无需处理
            }
        }
    }
}
