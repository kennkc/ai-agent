# PostgreSQL 表结构说明（冷层真相源）

> 数据库 `lifeform` · 由 body-service 启动自举（`PgMetadataStore` 内嵌 DDL）· DDL 同源文件：[`schema.sql`](schema.sql)

## knowledge_document — 知识文档元数据

| 字段 | 类型 | 说明 |
|---|---|---|
| `doc_id` | VARCHAR(64) PK | 文档唯一 ID（ingest 时生成） |
| `tenant_id` | VARCHAR(64) NOT NULL | 租户隔离键（所有查询必带） |
| `title` | VARCHAR(512) | 文档标题 |
| `source` | VARCHAR(256) | 来源标识 |
| `char_count` | INT | 原文字符数 |
| `chunk_count` | INT | 分块数（INDEXED 后回填） |
| `status` | VARCHAR(32) NOT NULL | 状态机：`PENDING → INDEXED \| FAILED`（`DocumentStatus`） |
| `backend` | VARCHAR(64) | 摄取/嵌入后端标识（降级可见性用） |
| `created_at` / `updated_at` | BIGINT | 毫秒时间戳 |
| `error` | VARCHAR(1024) | FAILED 时的错误摘要 |

索引：`idx_knowledge_document_tenant (tenant_id, updated_at DESC)` — 支撑"按租户列最新文档"。

## knowledge_chunk — 知识分块

| 字段 | 类型 | 说明 |
|---|---|---|
| `chunk_id` | VARCHAR(96) PK | **与 Qdrant point id 一一对应**（检索命中后回表取正文） |
| `doc_id` | VARCHAR(64) NOT NULL | 所属文档 |
| `tenant_id` | VARCHAR(64) NOT NULL | 租户隔离键 |
| `chunk_index` | INT | 分块序号（重叠切分：max 800 / min 200 / overlap 50 字符） |
| `heading` | VARCHAR(512) | 所属标题路径 |
| `content` | TEXT | 分块正文（正文真相在 PG，向量真相在 Qdrant） |
| `char_count` | INT | 分块字符数 |
| `vector_backend` | VARCHAR(64) | 生成向量的嵌入后端（降级可见性用） |
| `created_at` | BIGINT | 毫秒时间戳 |

索引：`idx_knowledge_chunk_tenant (tenant_id, doc_id, chunk_index)` — 支撑"按文档序读分块"。

## 写入方与读取方

- **写入**：仅 body-service（ingest 管线：解析→分块→嵌入→PG 元数据 + Qdrant 向量，事务性以 PG 状态机为准）
- **读取**：body-service `RetrievalService`（Qdrant 命中 chunk_id → 回表取 content）；`PgMetadataStore` 提供按租户的文档/分块列举与计数

## 数据现状（2026-09-18 快照）

- `knowledge_document` 8 行、`knowledge_chunk` 12 行（含 default / tenant-b 两租户）
- 完整导出见 `../snapshots/postgres-schema.sql`（pg_dump 口径）与 `../snapshots/postgres-data.sql`（20 条 INSERT）
