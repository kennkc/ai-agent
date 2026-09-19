package com.agent.tool.audit;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Properties;

/**
 * 工具审计事件旁路（R5-08 的 Kafka 侧）。
 *
 * <p><b>定位</b>：审计的**真相源是 PG 表**；Kafka 主题 {@code lifeform.tool.invoked}
 * 只做事件旁路（供后续流式消费/告警用）。因此本发布器**失败即降级、绝不抛出**——
 * 总线抖动不能让一次已成功完成的工具调用在审计上"消失"。
 *
 * <p>沿用 body-service 的裸 {@code kafka-clients} 写法（不引 spring-kafka，避免多一套生命周期）。
 */
@Component
public class AuditEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(AuditEventPublisher.class);

    private final String topic;
    private final boolean enabled;
    private volatile KafkaProducer<String, String> producer;
    private volatile boolean available;

    public AuditEventPublisher(@Value("${app.tool.audit.kafka-bootstrap:127.0.0.1:9092}") String bootstrap,
                               @Value("${app.tool.audit.kafka-topic:lifeform.tool.invoked}") String topic,
                               @Value("${app.tool.audit.kafka-enabled:true}") boolean enabled) {
        this.topic = topic;
        this.enabled = enabled;
        if (!enabled) { this.available = false; return; }
        try {
            Properties properties = new Properties();
            properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            properties.put(ProducerConfig.ACKS_CONFIG, "1");
            properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "2000");
            properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "2000");
            this.producer = new KafkaProducer<>(properties);
            this.available = true;
            log.info("工具审计 Kafka 旁路已启用：topic={} bootstrap={}", topic, bootstrap);
        } catch (Exception e) {
            this.available = false;
            log.warn("Kafka 不可用，审计事件旁路关闭（不影响 PG 落库）：{}", e.getMessage());
        }
    }

    /** 异步投递，失败仅记日志 */
    public void publish(String callId, Map<String, Object> payload) {
        if (!available || producer == null) return;
        try {
            producer.send(new ProducerRecord<>(topic, callId, String.valueOf(payload)),
                    (metadata, exception) -> {
                        if (exception != null) log.debug("审计事件投递失败 callId={}: {}", callId, exception.getMessage());
                    });
        } catch (Exception e) {
            log.debug("审计事件投递异常 callId={}: {}", callId, e.getMessage());
        }
    }

    public Map<String, Object> health() {
        return Map.of("enabled", enabled, "available", available, "topic", topic,
                "role", "event-sidecar", "truth_source", "postgres.tool_audit_log");
    }

    public void close() {
        if (producer != null) {
            try { producer.close(); } catch (Exception ignored) { }
        }
    }
}