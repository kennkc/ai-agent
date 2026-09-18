package com.agent.body.client;

import com.agent.body.common.BizException;
import com.agent.body.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 嵌入客户端（R3-03）：调用 nlp-service 的 BGE-M3 / 降级哈希后端。
 *
 * <p>设计约束：**不伪造向量质量**。上游不可用时直接抛
 * {@link ErrorCode#AGENT_UPSTREAM_UNAVAILABLE}，由入库流程把文档标为 FAILED，
 * 而不是写入随机向量造成"看起来入库成功、检索永远不命中"。
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private final RestClient client;

    public EmbeddingClient(@Value("${app.body.nlp.base-url:http://127.0.0.1:8000}") String baseUrl,
                           @Value("${app.body.nlp.embed-read-timeout-seconds:30}") long readTimeoutSeconds) {
        this.client = OutboundHttp.restClient(baseUrl, Duration.ofSeconds(readTimeoutSeconds));
    }

    /** 后端健康：body-service 据 dim 决定 Qdrant 集合维度，据 degraded 标注降级来源 */
    @SuppressWarnings("unchecked")
    public EmbeddingHealth health() {
        try {
            Map<String, Object> body = client.get().uri("/api/nlp/embed/health").retrieve().body(Map.class);
            if (body == null) {
                return new EmbeddingHealth(false, "unavailable", 0, true);
            }
            return new EmbeddingHealth(Boolean.TRUE.equals(body.get("available")),
                    String.valueOf(body.getOrDefault("backend", "unavailable")),
                    ((Number) body.getOrDefault("dim", 0)).intValue(),
                    Boolean.TRUE.equals(body.get("degraded")));
        } catch (RestClientException e) {
            log.warn("嵌入后端健康探测失败：{}", e.getMessage());
            return new EmbeddingHealth(false, "unavailable", 0, true);
        }
    }

    /** 批量向量化；失败即抛上游不可用（不降级、不伪造） */
    @SuppressWarnings("unchecked")
    public EmbedBatch embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new BizException(ErrorCode.AGENT_BAD_REQUEST, "texts must not be empty");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("texts", texts);
        try {
            Map<String, Object> body = client.post().uri("/api/nlp/embed").body(payload).retrieve().body(Map.class);
            if (body == null || body.get("vectors") == null) {
                throw new BizException(ErrorCode.AGENT_UPSTREAM_UNAVAILABLE, "嵌入服务返回空响应");
            }
            List<List<Number>> raw = (List<List<Number>>) body.get("vectors");
            List<double[]> vectors = raw.stream().map(row -> {
                double[] vector = new double[row.size()];
                for (int i = 0; i < row.size(); i++) {
                    vector[i] = row.get(i).doubleValue();
                }
                return vector;
            }).toList();
            return new EmbedBatch(vectors,
                    ((Number) body.getOrDefault("dim", vectors.isEmpty() ? 0 : vectors.get(0).length)).intValue(),
                    String.valueOf(body.getOrDefault("backend", "unknown")),
                    Boolean.TRUE.equals(body.get("degraded")),
                    ((Number) body.getOrDefault("latency_ms", 0)).longValue());
        } catch (RestClientException e) {
            throw new BizException(ErrorCode.AGENT_UPSTREAM_UNAVAILABLE, "嵌入服务不可用：" + e.getMessage(), e);
        }
    }

    public double[] embedOne(String text) {
        return embed(List.of(text)).vectors().get(0);
    }

    public record EmbeddingHealth(boolean available, String backend, int dim, boolean degraded) { }

    public record EmbedBatch(List<double[]> vectors, int dim, String backend, boolean degraded, long latencyMs) { }
}
