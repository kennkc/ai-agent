package com.agent.session.kafka;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class KafkaEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(KafkaEventConsumer.class);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private KafkaConsumer<String, String> consumer;
    private Thread thread;

    @PostConstruct
    public void start() {
        if (!Boolean.parseBoolean(System.getenv().getOrDefault("KAFKA_CONSUMER_ENABLED", "true"))) return;
        String bootstrap = System.getenv().getOrDefault("KAFKA_BOOTSTRAP", "127.0.0.1:9092");
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "session-manager-event-audit");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try {
            consumer = new KafkaConsumer<>(props);
            consumer.subscribe(List.of("lifeform.session.created"));
            running.set(true);
            thread = new Thread(this::pollLoop, "kafka-session-event-consumer");
            thread.setDaemon(true);
            thread.start();
        } catch (Exception e) {
            log.warn("Kafka consumer initialization failed: {}", e.getMessage());
        }
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    log.info("Kafka event consumed: topic={} key={} value={}", record.topic(), record.key(), record.value());
                }
            } catch (Exception e) {
                if (running.get()) log.warn("Kafka consumer poll failed: {}", e.getMessage());
            }
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (thread != null) thread.interrupt();
        if (consumer != null) consumer.wakeup();
    }
}
