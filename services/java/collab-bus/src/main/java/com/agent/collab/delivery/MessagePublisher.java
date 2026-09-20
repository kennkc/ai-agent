package com.agent.collab.delivery;

import com.agent.collab.common.BizException;
import com.agent.collab.common.ErrorCode;
import com.agent.collab.domain.CollabDomain;
import com.agent.collab.domain.DomainRepository;
import com.agent.collab.nats.NatsConnection;
import io.nats.client.PublishOptions;
import io.nats.client.api.PublishAck;
import io.nats.client.impl.Headers;
import io.nats.client.impl.NatsMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 消息发布器（R-MC01-02 / 接口冻结供 Phase 6 使用）。
 *
 * <p>消息正文仍是业务 payload；租户、request_id、member_id、type 通过 NATS headers
 * 随消息传递，并由 JetStream message id 参与服务端去重。消费端必须使用这些 headers
 * 才能完成幂等与重投状态机。
 */
@Service
public class MessagePublisher {
    private static final Logger log = LoggerFactory.getLogger(MessagePublisher.class);

    public static final Set<String> TYPES = Set.of("dispatch", "result", "heartbeat", "negotiate");
    public static final String DEAD_LETTER = "deadletter";

    private final NatsConnection nats;
    private final DomainRepository domains;

    public MessagePublisher(NatsConnection nats, DomainRepository domains) {
        this.nats = nats;
        this.domains = domains;
    }

    /** 发布一条消息。返回 stream/seq 等回执信息。 */
    public Map<String, Object> publish(String tenantId, String domainId, String type, String memberId,
                                       String requestId, String payload) {
        CollabDomain domain = requireActiveDomain(tenantId, domainId);
        String normalizedRequest = requireRequestId(requestId);
        String subject = subject(domainId, type, memberId);
        byte[] body = (payload == null ? "" : payload).getBytes(StandardCharsets.UTF_8);
        try {
            Headers headers = new Headers()
                    .add("X-Tenant-Id", tenantId == null || tenantId.isBlank() ? "default" : tenantId)
                    .add("X-Request-Id", normalizedRequest)
                    .add("X-Member-Id", memberId == null || memberId.isBlank() ? "-" : memberId)
                    .add("X-Message-Type", type);
            NatsMessage message = NatsMessage.builder()
                    .subject(subject)
                    .headers(headers)
                    .data(body)
                    .build();
            PublishAck ack = nats.jetStream().publish(message,
                    PublishOptions.builder().messageId(normalizedRequest).build());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("subject", subject);
            row.put("stream", ack.getStream());
            row.put("seq", ack.getSeqno());
            row.put("domain_id", domain.domainId());
            row.put("type", type);
            row.put("member_id", memberId);
            row.put("request_id", normalizedRequest);
            row.put("duplicate_publish", ack.isDuplicate());
            return row;
        } catch (Exception e) {
            log.error("发布失败 domain={} type={} member={}: {}", domainId, type, memberId, e.getMessage());
            throw new BizException(ErrorCode.AGENT_COLLAB_BUS_UNAVAILABLE,
                    "消息发布失败：" + e.getMessage(), Map.of("subject", subject));
        }
    }

    /** 消费失败达上限后落死信主题，**不静默丢弃**（MC-P3 需求冻结）。 */
    public Map<String, Object> publishToDeadLetter(String tenantId, String domainId, String requestId,
                                                   String reason, String payload) {
        CollabDomain domain = domains.find(tenantId, domainId)
                .orElseThrow(() -> new BizException(ErrorCode.AGENT_COLLAB_DOMAIN_NOT_FOUND,
                        "协作域不存在或不属于当前租户", Map.of("domain_id", domainId)));
        String subject = "collab." + domain.domainId() + "." + DEAD_LETTER;
        String body = "{\"request_id\":\"" + requestId + "\",\"reason\":\"" + String.valueOf(reason).replace("\"", "'")
                + "\",\"payload\":\"" + String.valueOf(payload).replace("\"", "'") + "\"}";
        try {
            // 使用独立 message id，避免与原始消息在同一 stream 内被 JetStream 判重而丢失死信。
            Headers headers = new Headers()
                    .add("X-Tenant-Id", tenantId == null || tenantId.isBlank() ? "default" : tenantId)
                    .add("X-Request-Id", requestId)
                    .add("X-Message-Type", DEAD_LETTER);
            NatsMessage message = NatsMessage.builder()
                    .subject(subject)
                    .headers(headers)
                    .data(body.getBytes(StandardCharsets.UTF_8))
                    .build();
            PublishAck ack = nats.jetStream().publish(message,
                    PublishOptions.builder().messageId(requestId + ".dlq").build());

            log.warn("消息进入死信：domain={} request_id={} reason={}", domainId, requestId, reason);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("subject", subject);
            row.put("stream", ack.getStream());
            row.put("seq", ack.getSeqno());
            row.put("request_id", requestId);
            return row;
        } catch (Exception e) {
            throw new BizException(ErrorCode.AGENT_COLLAB_BUS_UNAVAILABLE,
                    "死信发布失败（原始消息仍需人工介入，绝不静默丢弃）：" + e.getMessage(),
                    Map.of("domain_id", domainId, "request_id", requestId));
        }
    }

    public static String subject(String domainId, String type, String memberId) {
        if (!TYPES.contains(type)) {
            throw new BizException(ErrorCode.AGENT_COLLAB_HEARTBEAT_INVALID,
                    "不支持的消息类型：" + type + "（允许：" + TYPES + "）", Map.of("type", type));
        }
        return "collab." + domainId + "." + type + "." + (memberId == null ? "-" : memberId);
    }

    private CollabDomain requireActiveDomain(String tenantId, String domainId) {
        CollabDomain domain = domains.find(tenantId, domainId)
                .orElseThrow(() -> new BizException(ErrorCode.AGENT_COLLAB_DOMAIN_NOT_FOUND,
                        "协作域不存在或不属于当前租户", Map.of("domain_id", domainId)));
        if (domain.closed()) {
            throw new BizException(ErrorCode.AGENT_COLLAB_DOMAIN_CLOSED,
                    "协作域已关闭，拒绝新消息", Map.of("domain_id", domainId, "state", domain.state()));
        }
        if (!CollabDomain.STATE_ACTIVE.equals(domain.state())) {
            throw new BizException(ErrorCode.AGENT_COLLAB_BUS_UNAVAILABLE,
                    "协作域尚未就绪，拒绝新消息", Map.of("domain_id", domainId, "state", domain.state()));
        }
        return domain;
    }

    private static String requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(ErrorCode.AGENT_BAD_REQUEST, "request_id 不能为空", Map.of());
        }
        String value = requestId.trim();
        if (value.length() > 128) {
            throw new BizException(ErrorCode.AGENT_BAD_REQUEST, "request_id 长度不能超过 128", Map.of());
        }
        return value;
    }
}