package com.agent.body.store;

import com.agent.body.common.BizException;
import com.agent.body.common.ErrorCode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存元数据存储（降级后端）：PG 不可用时的兜底，保证单机演示链路可用。
 *
 * <p>与 Redis 降级同理——**必须显式标注**（{@link #backend()} 返回 {@code memory}），
 * 由状态面板展示，避免"进程重启后知识全丢"被误认为数据还在。
 */
public class InMemoryMetadataStore implements MetadataStore {

    private final Map<String, StoredDocument> documents = new ConcurrentHashMap<>();
    private final Map<String, List<StoredChunk>> chunks = new ConcurrentHashMap<>();

    private static String key(String tenantId, String docId) {
        return tenantId + "::" + docId;
    }

    @Override
    public void saveDocument(StoredDocument document) {
        documents.put(key(document.tenantId(), document.docId()), document);
    }

    @Override
    public void replaceChunks(String tenantId, String docId, List<StoredChunk> newChunks) {
        chunks.put(key(tenantId, docId), new ArrayList<>(newChunks));
    }

    @Override
    public StoredDocument findDocument(String tenantId, String docId) {
        return documents.get(key(tenantId, docId));
    }

    @Override
    public List<StoredDocument> listDocuments(String tenantId, int limit) {
        return documents.values().stream()
                .filter(document -> tenantId.equals(document.tenantId()))
                .sorted(Comparator.comparingLong(StoredDocument::updatedAt).reversed())
                .limit(Math.max(1, Math.min(limit, 200)))
                .toList();
    }

    @Override
    public List<StoredChunk> listChunks(String tenantId, String docId) {
        return List.copyOf(chunks.getOrDefault(key(tenantId, docId), List.of()));
    }

    @Override
    public boolean deleteDocument(String tenantId, String docId) {
        if (documents.remove(key(tenantId, docId)) == null) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND, "document not found: " + docId);
        }
        chunks.remove(key(tenantId, docId));
        return true;
    }

    @Override
    public long countDocuments(String tenantId) {
        return documents.values().stream().filter(document -> tenantId.equals(document.tenantId())).count();
    }

    @Override
    public long countChunks(String tenantId) {
        return chunks.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(tenantId + "::"))
                .mapToLong(entry -> entry.getValue().size())
                .sum();
    }

    @Override
    public String backend() { return "memory"; }
}
