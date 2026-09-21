package com.agent.collab.delivery;

import com.agent.collab.domain.CollabDomain;
import com.agent.collab.domain.DomainRepository;
import com.agent.collab.domain.DomainService;
import com.agent.collab.heartbeat.HeartbeatService;
import com.agent.collab.nats.NatsConnection;
import com.agent.collab.observability.CollabBusMetrics;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JetStream durable pull consumer（R-MC01-02）。
 *
 * <p>每个域一个 durable pull consumer + 虚拟线程拉取循环。相比 push consumer，
 * durable pull 在进程重启后可直接重新绑定，不会因 deliver subject / ackWait
 * 配置漂移而无法恢复未 ack 消息。
 */
@Component
public class ReliableConsumer {
    private static final Logger log = LoggerFactory.getLogger(ReliableConsumer.class);

    private final NatsConnection nats;
    private final DomainRepository domains;
    private final IdempotentConsumer consumer;
    private final HeartbeatService heartbeatService;
    private final ObjectMapper objectMapper;
    private final CollabBusMetrics metrics;
    private final String durablePrefix;
    private final long retryDelayMs;
    private final long ackWaitMs;
    private final int batchSize;
    private final long pollTimeoutMs;

    private final Map<String, ConsumerLoop> handles = new ConcurrentHashMap<>();

