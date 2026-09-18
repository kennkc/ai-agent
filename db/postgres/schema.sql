-- ============================================================================
-- Agent-Lifeform · PostgreSQL 冷层真相源 DDL
-- 来源：services/java/body-service/src/main/java/com/agent/body/store/PgMetadataStore.java
--       （内嵌 DDL 常量，启动时 CREATE TABLE IF NOT EXISTS 自举，无需迁移框架）
-- 本文件与其保持同源：改动 Java 侧 DDL 必须同步改这里；反之本文件仅作
-- 手动初始化 / 审查用，运行时以 Java 内嵌 DDL 为准。
-- 数据库：lifeform（对齐 docker-compose.yml：user/password = agent/agent123）
-- ============================================================================

-- 知识文档元数据（ingest 管线入口写入，状态机：PENDING → INDEXED | FAILED）
CREATE TABLE IF NOT EXISTS knowledge_document (
    doc_id      VARCHAR(64)  PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL,
    title       VARCHAR(512),
    source      VARCHAR(256),
    char_count  INT          DEFAULT 0,
    chunk_count INT          DEFAULT 0,
    status      VARCHAR(32)  NOT NULL,
    backend     VARCHAR(64),
    created_at  BIGINT,
    updated_at  BIGINT,
    error       VARCHAR(1024)
);

CREATE INDEX IF NOT EXISTS idx_knowledge_document_tenant
    ON knowledge_document(tenant_id, updated_at DESC);

-- 知识分块（与 Qdrant 点一一对应：chunk_id = Qdrant point id）
CREATE TABLE IF NOT EXISTS knowledge_chunk (
    chunk_id       VARCHAR(96)  PRIMARY KEY,
    doc_id         VARCHAR(64)  NOT NULL,
    tenant_id      VARCHAR(64)  NOT NULL,
    chunk_index    INT,
    heading        VARCHAR(512),
    content        TEXT,
    char_count     INT,
    vector_backend VARCHAR(64),
    created_at     BIGINT
);

CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_tenant
    ON knowledge_chunk(tenant_id, doc_id, chunk_index);
