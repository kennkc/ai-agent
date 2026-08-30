package com.agent.session.nats;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * NATS 消息客户端（R1-04 · 脊柱总线同步通道）
 * - 请求-应答：session → sense（lifeform.rpc.*）
 * - 事件发布：lifeform.event.*（预留）
 */
@Component
public class NatsClient {

    private static final Logger log = LoggerFactory.getLogger(NatsClient.class);

    private Connection connection;

    @PostConstruct
    public void init() {
        try {
            String natsUrl = System.getenv().getOrDefault("NATS_URL", "nats://127.0.0.1:4222");
            Options options = new Options.Builder()
                    .server(natsUrl)
                    .connectionName("session-manager")
                    .maxReconnects(5)
                    .build();
            connection = Nats.connect(options);
            log.info("NATS 已连接: {}", natsUrl);
        } catch (Exception e) {
            log.warn("NATS 连接失败（服务降级为直连）: {}", e.getMessage());
        }
    }

    /** 同步请求-应答（服务间调用，R1-04） */
    public String request(String subject, String payload) {
        if (connection == null) {
            log.warn("NATS 不可用，跳过请求: {}", subject);
            return null;
        }
        try {
            io.nats.client.Message reply = connection.request(
                    subject, payload.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(3));
            return reply == null ? null : new String(reply.getData(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("NATS 请求失败 {}: {}", subject, e.getMessage());
            return null;
        }
    }

    /** 异步发布（事件通知） */
    public void publish(String subject, String payload) {
        if (connection == null) return;
        try {
            connection.publish(subject, payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("NATS 发布失败 {}: {}", subject, e.getMessage());
        }
    }

    @PreDestroy
    public void close() {
        if (connection != null) {
            try { connection.close(); } catch (Exception ignored) {}
        }
    }
}
