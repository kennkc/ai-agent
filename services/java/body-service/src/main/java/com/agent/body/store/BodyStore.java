package com.agent.body.store;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BodyStore {
    private final Map<String, List<StoredChunk>> tenantChunks = new ConcurrentHashMap<>();

    public int ingest(String tenantId, String docId, String title, String content, String source) {
        if (content == null || content.isBlank()) return 0;
        List<StoredChunk> chunks = tenantChunks.computeIfAbsent(tenantId, ignored -> new ArrayList<>());
        int count = 0;
        for (String text : split(content)) {
            chunks.add(new StoredChunk(UUID.randomUUID().toString(), docId, title, text, source));
            count++;
        }
        return count;
    }

    public List<StoredChunk> retrieve(String tenantId, String query, int topK) {
        if (query == null || query.isBlank()) return List.of();
        return tenantChunks.getOrDefault(tenantId, List.of()).stream()
                .map(c -> new ScoredChunk(c, score(query, c.content())))
                .filter(s -> s.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(Math.max(1, Math.min(topK, 10)))
                .map(ScoredChunk::chunk).toList();
    }

    private List<String> split(String content) {
        List<String> chunks = new ArrayList<>();
        for (String block : content.replace("\r\n", "\n").split("\n+")) {
            String value = block.trim();
            while (value.length() > 300) { chunks.add(value.substring(0, 300)); value = value.substring(300); }
            if (!value.isBlank()) chunks.add(value);
        }
        return chunks;
    }

    private double score(String query, String content) {
        if (content.contains(query)) return 1.0;
        String normalized = query.replaceAll("\\s+", "");
        if (normalized.isEmpty()) return 0;
        long hits = normalized.chars().filter(c -> content.indexOf(c) >= 0).count();
        return (double) hits / normalized.length();
    }

    public record StoredChunk(String chunk_id, String doc_id, String title, String content, String source) {}
    private record ScoredChunk(StoredChunk chunk, double score) {}
}
