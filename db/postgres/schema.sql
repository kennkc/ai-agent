-- ============================================================================
-- Agent-Lifeform · PostgreSQL 冷层真相源 DDL
--
-- **本文件是两份内嵌 DDL 的同源副本**（两个服务各自启动自举，无需迁移框架）：
--   A. 体层：services/java/body-service/.../store/PgMetadataStore.java
--   B. 大脑层：services/python/nlp-service/app/model_config.py（WB-10 模型接入配置）
-- 改动任一侧内嵌 DDL 必须同步改这里；反之本文件仅作手动初始化 / 审查用，
-- 运行时以各服务内嵌 DDL 为准。
-- 数据库：lifeform（对齐 docker-compose.yml：user/password = agent/agent123）
-- ============================================================================

-- ─────────── A. 体层（body-service · PgMetadataStore）───────────

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

-- ─────────── B. 大脑层（nlp-service · app/model_config.py，WB-10）───────────
--
-- 前台可配置的大模型接入真相源：按**功能角色**（intent/embed/rerank/generate/plan/code）
-- 绑定模型。`api_key_cipher` 存 AES-256-GCM 密文（AAD 绑定表名+版本，主密钥来自
-- `MODEL_CONFIG_MASTER_KEY` 环境变量或本地密钥文件），**明文永不落库**；
-- `api_key_hint` 仅为脱敏展示值（`sk-***last4`）。
-- 注意：`pg.ensure_schema()` 按**语句级**去重（不是按"首次调用"短路），
-- 否则同一进程内后注册的模块拿不到自己的表（2026-09-20 修）。

CREATE TABLE IF NOT EXISTS llm_model_config (
    id                    BIGSERIAL PRIMARY KEY,
    tenant_id             VARCHAR(64)  NOT NULL DEFAULT 'default',
    config_key            VARCHAR(64)  NOT NULL,
    name                  VARCHAR(128) NOT NULL,
    provider              VARCHAR(64)  NOT NULL DEFAULT 'custom',
    base_url              VARCHAR(512) NOT NULL DEFAULT '',
    model                 VARCHAR(160) NOT NULL DEFAULT '',
    api_key_cipher        TEXT         NOT NULL DEFAULT '',
    api_key_hint          VARCHAR(64)  NOT NULL DEFAULT '',
    tier                  VARCHAR(4)   NOT NULL DEFAULT 'L2',
    max_tokens            INTEGER      NOT NULL DEFAULT 512,
    temperature           NUMERIC(3,2) NOT NULL DEFAULT 0.20,
    timeout_ms            INTEGER      NOT NULL DEFAULT 8000,
    routing_weight        INTEGER      NOT NULL DEFAULT 100,
    enabled               BOOLEAN      NOT NULL DEFAULT FALSE,
    extra                 JSONB        NOT NULL DEFAULT '{}'::jsonb,
    last_probe_at         TIMESTAMPTZ,
    last_probe_ok         BOOLEAN,
    last_probe_latency_ms INTEGER,
    last_probe_error      VARCHAR(512) NOT NULL DEFAULT '',
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by            VARCHAR(64)  NOT NULL DEFAULT ''
);

-- 角色内同名不允许：唯一约束是防"同一角色配了两条同名模型导致路由结果不确定"的硬保障
CREATE UNIQUE INDEX IF NOT EXISTS uq_llm_model_config_tenant_role_name
    ON llm_model_config (tenant_id, config_key, name);

CREATE INDEX IF NOT EXISTS idx_llm_model_config_tenant_role
    ON llm_model_config (tenant_id, config_key, enabled);

-- 决策链审计（nlp-service app/brain/audit.py，D5/X4 回放真相源）
CREATE TABLE IF NOT EXISTS brain_decision_log (
    decision_id    TEXT PRIMARY KEY,
    tenant_id      TEXT NOT NULL,
    session_id     TEXT DEFAULT '',
    question       TEXT NOT NULL,
    intent         TEXT DEFAULT '',
    answer         TEXT DEFAULT '',
    generator      TEXT DEFAULT '',
    model          TEXT DEFAULT '',
    degraded       BOOLEAN DEFAULT FALSE,
    confidence     REAL DEFAULT 0,
    payload        JSONB NOT NULL,
    created_at     DOUBLE PRECISION NOT NULL,
    created_at_iso TEXT DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_brain_decision_tenant_time
    ON brain_decision_log (tenant_id, created_at DESC);

-- 记忆图谱实体（nlp-service app/brain/memory_graph.py，IN-02）
CREATE TABLE IF NOT EXISTS memory_entity (
    id         TEXT PRIMARY KEY,
    tenant_id  TEXT NOT NULL,
    name       TEXT NOT NULL,
    kind       TEXT DEFAULT 'concept',
    aliases    JSONB DEFAULT '[]'::jsonb,
    mentions   INTEGER DEFAULT 1,
    created_at DOUBLE PRECISION NOT NULL,
    updated_at DOUBLE PRECISION NOT NULL,
    UNIQUE (tenant_id, name)
);

-- 记忆图谱关系（IN-02）
CREATE TABLE IF NOT EXISTS memory_relation (
    id         TEXT PRIMARY KEY,
    tenant_id  TEXT NOT NULL,
    source_id  TEXT NOT NULL,
    target_id  TEXT NOT NULL,
    rel        TEXT NOT NULL,
    weight     REAL DEFAULT 1.0,
    evidence   TEXT DEFAULT '',
    created_at DOUBLE PRECISION NOT NULL,
    updated_at DOUBLE PRECISION NOT NULL,
    UNIQUE (tenant_id, source_id, target_id, rel)
);

CREATE INDEX IF NOT EXISTS idx_memory_relation_tenant
    ON memory_relation (tenant_id, source_id);

-- ─────────── C. 四肢层（tool-executor · ToolAuditLog，R5-05）───────────

-- 工具调用审计（写路径真相源；PG 不可用时降级为内存环形缓冲，容量可配）
CREATE TABLE IF NOT EXISTS tool_audit_log (
    audit_id        VARCHAR(64)  PRIMARY KEY,
    call_id         VARCHAR(64)  NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL,
    tool_name       VARCHAR(96)  NOT NULL,
    tool_version    VARCHAR(32),
    args_summary    VARCHAR(2048),
    success         BOOLEAN      NOT NULL,
    output          TEXT,
    error_code      VARCHAR(64),
    error_message   VARCHAR(1024),
    latency_ms      BIGINT,
    sandboxed       BOOLEAN,
    sandbox_backend VARCHAR(48),
    degraded        BOOLEAN,
    requested_by    VARCHAR(96),
    created_at      BIGINT
);

CREATE INDEX IF NOT EXISTS idx_tool_audit_tenant
    ON tool_audit_log(tenant_id, created_at DESC);
