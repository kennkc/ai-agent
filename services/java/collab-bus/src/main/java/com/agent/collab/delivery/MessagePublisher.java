package com.agent.collab.delivery;

import com.agent.collab.common.BizException;
import com.agent.collab.common.ErrorCode;
import com.agent.collab.domain.CollabDomain;
import com.agent.collab.domain.DomainRepository;
import com.agent.collab.nats.NatsConnection;
import io.nats.client.api.PublishAck;
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
 * <p>主题形态：{@code collab.<domain_id>.<type>.<member_id>}，type 限定为
 * dispatch | result | heartbeat | negotiate —— 限定枚举避免主题空间被任意字符串污染。
 *
 * <p>发布前的两个前置校验：
 * <ol>
 *   <li><b>域存在且属于该租户</b>（跨租户/不存在 -> 404，不泄露存在性）</li>
 *   <li><b>域未被关闭</b>（closed -> 409 AGENT_COLLAB_DOMAIN_CLOSED，符合 MC-P3 需求冻结的行为）</li>
 * </ol>
 *
 * <p>幂等职责划分：<b>发布侧允许重复发布</b>（同 request_id 重发不会报错），
 * 去重由消费侧 {@link IdempotentConsumer} 依据 request_id 完成 —— 这样"生产侧重试"
 * 与"消费侧去重"各自单一职责，且重试不会因发布侧拒绝而丢失消息。
 */
@Service
public class MessagePublisher {
    private static final Logger log = LoggerFactory.getLogger(MessagePublisher.class);

    /** 允许的消息类型（与主题规范一致）。 */
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
        String subject = subject(domainId, type, memberId);
        byte[] body = (payload == null ? "" : payload).getBytes(StandardCharsets.UTF_8);
        try {
            PublishAck ack = nats.jetStream().publish(subject, body);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("subject", subject);
            row.put("stream", ack.getStream());
            row.put("seq", ack.getSeqno());
            row.put("domain_id", domain.domainId());
            row.put("type", type);
            row.put("member_id", memberId);
            row.put("request_id", requestId);
            row.put("duplicate_publish", true); // 发布侧不判重，去重在消费侧
            return row;
        } catch (Exception e) {
            log.error("发布失败 domain={} type={} member={}: {}", domainId, type, memberId, e.getMessage());
            throw new BizException(ErrorCode.AGENT_COLLAB_BUS_UNAVAILABLE,
                    "消息发布失败：" + e.getMessage(), Map.of("subject", subject));
        }
    }

    /**
     * 消费失败达重投上限后落死信主题，**不静默丢弃**（MC-P3 需求冻结）。
     */
    public Map<String, Object> publishToDeadLetter(String tenantId, String domainId, String requestId,
                                                   String reason, String payload) {
        CollabDomain domain = domains.find(tenantId, domainId)
                .orElseThrow(() -> new BizException(ErrorCode.AGENT_COLLAB_DOMAIN_NOT_FOUND,
                        "协作域不存在或不属于当前租户", Map.of("domain_id", domainId)));
        String subject = "collab." + domain.domainId() + "." + DEAD_LETTER;
        String body = "{\"request_id\":\"" + requestId + "\",\"reason\":\"" + String.valueOf(reason).replace("\"", "'")
                + "\",\"payload\":\"" + String.valueOf(payload).replace("\"", "'") + "\"}";
        try {
            PublishAck ack = nats.jetStream().publish(subject, body.getBytes(StandardCharsets.UTF_8));
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
        return domain;
    }
}
