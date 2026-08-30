package com.agent.session.kafka;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Properties;

/**
 * Kafka 事件发布器（R1-05 · 脊柱总线异步通道）
 * 主题规范：lifeform.<domain>.<event>（对齐开发设计 §3.2）
 */
@Component
public class KafkaEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private KafkaProducer<String, String> producer;

    @PostConstruct
    public void init() {
        try {
            String bootstrap = System.getenv().getOrDefault("KAFKA_BOOTSTRAP", "127.0.0.1:9092");
            Properties props = new Properties();
            props.put("bootstrap.servers", bootstrap);
            props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            props.put("acks", "all");
            producer = new KafkaProducer<>(props);
            log.info("Kafka Producer 就绪: {}", bootstrap);
        } catch (Exception e) {
            log.warn("Kafka 初始化失败（事件发布降级跳过）: {}", e.getMessage());
        }
    }

    /** 发布事件：lifeform.<domain>.<event> */
    public void publish(String domain, String event, String key, Map<String, Object> payload) {
        if (producer == null) return;
        String topic = "lifeform." + domain + "." + event;
        try {
            String json = payload == null ? "{}" : payload.toString().replace("=", ":").replace(", ", ",");
            // 用简单 JSON 序列化（字段级事件体，避免引额外库）
            StringBuilder sb = new StringBuilder("{");
            int i = 0;
            for (Map.Entry<String, Object> e : payload.entrySet()) {
                if (i++ > 0) sb.append(",");
                sb.append("\"").append(e.getKey()).append("\":\"").append(e.getValue()).append("\"");
            }
            sb.append("}");
            producer.send(new ProducerRecord<>(topic, key, sb.toString()),
                    (meta, ex) -> {
                        if (ex != null) log.warn("Kafka 发送失败 {}: {}", topic, ex.getMessage());
                    });
            log.info("Kafka 事件已发布: {} key={}", topic, key);
        } catch (Exception e) {
            log.warn("Kafka 发布异常: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void close() {
        if (producer != null) {
            try { producer.close(); } catch (Exception ignored) {}
        }
    }
}
