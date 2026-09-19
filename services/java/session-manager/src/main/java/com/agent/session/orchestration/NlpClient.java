package com.agent.session.orchestration;

import com.agent.session.common.BizException;
import com.agent.session.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * nlp-service 意图识别客户端（{@code POST /api/nlp/intent}）。
 *
 * <p>读超时使用 {@link OutboundHttp#INTENT_TIMEOUT} 档（5s）—— 规则级联是毫秒级操作，
 * 给它生成档的时长只会让故障晚暴露。
 *
 * <p>可选传入 {@link RequestBudget}：整条 ask 链共享一个总预算时，本跳只在剩余预算内发起，
 * 剩余为 0 则直接返回 FALLBACK 并记日志，**不硬发**。
 */
@Component
public class NlpClient {

    private static final Logger log = LoggerFactory.getLogger(NlpClient.class);

    private final String baseUrl;
    private final boolean degradeOnFailure;

    public NlpClient(@Value("${app.nlp.base-url:http://127.0.0.1:8000}") String baseUrl,
                     @Value("${app.nlp.degrade-on-failure:true}") boolean degradeOnFailure) {
        this.baseUrl = baseUrl;
        this.degradeOnFailure = degradeOnFailure;
    }

    public Map<String, Object> recognize(String question, String sessionId, String tenantId) {
        return recognize(question, sessionId, tenantId, null);
    }

    /**
     * @param budget 整条链的总预算；为 {@code null} 表示不参与预算传播（只用本档上限）
     */
    public Map<String, Object> recognize(String question, String sessionId, String tenantId,
                                        RequestBudget budget) {
        if (budget != null && budget.exhausted()) {
            log.warn("意图识别降级为 FALLBACK：预算已耗尽（已用 {}ms / 总 {}ms）",
                    budget.elapsedMs(), budget.totalMs());
            return fallback();
        }
        Duration timeout = Duration.ofMillis(budget == null
                ? OutboundHttp.INTENT_TIMEOUT.toMillis()
                : budget.clamp(OutboundHttp.INTENT_TIMEOUT.toMillis()));
        try {
            Map<String, Object> response = OutboundHttp.restClient(baseUrl, timeout)
                    .post().uri("/api/nlp/intent").header("X-Tenant-Id", tenantId)
                    .body(Map.of("text", question, "session_id", sessionId, "tenant_id", tenantId))
                    .retrieve().body(new ParameterizedTypeReference<>() {});
            return response == null ? fallback() : response;
        } catch (RestClientException e) {
            if (!degradeOnFailure) {
                throw new BizException(ErrorCode.AGENT_UPSTREAM_UNAVAILABLE, "NLP service unavailable", e);
            }
            log.warn("意图识别降级为 FALLBACK（nlp-service 不可用）：{}", e.getMessage(), e);
            return fallback();
        }
    }

    /**
     * 意图识别不可用时的兜底：语义与 nlp-service 的 FALLBACK 引擎一致，
     * 让问答链路退化为「模板回答」，而不是整条链路 503。
     */
    private Map<String, Object> fallback() {
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("intent", "闲聊");
        fallback.put("confidence", 0.5);
        fallback.put("engine", "FALLBACK");
        return fallback;
    }
}
