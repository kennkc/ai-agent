package com.agent.collab.delivery;

import com.agent.collab.common.BizException;
import com.agent.collab.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 幂等消费助手（R-MC01-02）。
 *
 * <p>消费流程（Phase 6 的 handler 通过本类保证幂等）：
 * <pre>
 *   consume(domainId, requestId, memberId, payload) ->
 *     首次? -> 交给业务处理器执行 -> 成功后 ack
 *     重复? -> 直接 ack（不执行，避免重复副作用）
 *   业务失败 -> 抛异常，由调用方按重投上限处理（超限落死信）
 * </pre>
 *
 * <p>设计约束：**去重标记先于业务执行**。若业务失败，request_id 仍保持"已见" ——
 * 重投时会被判定为重复而丢弃。这与"最多一次"语义一致；需要"至少一次 + 业务幂等"
 * 的场景应在业务侧用自己的幂等键，而不是依赖本表（见 MC-P3 设计文档 §3.2）。
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
        this.retryMax = retryMax;
    }

    /** 业务处理函数。 */
    @FunctionalInterface
    public interface Handler {
        void handle(String payload) throws Exception;
    }

    /**
     * 处理一条消息。
     *
     * @return {@code true} = 本次真正执行；{@code false} = 重复消息（已直接丢弃）
     */
    public boolean consume(String tenantId, String domainId, String requestId, String memberId,
                           String payload, Handler handler) {
        requirePg();
        boolean first = repository.markSeen(domainId, requestId, memberId);
        if (!first) {
            log.debug("重复消息已丢弃：domain={} request_id={}", domainId, requestId);
            return false;
        }
        try {
            handler.handle(payload);
            return true;
        } catch (Exception e) {
            // 业务失败：交给上层决定重投；此处不吞异常，也不删除幂等标记
            log.warn("消息处理失败（幂等标记保留）：domain={} request_id={} err={}", domainId, requestId, e.getMessage());
            throw new BizException(ErrorCode.AGENT_INTERNAL, "消息处理失败：" + e.getMessage(),
                    Map.of("domain_id", domainId, "request_id", requestId));
        }
    }

    /**
     * 重投超限后的收口：把消息送入死信并返回回执。
     *
     * <p>由调用方在捕获到超过 {@link #retryMax()} 次失败后调用。
     */
    public Map<String, Object> deadLetter(String tenantId, String domainId, String requestId,
                                          String payload, String reason) {
        return publisher.publishToDeadLetter(tenantId, domainId, requestId, reason, payload);
    }

    public int retryMax() {
        return retryMax;
    }

    private void requirePg() {
        if (!repository.available()) {
            throw new BizException(ErrorCode.AGENT_COLLAB_BUS_UNAVAILABLE,
                    "PG 不可用，幂等去重不可用（不做内存降级）：" + repository.lastError(), Map.of());
        }
    }
}
