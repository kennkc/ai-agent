package com.agent.body.store;

/** 文档索引状态（R3-09 索引异步更新口径：PENDING → INDEXED / FAILED） */
public enum DocumentStatus {
    PENDING,
    INDEXED,
    FAILED,
    DELETED
}
