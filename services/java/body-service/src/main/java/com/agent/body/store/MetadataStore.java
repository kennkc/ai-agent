package com.agent.body.store;

import java.util.List;

/**
 * 元数据存储（R3-01 冷层抽象）：文档与切片的真相源。
 *
 * <p>真相源语义：向量库里只有向量 + 冗余 payload，**元数据以本接口的实现为准**，
 * 对账任务（设计 §6 风险应对）按此判定 Qdrant 与元数据是否一致。
 */
public interface MetadataStore {

    /** 落库文档元数据（同 doc_id 覆盖，支持重入库） */
    void saveDocument(StoredDocument document);

    /** 覆盖式写入切片（先按 doc 清理，避免重入库残留旧切片） */
    void replaceChunks(String tenantId, String docId, List<StoredChunk> chunks);

    StoredDocument findDocument(String tenantId, String docId);

    List<StoredDocument> listDocuments(String tenantId, int limit);

    List<StoredChunk> listChunks(String tenantId, String docId);

    boolean deleteDocument(String tenantId, String docId);

    long countDocuments(String tenantId);

    long countChunks(String tenantId);

    /** 后端类型（pg / memory），用于状态面板诚实展示 */
    String backend();
}
