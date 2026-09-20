package com.agent.collab.delivery;

import com.agent.collab.common.BizException;
import com.agent.collab.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 可靠消费助手（R-MC01-02）。
 *
 * <p>消费流程：
 * <pre>
 *   claim(domain_id, request_id)
 *     ├─ processed / dlq → ack，不执行副作用
 *     ├─ claimed / retry → 执行业务
 *     │    ├─ 成功 → processed → ack
 *     │    └─ 失败 → failed → 由消费端 nak 重投；达到上限则 DLQ + ack
 *     └─ exhausted → 发布 DLQ + ack
 * </pre>
 *
 * <p>业务失败不再永久保留“已见”标记；状态机允许有限重投，最终由
 * {@link #deliver} 自动进入死信。这样设计中的“重投 ≤ N → 死信”才真正成立。
 */
@Service
public class IdempotentConsumer {
    private static final Logger log = LoggerFactory.getLogger(IdempotentConsumer.class);

    private final IdempotencyRepository repository;
    private final MessagePublisher publisher;
    private final int retryMax;

    public IdempotentConsumer(IdempotencyRepository repository,
                              MessagePublisher publisher,
                              @Value("${app.collab.retry-max:3}") int retryMax) {
        this.repository = repository;
        this.publisher = publisher;
        this.retryMax = Math.max(0, retryMax);
    }

    /** 业务处理函数。 */
    @FunctionalInterface
    public interface Handler {
        void handle(String payload) throws Exception;
    }

    public enum DeliveryState {
        PROCESSED,
        DUPLICATE,
        DEAD_LETTER
    }

    public record DeliveryResult(DeliveryState state, int attempts, String requestId) {
    }

    /**
     * 处理一条消息并返回最终状态。
     *
     * <p>失败且未达上限时抛出异常，由真实消费端调用 {@code nakWithDelay}；
     * 达到上限时返回 {@code DEAD_LETTER}，由消费端 ack 原消息。
     */
    public DeliveryResult deliver(String tenantId, String domainId, String requestId, String memberId,
                                  String payload, Handler handler) {
        requirePg();
        int maxAttempts = retryMax + 1;
        IdempotencyRepository.ClaimResult claim =
                repository.claim(domainId, requestId, memberId, maxAttempts);

        if (claim.duplicate()) {
            log.debug("重复消息已 ack：domain={} request_id={} state={}",
                    domainId, requestId, claim.state());
            return new DeliveryResult(DeliveryState.DUPLICATE, claim.attempts(), requestId);
        }
        if (claim.state() == IdempotencyRepository.ClaimState.EXHAUSTED) {
            return sendToDeadLetter(tenantId, domainId, requestId, payload,
                    "重投次数已超过上限 attempts=" + claim.attempts(), claim.attempts());
        }

        try {
            handler.handle(payload);
            repository.markProcessed(domainId, requestId);
            return new DeliveryResult(DeliveryState.PROCESSED, claim.attempts(), requestId);
        } catch (Exception e) {
            repository.markFailed(domainId, requestId, e.getMessage());
            log.warn("消息处理失败（可重投）：domain={} request_id={} attempt={} err={}",
                    domainId, requestId, claim.attempts(), e.getMessage());
            if (claim.attempts() >= maxAttempts) {
                return sendToDeadLetter(tenantId, domainId, requestId, payload,
                        "处理器最终失败：" + e.getMessage(), claim.attempts());
            }
            throw new BizException(ErrorCode.AGENT_INTERNAL, "消息处理失败：" + e.getMessage(),
                    Map.of("domain_id", domainId, "request_id", requestId,
                            "attempt", claim.attempts(), "max_attempts", maxAttempts));
        }
    }

    /** 兼容旧调用：只返回是否真正完成一次业务处理。 */
    public boolean consume(String tenantId, String domainId, String requestId, String memberId,
                           String payload, Handler handler) {
        return deliver(tenantId, domainId, requestId, memberId, payload, handler)
                .state() == DeliveryState.PROCESSED;
    }

    /** 重投超限或人工收口：把消息送入死信并标记状态。 */
    public Map<String, Object> deadLetter(String tenantId, String domainId, String requestId,
                                          String payload, String reason) {
        Map<String, Object> ack = publisher.publishToDeadLetter(tenantId, domainId, requestId, reason, payload);
        repository.markDeadLetter(domainId, requestId, reason);
        return ack;
    }

    public int retryMax() {
        return retryMax;
    }

    private DeliveryResult sendToDeadLetter(String tenantId, String domainId, String requestId,
                                            String payload, String reason, int attempts) {
        deadLetter(tenantId, domainId, requestId, payload, reason);
        log.error("消息进入死信：domain={} request_id={} attempts={} reason={}",
                domainId, requestId, attempts, reason);
        return new DeliveryResult(DeliveryState.DEAD_LETTER, attempts, requestId);
    }

    private void requirePg() {
        if (!repository.available()) {
            throw new BizException(ErrorCode.AGENT_COLLAB_BUS_UNAVAILABLE,
                    "PG 不可用，可靠投递状态机不可用（不做内存降级）：" + repository.lastError(), Map.of());
        }
    }
}