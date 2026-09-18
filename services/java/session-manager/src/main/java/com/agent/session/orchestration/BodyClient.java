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

    /**
     * 语义检索（R-C03：会话链路已切至躯体层语义检索）
     *
     * <p>Phase 3 起 body-service 的 {@code POST /api/body/retrieve} 由
     * {@code RetrievalService} 承载：缓存优先 → 向量召回 TOP-50 → 重排 TOP-K。
     * 返回字段在 Phase 2 的 {@code content/title/source} 之上补充
     * {@code chunk_id/doc_id/heading/score/rerank_score/ingest_time_iso}，
     * 本方法原样透传给上层作为 citations（引用可回溯），因此响应结构保持兼容。
     *
     * @param question 用户问题
     * @param tenantId 租户（透传 X-Tenant-Id，检索侧按 payload filter 强隔离）
     */
    public List<Map<String, Object>> retrieve(String question, String tenantId) {
        try {
            List<Map<String, Object>> response = client.post().uri("/api/body/retrieve").header("X-Tenant-Id", tenantId)
                    .body(Map.of("query", question, "top_k", 5, "use_cache", true))
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
