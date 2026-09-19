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

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R4-06 大脑层客户端：调 {@code nlp-service} 的 {@code POST /api/nlp/brain/ask}。
 *
 * <p>与 {@link NlpClient}（只做意图识别）分工：本类承载**完整问答链路**——
 * 规划 → 检索 → 生成 → 来源标注，返回体含 {@code answer / sources / gap / chain /
 * generator / degraded_reasons / decision_id}。
 *
 * <p><b>降级语义</b>（与全平台一致）：
 * <ul>
 *   <li>{@code degrade-on-failure=true}（默认）：大脑层不可用 → 返回 {@code available=false}
 *       的空结果，由 {@code SessionController} 退化为本地检索直出，并标注 {@code degraded}；</li>
 *   <li>{@code =false}：直接抛 {@code AGENT_UPSTREAM_UNAVAILABLE}。</li>
 * </ul>
 * **不伪造回答**：降级时 {@code answer} 为空字符串，由调用方决定显示什么。
 *
 * <p><b>读超时</b>：{@link OutboundHttp#BRAIN_TIMEOUT}（30s），覆盖 nlp-service
 * {@code /brain/ask} 的端到端总预算 20s（`BRAIN_TOTAL_BUDGET_MS`）并留 1.5x 余量（登记表 TB-11 / GAP-01）。
 * 原值 10s 小于下游最坏 22s，是「上游先把『慢』说成『不可用』」的又一实例。
 */
@Component
public class BrainClient {

    private static final Logger log = LoggerFactory.getLogger(BrainClient.class);

    private final String baseUrl;
    private final boolean degradeOnFailure;

    public BrainClient(@Value("${app.nlp.base-url:http://127.0.0.1:8000}") String baseUrl,
                       @Value("${app.brain.degrade-on-failure:true}") boolean degradeOnFailure) {
        this.baseUrl = baseUrl;
        this.degradeOnFailure = degradeOnFailure;
    }

    /**
     * 发起一次大脑层问答。
     *
     * @param context 最近 K 轮上下文（{@code [{role, content, ...}]}），可为空
     */
    public BrainAnswer ask(String question, String sessionId, String tenantId,
                           String intent, double intentConfidence, List<Map<String, Object>> context) {
        return ask(question, sessionId, tenantId, intent, intentConfidence, context, null);
    }

    /**
     * @param budget 整条 ask 链的总预算；为 {@code null} 表示不参与预算传播（只用本档上限）
     */
    public BrainAnswer ask(String question, String sessionId, String tenantId,
                           String intent, double intentConfidence, List<Map<String, Object>> context,
                           RequestBudget budget) {
        if (budget != null && budget.exhausted()) {
            log.warn("大脑层降级：预算已耗尽（已用 {}ms / 总 {}ms）—— 不再发起调用，"
                            + "由调用方走本地检索直出", budget.elapsedMs(), budget.totalMs());
            return degraded("budget_exhausted: " + budget.elapsedMs() + "ms/" + budget.totalMs() + "ms");
        }
        Duration timeout = Duration.ofMillis(budget == null
                ? OutboundHttp.BRAIN_TIMEOUT.toMillis()
                : budget.clamp(OutboundHttp.BRAIN_TIMEOUT.toMillis()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", question);
        payload.put("session_id", sessionId);
        payload.put("tenant_id", tenantId);
        payload.put("intent", intent == null ? "" : intent);
        payload.put("intent_confidence", intentConfidence);
        payload.put("context", context == null ? List.of() : context);
        payload.put("use_cache", true);
        try {
            Map<String, Object> response = OutboundHttp.restClient(baseUrl, timeout).post()
                    .uri("/api/nlp/brain/ask")
                    .header("X-Tenant-Id", tenantId)
                    .body(payload)
                    .retrieve().body(new ParameterizedTypeReference<>() {});
            if (response == null || response.isEmpty()) {
                return degraded("empty response from brain layer");
            }
            return BrainAnswer.available(response);
        } catch (RestClientException e) {
            if (!degradeOnFailure) {
                throw new BizException(ErrorCode.AGENT_UPSTREAM_UNAVAILABLE, "brain layer unavailable", e);
            }
            log.warn("大脑层降级（nlp-service /brain/ask 不可用）：{}", e.getMessage(), e);
            return degraded("brain_unavailable: " + e.getMessage());
        }
    }

    private static BrainAnswer degraded(String reason) {
        return new BrainAnswer(false, "", List.of(), Map.of(), List.of(), "", false, List.of(reason), "");
    }

    /**
     * 大脑层回答（扁平化常用字段，原始响应保留在 {@link #raw()}）。
     */
    public record BrainAnswer(
            boolean available,
            String answer,
            List<Map<String, Object>> sources,
            Map<String, Object> gap,
            List<Map<String, Object>> chain,
            String generator,
            boolean degraded,
            List<String> degradedReasons,
            String decisionId
    ) {
        static BrainAnswer available(Map<String, Object> raw) {
            return new BrainAnswer(
                    true,
                    str(raw.get("answer")),
                    maps(raw.get("sources")),
                    map(raw.get("gap")),
                    maps(raw.get("chain")),
                    str(raw.get("generator")),
                    Boolean.parseBoolean(String.valueOf(raw.getOrDefault("degraded", false))),
                    strings(raw.get("degraded_reasons")),
                    str(raw.get("decision_id"))
            );
        }

        private static String str(Object value) {
            return value == null ? "" : String.valueOf(value);
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> map(Object value) {
            return value instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : Map.of();
        }

        private static List<Map<String, Object>> maps(Object value) {
            List<Map<String, Object>> out = new ArrayList<>();
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        out.add(map(m));
                    }
                }
            }
            return out;
        }

        private static List<String> strings(Object value) {
            List<String> out = new ArrayList<>();
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    out.add(String.valueOf(item));
                }
            }
            return out;
        }
    }
}
