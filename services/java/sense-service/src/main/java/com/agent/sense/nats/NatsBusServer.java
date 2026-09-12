package com.agent.sense.nats;

import com.agent.sense.dynamic.R1DynamicHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * NATS 总线应答服务（R1-04 · 脊柱神经信号接收端 / R2-06 R1 动态采集指令通道）
 * 基于自研 MinimalNatsClient（直接实现 NATS 协议）
 */
@Component
public class NatsBusServer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(NatsBusServer.class);
    /** 具体主题订阅（R1-04）：Docker NATS 环境下通配订阅 * 投递失效，用具体主题绕过 */
    private static final String RPC_PING_SUBJECT = "lifeform.rpc.sense.ping";
    /** R2-06 R1 动态采集指令主题 */
    private static final String RPC_COLLECT_SUBJECT = "lifeform.rpc.sense.collect";

    private final R1DynamicHandler dynamicHandler;
    private MinimalNatsClient client;

    public NatsBusServer(R1DynamicHandler dynamicHandler) { this.dynamicHandler = dynamicHandler; }

    @Override
    public void run(ApplicationArguments args) {
        try {
            String natsUrl = System.getenv().getOrDefault("NATS_URL", "nats://127.0.0.1:4222");
            String host = natsUrl.replace("nats://", "").split(":")[0];
            int port = Integer.parseInt(natsUrl.replace("nats://", "").split(":")[1]);
            client = new MinimalNatsClient(host, port, "sense-service", this::handleRequest);
            client.connect();
            client.subscribe(RPC_PING_SUBJECT);
            client.subscribe(RPC_COLLECT_SUBJECT);
            log.info("NATS 总线应答服务就绪，订阅: {} / {}", RPC_PING_SUBJECT, RPC_COLLECT_SUBJECT);
        } catch (Exception e) {
            log.warn("NATS 订阅失败（总线降级）: {}", e.getMessage());
        }
    }

    /** 总线请求处理：ping → pong（健康信号）/ collect → R1 动态采集 */
    private void handleRequest(String subject, String replyTo, String payload) {
        String reply;
        if (subject.endsWith(".sense.ping")) {
            reply = "{\"service\":\"sense-service\",\"status\":\"pong\",\"channel\":\"TOUCH\"}";
        } else if (subject.endsWith(".sense.collect")) {
            reply = dynamicHandler.dispatchJson(payload == null || payload.isBlank() ? "{}" : payload);
        } else {
            reply = "{\"service\":\"sense-service\",\"status\":\"unknown_subject\"}";
        }
        if (replyTo != null && !replyTo.isEmpty()) {
            try {
                client.publish(replyTo, reply);
                log.info("[总线] 已应答 {}: {}", subject, abbreviate(reply));
            } catch (Exception e) {
                log.warn("[总线] 应答失败: {}", e.getMessage());
            }
        }
    }

    private static String abbreviate(String value) {
        return value != null && value.length() > 240 ? value.substring(0, 240) + "..." : value;
    }
}
