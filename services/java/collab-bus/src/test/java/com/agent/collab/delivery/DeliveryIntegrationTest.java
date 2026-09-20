package com.agent.collab.delivery;

import com.agent.collab.common.BizException;
import com.agent.collab.common.ErrorCode;
import com.agent.collab.domain.CollabDomain;
import com.agent.collab.domain.DomainService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-MC01-02 真实 PG + NATS 集成测试。
 *
 * <p>覆盖：publish ack、消费者离线恢复、真实 durable consumer、自动 DLQ 与关闭域拒发。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "COLLAB_IT", matches = "true")
class DeliveryIntegrationTest {

    @Autowired
    DomainService domainService;

    @Autowired
    MessagePublisher publisher;

    @Autowired
    IdempotentConsumer consumer;

    @Autowired
    ReliableConsumer reliableConsumer;

    @Autowired
    IdempotencyRepository idempotencyRepository;

    private String newDomain(String tenant) {
        String name = "it-" + UUID.randomUUID().toString().substring(0, 6);
        return domainService.create(tenant, name).domainId();
    }

    @Test
    void publishToActiveDomainSucceedsWithStreamAck() {
        String tenant = "it-tenant";
        String domainId = newDomain(tenant);

        Map<String, Object> ack = publisher.publish(tenant, domainId, "dispatch", "agent-a",
                "req-" + UUID.randomUUID(), "{\"task\":\"demo\"}");

        assertNotNull(ack.get("stream"));
        assertNotNull(ack.get("seq"));
        assertTrue(String.valueOf(ack.get("subject")).startsWith("collab." + domainId + ".dispatch."));
    }

    @Test
    void duplicateRequestIdExecutesHandlerOnlyOnce_againstRealPg() {
        String tenant = "it-tenant";
        String domainId = newDomain(tenant);
        String requestId = "req-dup-" + UUID.randomUUID();

        int[] executed = {0};
        IdempotentConsumer.Handler handler = payload -> executed[0]++;

        assertTrue(consumer.consume(tenant, domainId, requestId, "agent-a", "p", handler), "首次执行");
        assertFalse(consumer.consume(tenant, domainId, requestId, "agent-a", "p", handler), "重投丢弃");

        assertEquals(1, executed[0], "同一 request_id 的业务副作用必须只发生一次");
        assertEquals(1, idempotencyRepository.countByDomain(domainId));
    }

    @Test
    void consumerOfflineRecoversAllMessagesFromDurableStream() throws Exception {
        String tenant = "it-tenant";
        String domainId = newDomain(tenant);
        String[] requestIds = new String[50];

        reliableConsumer.stopDomain(domainId);
        for (int i = 0; i < requestIds.length; i++) {
            requestIds[i] = "req-offline-" + i + "-" + UUID.randomUUID();
            publisher.publish(tenant, domainId, "heartbeat", "agent-offline", requestIds[i],
                    "{\"progress\":" + (i % 100) + ",\"state\":\"working\"}");
        }

        reliableConsumer.startDomain(domainId);
        for (String requestId : requestIds) {
            awaitStatus(domainId, requestId, "processed", 15000);
        }
        assertEquals(50, idempotencyRepository.countByDomain(domainId));
    }

    @Test
    void malformedHeartbeatEventuallyReachesDeadLetter() throws Exception {
        String tenant = "it-tenant";
        String domainId = newDomain(tenant);
        String requestId = "req-dlq-" + UUID.randomUUID();

        publisher.publish(tenant, domainId, "heartbeat", "agent-bad", requestId, "not-json");
        awaitStatus(domainId, requestId, "dlq", 20000);

        assertEquals("dlq", idempotencyRepository.status(domainId, requestId));
    }

    @Test
    void publishToClosedDomainIsRejected_againstRealPg() {
        String tenant = "it-tenant";
        String domainId = newDomain(tenant);
        domainService.close(tenant, domainId);

        BizException e = assertThrows(BizException.class,
                () -> publisher.publish(tenant, domainId, "dispatch", "agent-a", "req-x", "p"));
        assertEquals(ErrorCode.AGENT_COLLAB_DOMAIN_CLOSED, e.code());
    }

    @Test
    void deadLetterPublishReturnsAck() {
        String tenant = "it-tenant";
        String domainId = newDomain(tenant);

        Map<String, Object> ack = publisher.publishToDeadLetter(tenant, domainId, "req-dead", "重投超限", "p");

        assertNotNull(ack.get("seq"));
        assertTrue(String.valueOf(ack.get("subject")).endsWith(".deadletter"));
    }

    private void awaitStatus(String domainId, String requestId, String expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String actual = "";
        while (System.currentTimeMillis() < deadline) {
            actual = idempotencyRepository.status(domainId, requestId);
            if (expected.equals(actual)) {
                return;
            }
            Thread.sleep(100);
        }
        assertEquals(expected, actual, "request_id 未在超时前进入预期状态：" + requestId);
    }
}