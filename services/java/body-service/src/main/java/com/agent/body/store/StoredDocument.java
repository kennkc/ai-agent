package com.agent.body.store;

/**
 * 文档元数据（R3-01 冷层 · PG 元数据为真相源）
 *
 * @param docId       文档 ID
 * @param tenantId    租户（隔离维度，来自网关 X-Tenant-Id）
 * @param title       文档标题（回答引用与列表展示）
 * @param source      来源渠道（sense 采集渠道 / manual 手工上传）
 * @param charCount   正文字符数
 * @param chunkCount  分块数
 * @param status      索引状态
 * @param backend     向量后端（bge-m3 / hash-ngram-768），用于重索引判定
 * @param createdAt   入库时间
 * @param updatedAt   最近更新时间
 * @param error       失败原因（status=FAILED 时非空）
 */
public record StoredDocument(String docId, String tenantId, String title, String source, int charCount,
                             int chunkCount, DocumentStatus status, String backend,
                             long createdAt, long updatedAt, String error) {

    public StoredDocument withStatus(DocumentStatus newStatus, int newChunkCount, String newError) {
        return new StoredDocument(docId, tenantId, title, source, charCount, newChunkCount, newStatus,
                backend, createdAt, System.currentTimeMillis(), newError);
    }
}
