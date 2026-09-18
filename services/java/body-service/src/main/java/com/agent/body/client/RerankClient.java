package com.agent.body.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 重排客户端（R3-06 · Should）：调用 nlp-service 的 bge-reranker / 降级词法后端。
 *
 * <p>重排是**质量增强**而非必需环节：上游不可用时返回空列表并由检索服务标记
 * {@code rerankDegraded=true}（按召回分排序返回），保证检索链路不因 Should 项中断。
 */
@Component
public class RerankClient {

    private static final Logger log = LoggerFactory.getLogger(RerankClient.class);

    private final RestClient client;

    public RerankClient(@Value("${app.body.nlp.base-url:http://127.0.0.1:8000}") String baseUrl) {
        this.client = OutboundHttp.restClient(baseUrl);
    }

    @SuppressWarnings("unchecked")
    public RerankHealth health() {
        try {
            Map<String, Object> body = client.get().uri("/api/nlp/rerank/health").retrieve().body(Map.class);
            if (body == null) {
                return new RerankHealth(false, "unavailable", true);
            }
            return new RerankHealth(Boolean.TRUE.equals(body.get("available")),
                    String.valueOf(body.getOrDefault("backend", "unavailable")),
                    Boolean.TRUE.equals(body.get("degraded")));
        } catch (RestClientException e) {
            log.warn("重排后端健康探测失败：{}", e.getMessage());
            return new RerankHealth(false, "unavailable", true);
        }
    }

    @SuppressWarnings("unchecked")
    public RerankBatch rerank(String query, List<RerankCandidate> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return new RerankBatch(List.of(), "skipped", false);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("top_k", topK);
        payload.put("candidates", candidates.stream().map(candidate -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", candidate.id());
            row.put("text", candidate.text());
            row.put("score", candidate.score());
            return row;
        }).toList());
        try {
            Map<String, Object> body = client.post().uri("/api/nlp/rerank").body(payload).retrieve().body(Map.class);
            if (body == null || body.get("results") == null) {
                return new RerankBatch(List.of(), "empty", true);
            }
            List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("results");
            List<RerankedHit> hits = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                hits.add(new RerankedHit(String.valueOf(row.get("id")),
                        ((Number) row.getOrDefault("score", 0)).doubleValue(),
                        ((Number) row.getOrDefault("rerank_score", 0)).doubleValue(),
                        ((Number) row.getOrDefault("rank_before", 0)).intValue(),
                        ((Number) row.getOrDefault("rank_after", 0)).intValue()));
            }
            return new RerankBatch(hits, String.valueOf(body.getOrDefault("backend", "unknown")),
                    Boolean.TRUE.equals(body.get("degraded")));
        } catch (RestClientException e) {
            log.warn("重排降级（保留召回顺序）：{}", e.getMessage());
            return new RerankBatch(List.of(), "unavailable", true);
        }
    }

    public record RerankCandidate(String id, String text, double score) { }

    public record RerankedHit(String id, double score, double rerankScore, int rankBefore, int rankAfter) { }

    public record RerankBatch(List<RerankedHit> hits, String backend, boolean degraded) { }

    public record RerankHealth(boolean available, String backend, boolean degraded) { }
}