    public ReliableConsumer(NatsConnection nats,
                            DomainRepository domains,
                            IdempotentConsumer consumer,
                            HeartbeatService heartbeatService,
                            ObjectMapper objectMapper,
                            CollabBusMetrics metrics,
                            @Value("${app.collab.consumer-durable-prefix:collab-worker}") String durablePrefix,
                            @Value("${app.collab.retry-delay-ms:500}") long retryDelayMs,
                            @Value("${app.collab.ack-wait-ms:30000}") long ackWaitMs,
                            @Value("${app.collab.consumer-batch-size:32}") int batchSize,
                            @Value("${app.collab.consumer-poll-timeout-ms:500}") long pollTimeoutMs) {
        this.nats = nats;
        this.domains = domains;
        this.consumer = consumer;
        this.heartbeatService = heartbeatService;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.durablePrefix = durablePrefix;
        this.retryDelayMs = Math.max(100, retryDelayMs);
        this.ackWaitMs = Math.max(1000, ackWaitMs);
        this.batchSize = Math.max(1, batchSize);
        this.pollTimeoutMs = Math.max(100, pollTimeoutMs);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startAll() {
        if (!domains.available()) {
            log.warn("PG 不可用，跳过协作域 consumer 恢复");
            return;
        }
        for (CollabDomain domain : domains.listAll()) {
            if (domain.closed() || CollabDomain.STATE_FAILED.equals(domain.state())) {
                continue;
            }
            try {
                startDomain(domain.domainId());
            } catch (Exception e) {
                log.warn("协作域 consumer 启动失败：domain={} state={} err={}",
                        domain.domainId(), domain.state(), e.getMessage());
            }
        }
    }

    /** 创建或恢复 durable pull consumer；重复调用幂等。 */
    public synchronized void startDomain(String domainId) throws Exception {
        if (handles.containsKey(domainId)) {
            return;
        }
        String stream = DomainService.streamName(domainId);
        String filter = "collab." + domainId + ".*.*";
        String durable = durablePrefix + "-" + domainId;
        ConsumerConfiguration configuration = ConsumerConfiguration.builder()
                .ackPolicy(AckPolicy.Explicit)
                .deliverPolicy(DeliverPolicy.All)
                .ackWait(Duration.ofMillis(ackWaitMs))
                .maxDeliver(consumer.retryMax() + 1L)
                .backoff(Duration.ofMillis(250), Duration.ofMillis(1000), Duration.ofSeconds(2))
                .filterSubject(filter)
                .build();

        PullSubscribeOptions options;
        try {
            // 绑定既有 consumer 时使用服务端快照配置，避免客户端默认值造成“不可修改”误判。
            var existing = nats.jetStreamManagement().getConsumerInfo(stream, durable);
            String deliverSubject = existing.getConsumerConfiguration().getDeliverSubject();
            if (deliverSubject != null && !deliverSubject.isBlank()) {
                if (existing.getNumPending() > 0 || existing.getNumAckPending() > 0) {
                    throw new IllegalStateException("旧 push consumer 仍有未处理消息，拒绝自动迁移："
                            + "pending=" + existing.getNumPending() + ", ack_pending=" + existing.getNumAckPending());
                }
                nats.jetStreamManagement().deleteConsumer(stream, durable);
                log.warn("检测到旧 push consumer，已无未处理消息，自动迁移为 pull：domain={} durable={}",
                        domainId, durable);
                options = PullSubscribeOptions.builder()
                        .stream(stream)
                        .durable(durable)
                        .configuration(configuration)
                        .build();
            } else {
                options = PullSubscribeOptions.builder()
                        .stream(stream)
                        .durable(durable)
                        .configuration(existing.getConsumerConfiguration())
                        .bind(true)
                        .build();
            }
        } catch (JetStreamApiException notFound) {
            if (notFound.getErrorCode() != 404) {
                throw notFound;
            }
            options = PullSubscribeOptions.builder()
                    .stream(stream)
                    .durable(durable)
                    .configuration(configuration)
                    .build();
        }

        JetStreamSubscription subscription = nats.jetStream().subscribe(filter, options);
        AtomicBoolean running = new AtomicBoolean(true);
        Thread thread = Thread.ofVirtual()
                .name("collab-consumer-" + domainId)
                .start(() -> pullLoop(domainId, subscription, running));
        handles.put(domainId, new ConsumerLoop(subscription, running, thread));
        metrics.activeDomainsChanged(handles.size());
        log.info("协作域 pull consumer 已启动：domain={} stream={} durable={}", domainId, stream, durable);
    }

    /** 关闭域时停止拉取；durable consumer 保留未 ack 进度，供恢复继续消费。 */
    public synchronized void stopDomain(String domainId) {
        ConsumerLoop handle = handles.remove(domainId);
        metrics.activeDomainsChanged(handles.size());
        if (handle == null) {
            return;
        }
        handle.running().set(false);
        try {
            handle.subscription().unsubscribe();
        } catch (Exception e) {
            log.warn("取消协作域 pull 订阅失败：domain={} err={}", domainId, e.getMessage());
        }
        handle.thread().interrupt();
        heartbeatService.forgetDomain(domainId);
        log.info("协作域 pull consumer 已停止：domain={}", domainId);
    }

    public Map<String, Object> status() {
        long active = handles.values().stream().filter(handle -> handle.running().get()).count();
        return Map.of("active_domains", active, "domain_ids",
                handles.keySet().stream().sorted().toList());
    }

    private void pullLoop(String domainId, JetStreamSubscription subscription, AtomicBoolean running) {
        while (running.get()) {
            try {
                List<Message> messages = subscription.fetch(batchSize, Duration.ofMillis(pollTimeoutMs));
                for (Message message : messages) {
                    if (!running.get()) {
                        break;
                    }
                    handle(domainId, message);
                }
            } catch (Exception e) {
                if (!running.get()) {
                    break;
                }
                log.warn("协作域 pull 循环异常：domain={} err={}", domainId, e.getMessage());
                try {
                    Thread.sleep(Math.max(100, retryDelayMs));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void handle(String domainId, Message message) {
        String requestId = header(message, "X-Request-Id", fallbackRequestId(message));
        String memberId = header(message, "X-Member-Id", "-");
        String type = header(message, "X-Message-Type", "");
        String tenantId = header(message, "X-Tenant-Id", "default");
        String payload = new String(message.getData(), StandardCharsets.UTF_8);
        try {
            IdempotentConsumer.DeliveryResult result = consumer.deliver(
                    tenantId, domainId, requestId, memberId, payload,
                    body -> dispatch(type, tenantId, domainId, memberId, body));
            metrics.messageConsumed(result.state().name());
            if (result.state() == IdempotentConsumer.DeliveryState.PROCESSED
                    || result.state() == IdempotentConsumer.DeliveryState.DUPLICATE
                    || result.state() == IdempotentConsumer.DeliveryState.DEAD_LETTER) {
                message.ack();
            }
        } catch (Exception e) {
            log.warn("消息未完成，延迟重投：domain={} request_id={} retry-delay={}ms err={}",
                    domainId, requestId, retryDelayMs, e.getMessage());
            metrics.messageRetry();
            message.nakWithDelay(Duration.ofMillis(retryDelayMs));
        }
    }

    private void dispatch(String type, String tenantId, String domainId, String memberId, String payload)
            throws Exception {
        if ("heartbeat".equals(type)) {
            HeartbeatPayload heartbeat = objectMapper.readValue(payload, HeartbeatPayload.class);
            heartbeatService.report(tenantId, domainId, memberId,
                    heartbeat.progress(), heartbeat.state(), heartbeat.weight());
            return;
        }
        // Phase 6 会在这里注册 dispatch / result / negotiate 的真实业务处理器。
        log.debug("消息传输层已消费（业务处理器预留）：domain={} type={} member={}",
                domainId, type, memberId);
    }

    private static String header(Message message, String name, String fallback) {
        if (message.getHeaders() == null) {
            return fallback;
        }
        String value = message.getHeaders().getFirst(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String fallbackRequestId(Message message) {
        try {
            return "msg-" + message.metaData().getStream() + "-" + message.metaData().streamSequence();
        } catch (Exception ignored) {
            return "msg-" + System.nanoTime();
        }
    }

    @PreDestroy
    public synchronized void stopAll() {
        for (String domainId : handles.keySet().toArray(String[]::new)) {
            stopDomain(domainId);
        }
    }

    private record ConsumerLoop(JetStreamSubscription subscription, AtomicBoolean running, Thread thread) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record HeartbeatPayload(Double progress, String state, Double weight) {
    }
}