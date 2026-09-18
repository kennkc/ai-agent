package com.agent.body.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 管道（R3-07）：检索结果 → 上下文组装 → 生成回答（含来源）
 *
 * <p>DEBT-002 关联：回答生成当前为**模板拼接**（截取 TOP 片段），与 session-manager 口径一致；
 * 触发点 VS2/Phase 4 LLM Gateway 接入后，本类只需替换 {@link #compose} 的生成段，
 * 检索、引用与降级逻辑保持不变。
 *
 * <p>设计约束：**引用必须可回溯**——每条 citation 带 doc_id/chunk_id/score/来源，
 * 且回答中显式标注降级（无 LLM、无结果）状态，不允许"编造答案"。
 */
@Service
public class RagPipeline {

    private final RetrievalService retrievalService;

    public RagPipeline(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    public RagAnswer answer(String tenantId, String question, int topK) {
        long started = System.currentTimeMillis();
        RetrievalService.RetrievalOutcome outcome = retrievalService.retrieve(tenantId, question, topK, true);
        List<Map<String, Object>> citations = new ArrayList<>();
        for (RetrievalService.SearchHit hit : outcome.hits()) {
            Map<String, Object> citation = new LinkedHashMap<>();
            citation.put("chunk_id", hit.chunkId());
            citation.put("doc_id", hit.docId());
            citation.put("title", hit.title());
            citation.put("heading", hit.heading());
            citation.put("chunk_index", hit.chunkIndex());
            citation.put("score", hit.rerankScore());
            citation.put("recall_score", hit.score());
            citation.put("source", hit.source());
            citation.put("ingest_time", hit.ingestTimeIso());
            citation.put("snippet", snippet(hit.content(), question));
            citations.add(citation);
        }
        String answer = compose(question, citations);
        long latency = System.currentTimeMillis() - started;
        return new RagAnswer(answer, citations, outcome.hits().isEmpty() ? "no_knowledge" : "template",
                outcome.cacheHit(), latency, outcome.tier());
    }

    /** 生成段（Phase 4 换 LLM）；当前按 TOP 片段模板拼接，保留引用可追溯性 */
    private String compose(String question, List<Map<String, Object>> citations) {
        if (citations.isEmpty()) {
            return "当前知识库未检索到与该问题相关的内容，请补充文档后重试。";
        }
        Map<String, Object> first = citations.get(0);
        String title = String.valueOf(first.getOrDefault("title", "knowledge"));
        String body = String.valueOf(first.getOrDefault("snippet", ""));
        StringBuilder builder = new StringBuilder();
        builder.append("根据知识库《").append(title).append("》检索结果：").append(body);
        if (citations.size() > 1) {
            builder.append("\n\n其他相关片段：");
            for (int i = 1; i < Math.min(citations.size(), 3); i++) {
                Map<String, Object> citation = citations.get(i);
                builder.append("\n- 《").append(citation.get("title")).append("》")
                        .append(String.valueOf(citation.getOrDefault("snippet", "")));
            }
        }
        builder.append("\n\n（回答由模板生成：LLM 生成待 Phase 4 接入，引用可回溯）");
        return builder.toString();
    }

    /** 高亮定位：返回命中片段（若过长则围绕查询词截窗，供前端高亮展示） */
    private String snippet(String content, String question) {
        if (content == null) return "";
        String trimmed = content.trim();
        if (trimmed.length() <= 240) return trimmed;
        String keyword = question == null ? "" : question.trim();
        if (!keyword.isEmpty()) {
            int index = trimmed.indexOf(keyword);
            if (index < 0 && keyword.length() > 4) {
                index = trimmed.indexOf(keyword.substring(0, 4));
            }
            if (index > 60) {
                int start = Math.max(0, index - 60);
                return "…" + trimmed.substring(start, Math.min(start + 220, trimmed.length())) + "…";
            }
        }
        return trimmed.substring(0, 220) + "…";
    }

    /** RAG 回答（含引用，前端躯体视图/会话链路共用） */
    public record RagAnswer(String answer, List<Map<String, Object>> citations, String generator,
                            boolean cacheHit, long latencyMs, String retrievalTier) { }
}
