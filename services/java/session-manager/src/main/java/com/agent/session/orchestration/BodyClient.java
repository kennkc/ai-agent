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
import java.util.List;
import java.util.Map;

/**
 * body-service 语义检索客户端（{@code POST /api/body/retrieve}）。
 *
 * <p>读超时使用 {@link OutboundHttp#RETRIEVE_TIMEOUT} 档（15s）。
 * 原值 10s 与下游 body-service 的读超时**相等**，登记表记为 <b>GAP-02</b>：
 * 上游预算 = 下游最坏耗时意味着**余量为 0** —— 下游一旦走到自己的读超时边界，
 * 上游必然先放弃，于是降级原因被误报成「body 不可用」。
 *
 * <p>可选传入 {@link RequestBudget}：预算已耗尽时不再发起调用，直接返回空结果并记日志
 * （返回体与「真检索不到」一致，但日志可区分）。
 */
@Component
public class BodyClient {

    private static final Logger log = LoggerFactory.getLogger(BodyClient.class);

    private final String baseUrl;
    private final boolean degradeOnFailure;

    public BodyClient(@Value("${app.body.base-url:http://127.0.0.1:8083}") String baseUrl,
                      @Value("${app.body.degrade-on-failure:true}") boolean degradeOnFailure) {
        this.baseUrl = baseUrl;
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
        return retrieve(question, tenantId, null);
    }

    /**
     * @param budget 整条链的总预算；为 {@code null} 表示不参与预算传播（只用本档上限）
     */
    public List<Map<String, Object>> retrieve(String question, String tenantId, RequestBudget budget) {
        if (budget != null && budget.exhausted()) {
            log.warn("知识检索跳过：预算已耗尽（已用 {}ms / 总 {}ms）—— 返回空结果，"
                            + "但这**不得读作「检索不到」**",
                    budget.elapsedMs(), budget.totalMs());
            return List.of();
        }
        Duration timeout = Duration.ofMillis(budget == null
                ? OutboundHttp.RETRIEVE_TIMEOUT.toMillis()
                : budget.clamp(OutboundHttp.RETRIEVE_TIMEOUT.toMillis()));
        try {
            List<Map<String, Object>> response = OutboundHttp.restClient(baseUrl, timeout)
                    .post().uri("/api/body/retrieve").header("X-Tenant-Id", tenantId)
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
