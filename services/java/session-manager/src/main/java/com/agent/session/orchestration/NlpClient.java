package com.agent.session.orchestration;

import com.agent.session.common.BizException;
import com.agent.session.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class NlpClient {

    private static final Logger log = LoggerFactory.getLogger(NlpClient.class);

    private final RestClient client;
    private final boolean degradeOnFailure;

    public NlpClient(@Value("${app.nlp.base-url:http://127.0.0.1:8000}") String baseUrl,
                     @Value("${app.nlp.degrade-on-failure:true}") boolean degradeOnFailure) {
        this.client = OutboundHttp.restClient(baseUrl);
        this.degradeOnFailure = degradeOnFailure;
    }

    public Map<String, Object> recognize(String question, String sessionId, String tenantId) {
        try {
            Map<String, Object> response = client.post().uri("/api/nlp/intent").header("X-Tenant-Id", tenantId)
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
