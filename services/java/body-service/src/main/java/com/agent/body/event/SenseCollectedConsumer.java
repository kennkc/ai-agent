package com.agent.body.event;

import com.agent.body.service.IngestService;
import com.agent.body.service.KnowledgeMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 感官采集事件消费（R3-07 闭环：Phase 2 采集 → Phase 3 入库）
 *
 * <p>主题：{@code lifeform.sense.collected}（Phase 2 侧 {@code SenseEventPublisher} 发布）。
 * 事件字段：batch_id / tenant_id / source_channel / content / title / staging_ref / quality_score ...
 * 只有 ACCEPTED 且带正文的事件才入库（Phase 2 质检已在上游完成，此处不重复质检）。
 *
 * <p>语义设计：**入库失败不阻塞消费**——异常按文档粒度记录（FAILED + 原因），
 * 消费位点照常提交，避免一条坏数据把整条采集链路卡死（与 Phase 2 死信思路一致）。
 */
@Component
public class SenseCollectedConsumer {

    private static final Logger log = LoggerFactory.getLogger(SenseCollectedConsumer.class);

    public static final String TOPIC = "lifeform.sense.collected";

    private final IngestService ingestService;
    private final KnowledgeMetrics metrics;
    private final ObjectMapper objectMapper;
    private final String bootstrap;
    private final boolean enabled;

    private final AtomicLong consumed = new AtomicLong();
    private final AtomicLong ingested = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    private volatile KafkaConsumer<String, String> consumer;
    private volatile Thread worker;
    private volatile boolean running;

    public SenseCollectedConsumer(IngestService ingestService, KnowledgeMetrics metrics, ObjectMapper objectMapper,
                                  @Value("${app.body.kafka.bootstrap:127.0.0.1:9092}") String bootstrap,
                                  @Value("${app.body.kafka.consume-enabled:true}") boolean enabled) {
        this.ingestService = ingestService;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.bootstrap = bootstrap;
        this.enabled = enabled;
    }

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("感官事件消费已关闭（app.body.kafka.consume-enabled=false）");
            return;
        }
        try {
            Properties props = new Properties();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            props.put(ConsumerConfig.GROUP_ID_CONFIG, "body-service-knowledge");
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
            props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "20");
            KafkaConsumer<String, String> created = new KafkaConsumer<>(props);
            created.subscribe(List.of(TOPIC));
            this.consumer = created;
            this.running = true;
            this.worker = new Thread(this::loop, "sense-collected-consumer");
            this.worker.setDaemon(true);
            this.worker.start();
            log.info("感官事件消费已启动：topic={} bootstrap={}", TOPIC, bootstrap);
        } catch (Exception e) {
            log.warn("感官事件消费启动失败（入库链路仍可通过 HTTP API 使用）：{}", e.getMessage());
        }
    }

    private void loop() {
        while (running) {
            try {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(800));
                for (ConsumerRecord<String, String> record : records) {
                    handle(record.value());
                }
            } catch (org.apache.kafka.common.errors.WakeupException e) {
                break;
            } catch (Exception e) {
                log.warn("消费循环异常（继续）：{}", e.getMessage());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    void handle(String payload) {
        if (payload == null || payload.isBlank()) return;
        consumed.incrementAndGet();
        try {
            Map<String, Object> event = objectMapper.readValue(payload, LinkedHashMap.class);
            String tenantId = String.valueOf(event.getOrDefault("tenant_id", "default"));
            String batchId = String.valueOf(event.getOrDefault("batch_id", "batch-" + System.nanoTime()));
            String channel = String.valueOf(event.getOrDefault("source_channel", "sense"));
            String content = event.get("content") == null ? "" : String.valueOf(event.get("content"));
            String title = event.get("title") == null ? batchId : String.valueOf(event.get("title"));
            String stagingRef = event.get("staging_ref") == null ? null : String.valueOf(event.get("staging_ref"));
            String stagingStatus = String.valueOf(event.getOrDefault("staging_status", ""));

            if (!stagingStatus.isEmpty() && !"ACCEPTED".equalsIgnoreCase(stagingStatus)) {
                log.debug("跳过非 ACCEPTED 事件：batch={} staging_status={}", batchId, stagingStatus);
                return;
            }
            if (content.isBlank()) {
                // 事件只带暂存引用时无法直接入库，如实记录（不在本阶段实现 MinIO 回读）
                log.warn("事件缺少正文，跳过入库：batch={} staging_ref={}", batchId, stagingRef);
                failed.incrementAndGet();
                return;
            }
            var outcome = ingestService.ingest(tenantId, batchId, title, content, channel);
            metrics.recordIngest(outcome.chunkCount(), true);
            ingested.incrementAndGet();
            log.info("采集数据入库完成 batch={} tenant={} chunks={}", batchId, tenantId, outcome.chunkCount());
        } catch (Exception e) {
            failed.incrementAndGet();
            log.warn("采集数据入库失败（不阻塞后续消费）：{}", e.getMessage());
        }
    }

    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("topic", TOPIC);
        stats.put("bootstrap", bootstrap);
        stats.put("enabled", enabled);
        stats.put("running", running);
        stats.put("consumed", consumed.get());
        stats.put("ingested", ingested.get());
        stats.put("failed", failed.get());
        return stats;
    }

    @PreDestroy
    public void stop() {
        running = false;
        KafkaConsumer<String, String> current = consumer;
        if (current != null) {
            try {
                current.wakeup();
            } catch (Exception ignored) {
                // 忽略关闭期异常
            }
            try {
                current.close(Duration.ofSeconds(3));
            } catch (Exception ignored) {
                // 忽略关闭期异常
            }
        }
        log.info("感官事件消费已停止：consumed={} ingested={} failed={}", consumed.get(), ingested.get(), failed.get());
    }
}
