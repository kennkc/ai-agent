package com.agent.body.controller;

import com.agent.body.store.BodyStore;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/body")
public class BodyController {
    private final BodyStore bodyStore;
    public BodyController(BodyStore bodyStore) { this.bodyStore = bodyStore; }

    @PostMapping("/ingest")
    public Map<String, Object> ingest(@RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                      @RequestBody IngestRequest request) {
        int chunkCount = bodyStore.ingest(tenantId, request.doc_id(), request.title(), request.content(), request.source());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("doc_id", request.doc_id()); result.put("chunk_count", chunkCount); result.put("success", chunkCount > 0);
        return result;
    }

    @PostMapping("/retrieve")
    public List<Map<String, Object>> retrieve(@RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                              @RequestBody RetrieveRequest request) {
        int topK = request.top_k() == null ? 3 : request.top_k();
        return bodyStore.retrieve(tenantId, request.query(), topK).stream().map(chunk -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chunk_id", chunk.chunk_id()); row.put("doc_id", chunk.doc_id()); row.put("title", chunk.title());
            row.put("content", chunk.content()); row.put("source", chunk.source());
            return row;
        }).toList();
    }

    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("service", "body-service", "status", "ok"); }

    public record IngestRequest(String doc_id, String title, String content, String source) {}
    public record RetrieveRequest(String query, Integer top_k) {}
}
