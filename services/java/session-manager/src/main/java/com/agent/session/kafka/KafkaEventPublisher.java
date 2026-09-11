package com.agent.session.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Properties;

@Component
public class KafkaEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);
    private final ObjectMapper objectMapper;
    private KafkaProducer<String, String> producer;

    public KafkaEventPublisher(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

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
            log.info("Kafka Producer ready: {}", bootstrap);
        } catch (Exception e) {
            log.warn("Kafka initialization failed; events skipped: {}", e.getMessage());
        }
    }

    public void publish(String domain, String event, String key, Map<String, Object> payload) {
        if (producer == null) return;
        String topic = "lifeform." + domain + "." + event;
        try {
            String json = objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
            producer.send(new ProducerRecord<>(topic, key, json), (meta, ex) -> {
                if (ex != null) log.warn("Kafka send failed {}: {}", topic, ex.getMessage());
                else log.info("Kafka event published: {} offset={}", topic, meta.offset());
            });
        } catch (Exception e) {
            log.warn("Kafka publish failed {}: {}", topic, e.getMessage());
        }
    }

    @PreDestroy
    public void close() { if (producer != null) try { producer.close(); } catch (Exception ignored) {} }
}
