package com.agent.sense.event;

import com.agent.sense.channel.SenseChannel;
import com.agent.sense.model.CollectedData;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 采集事件发布（Phase 2 → Phase 3 入库消费）
 * 主题约定：lifeform.sense.collected（与 Phase 1 的 lifeform.{domain}.{event} 一致）
 */
@Slf4j
@Component
public class SenseEventPublisher {

    public static final String TOPIC = "lifeform.sense.collected";

    private final ObjectMapper objectMapper;
    private final AtomicLong published = new AtomicLong();
    private KafkaProducer<String, String> producer;

    public SenseEventPublisher(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    @PostConstruct
    public void init() {
        try {
            String bootstrap = System.getenv().getOrDefault("KAFKA_BOOTSTRAP", "127.0.0.1:9092");
            Properties props = new Properties();
            props.put("bootstrap.servers", bootstrap);
            props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            props.put("acks", "all");
            props.put("max.block.ms", "3000");
            producer = new KafkaProducer<>(props);
            log.info("Sense Kafka producer ready: {}", bootstrap);
        } catch (Exception e) {
            log.warn("Kafka init failed; sense events skipped: {}", e.getMessage());
        }
    }

    /** 发布采集完成事件；仅 ACCEPTED 数据会触发下游入库（Phase 3） */
    public boolean publishCollected(CollectedData data) {
        if (producer == null) return false;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batch_id", data.getBatchId());
        payload.put("tenant_id", data.getTenantId());
        payload.put("source_channel", data.getSourceChannel());
        payload.put("timestamp", data.getTimestamp());
        payload.put("freshness", data.getFreshness() == null ? null : data.getFreshness().name());
        payload.put("confidence", data.getConfidence());
        payload.put("item_count", data.getItemCount());
        payload.put("quality_score", data.getQualityScore());
        payload.put("staging_status", data.getStagingStatus() == null ? null : data.getStagingStatus().name());
        payload.put("staging_ref", data.getStagingRef());
        payload.put("mode", data.getMode());
        // Phase 3 躯体期闭环：事件携带正文与标题，body-service 据此直接分块入库。
        // DEBT-012 触发点：正文超过 Kafka 单消息上限（默认 1MB）时，应改为按 staging_ref 回读暂存对象。
        payload.put("title", data.getTitle());
        payload.put("content", data.getContent());
        try {
            String json = objectMapper.writeValueAsString(payload);
            producer.send(new ProducerRecord<>(TOPIC, data.getBatchId(), json), (meta, ex) -> {
                if (ex != null) log.warn("sense event send failed: {}", ex.getMessage());
                else published.incrementAndGet();
            });
            return true;
        } catch (Exception e) {
            log.warn("sense event publish failed: {}", e.getMessage());
            return false;
        }
    }

    public long publishedCount() { return published.get(); }

    public boolean available() { return producer != null; }

    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("topic", TOPIC);
        stats.put("available", available());
        stats.put("published", published.get());
        return stats;
    }

    @PreDestroy
    public void close() {
        if (producer != null) try { producer.close(); } catch (Exception ignored) { }
    }
}
