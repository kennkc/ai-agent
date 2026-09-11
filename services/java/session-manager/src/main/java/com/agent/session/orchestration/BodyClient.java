package com.agent.session.orchestration;

import com.agent.session.common.BizException;
import com.agent.session.common.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

@Component
public class BodyClient {
    private final RestClient client;
    public BodyClient(@Value("${app.body.base-url:http://127.0.0.1:8083}") String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }
    public List<Map<String, Object>> retrieve(String question, String tenantId) {
        try {
            List<Map<String, Object>> response = client.post().uri("/api/body/retrieve").header("X-Tenant-Id", tenantId)
                    .body(Map.of("query", question, "top_k", 3))
                    .retrieve().body(new ParameterizedTypeReference<>() {});
            return response == null ? List.of() : response;
        } catch (RestClientException e) {
            throw new BizException(ErrorCode.AGENT_BUS_UNAVAILABLE, "Body service unavailable");
        }
    }
}
