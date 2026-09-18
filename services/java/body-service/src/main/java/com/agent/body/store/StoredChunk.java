package com.agent.body.store;

/**
 * 切片元数据（R3-01 冷层 · 与 Qdrant 向量一一对应，用于对账与回填）
 *
 * @param chunkId      切片 ID（与 Qdrant point id 一致）
 * @param docId        所属文档
 * @param tenantId     租户
 * @param chunkIndex   块序号
 * @param heading      所属小节标题
 * @param content      切片正文（含标题与重叠尾巴）
 * @param charCount    字符数
 * @param vectorBackend 生成向量时的后端标识（模型版本管理依据）
 * @param createdAt    写入时间
 */
public record StoredChunk(String chunkId, String docId, String tenantId, int chunkIndex, String heading,
                          String content, int charCount, String vectorBackend, long createdAt) { }
