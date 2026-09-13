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

import java.util.List;
import java.util.Map;

@Component
public class BodyClient {

    private static final Logger log = LoggerFactory.getLogger(BodyClient.class);

    private final RestClient client;
    private final boolean degradeOnFailure;

    public BodyClient(@Value("${app.body.base-url:http://127.0.0.1:8083}") String baseUrl,
                      @Value("${app.body.degrade-on-failure:true}") boolean degradeOnFailure) {
        this.client = OutboundHttp.restClient(baseUrl);
        this.degradeOnFailure = degradeOnFailure;
    }

    public List<Map<String, Object>> retrieve(String question, String tenantId) {
        try {
            List<Map<String, Object>> response = client.post().uri("/api/body/retrieve").header("X-Tenant-Id", tenantId)
                    .body(Map.of("query", question, "top_k", 3))
                    .retrieve().body(new ParameterizedTypeReference<>() {});
            return response == null ? List.of() : response;
        } catch (RestClientException e) {
            if (!degradeOnFailure) {
                throw new BizException(ErrorCode.AGENT_UPSTREAM_UNAVAILABLE, "Body service unavailable", e);
            }
            log.warn("知识检索降级为空结果（body-service 不可用）：{}", e.getMessage(), e);
            return List.of();
        }
    }
}
