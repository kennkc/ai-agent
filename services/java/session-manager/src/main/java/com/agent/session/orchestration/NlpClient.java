package com.agent.session.orchestration;

import com.agent.session.common.BizException;
import com.agent.session.common.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@Component
public class NlpClient {
    private final RestClient client;
    public NlpClient(@Value("${app.nlp.base-url:http://127.0.0.1:8000}") String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }
    public Map<String, Object> recognize(String question, String sessionId, String tenantId) {
        try {
            Map<String, Object> response = client.post().uri("/api/nlp/intent").header("X-Tenant-Id", tenantId)
                    .body(Map.of("text", question, "session_id", sessionId, "tenant_id", tenantId))
                    .retrieve().body(new ParameterizedTypeReference<>() {});
            return response == null ? Map.of("intent", "闲聊", "confidence", 0.5) : response;
        } catch (RestClientException e) {
            throw new BizException(ErrorCode.AGENT_BUS_UNAVAILABLE, "NLP service unavailable");
        }
    }
}
