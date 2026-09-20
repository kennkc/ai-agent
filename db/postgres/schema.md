# PostgreSQL 表结构说明（冷层真相源）

> 数据库 `lifeform` · 三个服务各自**启动时自举**内嵌 DDL（`CREATE TABLE IF NOT EXISTS`，无迁移框架）· 同源文件：[`schema.sql`](schema.sql)

## 表清单与 DDL 归属

| 表 | 归属服务 | 内嵌 DDL 位置 | 用途 |
|---|---|---|---|
| `knowledge_document` | body-service | `store/PgMetadataStore.java` | 知识文档元数据 |
| `knowledge_chunk` | body-service | `store/PgMetadataStore.java` | 知识分块正文 |
| `llm_model_config` | nlp-service | `app/model_config.py` | **WB-10 模型接入配置（前台可配）** |
| `brain_decision_log` | nlp-service | `app/brain/audit.py` | 决策链审计（D5/X4 回放） |
| `memory_entity` | nlp-service | `app/brain/memory_graph.py` | 记忆图谱实体 |
| `memory_relation` | nlp-service | `app/brain/memory_graph.py` | 记忆图谱关系 |
| `tool_audit_log` | tool-executor | `audit/ToolAuditLog.java` | 工具调用审计 |

> `schema.sql` 是三份内嵌 DDL 的**同源副本**，仅作手动初始化 / 审查用；运行时以各服务内嵌 DDL 为准。
> 改动任一侧内嵌 DDL 必须同步本文件与 `schema.sql`（维护约定见 `../README.md` §五）。

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

## llm_model_config — 模型接入配置（WB-10）

按**功能角色**绑定大模型接口。角色取值：`intent` / `embed` / `rerank` / `generate` / `plan` / `code`
（`generate` 已接入问答生成链路；其余角色为引擎已装配、调用点待后续阶段接入，见
`docs/功能逻辑/NLP服务-模型接入配置.md` §6）。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | BIGSERIAL PK | 配置 ID |
| `tenant_id` | VARCHAR(64) NOT NULL | 租户隔离键，默认 `default` |
| `config_key` | VARCHAR(64) NOT NULL | **功能角色**（上列 6 值之一） |
| `name` | VARCHAR(128) NOT NULL | 同角色内显示名（角色内唯一） |
| `provider` | VARCHAR(64) | `deepseek` / `openai` / `qwen` / `anthropic` / `moonshot` / `zhipu` / `local` / `custom` |
| `base_url` | VARCHAR(512) | OpenAI 兼容端点根地址 |
| `model` | VARCHAR(160) | 模型名（如 `deepseek-chat`） |
| `api_key_cipher` | TEXT | **AES-256-GCM 密文**（AAD = `lifeform.llm_model_config.v1`）；明文永不落库 |
| `api_key_hint` | VARCHAR(64) | 脱敏展示值（`sk-***last4`），响应里只出这个 |
| `tier` | VARCHAR(4) | 层级 `L1`/`L2`/`L3`，参与降级链 |
| `max_tokens` | INTEGER | 生成参数（装配引擎时覆盖请求默认值） |
| `temperature` | NUMERIC(3,2) | 同上 |
| `timeout_ms` | INTEGER | 单次调用预算 |
| `routing_weight` | INTEGER | 角色内优先级（**大者优先**，同角色多配置时决定降级顺序） |
| `enabled` | BOOLEAN | 未启用则**不进入角色引擎**，也不参与路由 |
| `extra` | JSONB | 扩展位 |
| `last_probe_*` | — | 连通性探测结果（时间/成功/延迟/错误），由 `POST /models/{id}/test` 回填 |
| `created_at` / `updated_at` | TIMESTAMPTZ | 写入时间 |
| `updated_by` | VARCHAR(64) | 操作者（来自 `X-Actor` 头，审计归属） |

索引与约束：

- `uq_llm_model_config_tenant_role_name (tenant_id, config_key, name)` **唯一** — 防止同角色下出现两条同名配置导致路由结果不确定（冲突返回 409）
- `idx_llm_model_config_tenant_role (tenant_id, config_key, enabled)` — 支撑"按租户+角色列可用配置"

主密钥来源（`MODEL_CONFIG_MASTER_KEY` 环境变量优先，否则读本地密钥文件 `.model-config-key`，0600 且已 gitignore）。
**主密钥不可用时拒绝写入**，绝不退化成明文落库。

## brain_decision_log — 决策链审计

| 字段 | 类型 | 说明 |
|---|---|---|
| `decision_id` | TEXT PK | 决策 ID（回放主键） |
| `tenant_id` | TEXT NOT NULL | 租户隔离键 |
| `session_id` | TEXT | 会话 ID |
| `question` / `intent` / `answer` | TEXT | 问题 / 意图 / 最终答复 |
| `generator` / `model` | TEXT | 实际生效的引擎与模型（**降级可追溯**） |
| `degraded` | BOOLEAN | 本次是否走了降级路径 |
| `confidence` | REAL | 置信度 |
| `payload` | JSONB NOT NULL | 完整链步（intent/plan/retrieve/gap/generate/verify） |
| `created_at` | DOUBLE PRECISION | Unix 秒（ES 风格小数） |
| `created_at_iso` | TEXT | 可读时间 |

索引：`idx_brain_decision_tenant_time (tenant_id, created_at DESC)` — 支撑决策索引列表。

## memory_entity / memory_relation — 记忆图谱（IN-02）

| 表 | 关键字段 | 约束 |
|---|---|---|
| `memory_entity` | `id` PK, `tenant_id`, `name`, `kind`, `aliases` JSONB, `mentions`, `created_at`, `updated_at` | `UNIQUE (tenant_id, name)` — 同租户实体名唯一 |
| `memory_relation` | `id` PK, `tenant_id`, `source_id`, `target_id`, `rel`, `weight`, `evidence`, `created_at`, `updated_at` | `UNIQUE (tenant_id, source_id, target_id, rel)` — 同三元组不重复 |

索引：`idx_memory_relation_tenant (tenant_id, source_id)`。

## tool_audit_log — 工具调用审计

| 字段 | 类型 | 说明 |
|---|---|---|
| `audit_id` | VARCHAR(64) PK | 审计 ID |
| `call_id` | VARCHAR(64) NOT NULL | 调用 ID（关联一次执行） |
| `tenant_id` | VARCHAR(64) NOT NULL | 租户隔离键 |
| `tool_name` / `tool_version` | VARCHAR | 工具名与版本 |
| `args_summary` | VARCHAR(2048) | 参数摘要（**已脱敏/截断**，不落敏感原文） |
| `success` | BOOLEAN NOT NULL | 是否成功 |
| `output` | TEXT | 输出（失败时可为空） |
| `error_code` / `error_message` | VARCHAR | 失败原因（工具域专属错误码） |
| `latency_ms` | BIGINT | 调用耗时 |
| `sandboxed` / `sandbox_backend` | BOOLEAN / VARCHAR(48) | 沙箱执行标记与后端 |
| `degraded` | BOOLEAN | 是否走了降级路径 |
| `requested_by` | VARCHAR(96) | 发起方 |
| `created_at` | BIGINT | 毫秒时间戳 |

索引：`idx_tool_audit_tenant (tenant_id, created_at DESC)`。
PG 不可用时降级为**内存环形缓冲**（容量 `app.tool.audit.memory-capacity`，默认 500），
降级状态经 `/api/tool/audit/stats` 如实上报，不假装已落库。

## 写入方与读取方

| 表 | 写入方 | 读取方 |
|---|---|---|
| `knowledge_document` / `knowledge_chunk` | 仅 body-service ingest 管线（事务性以 PG 状态机为准） | body-service `RetrievalService` / `PgMetadataStore` |
| `llm_model_config` | nlp-service `model_config` CRUD（经 wp-bff `/api/wp/models` 写路径） | nlp-service `resolve_engines()` → `LlmGateway` 角色引擎装配 |
| `brain_decision_log` | nlp-service 问答链路（每次决策一条） | nlp-service `/brain/decisions`、`/brain/{id}`；wp-bff 代理 |
| `memory_entity` / `memory_relation` | nlp-service `/brain/memory/ingest` | nlp-service `/brain/memory/subgraph`、`/stats` |
| `tool_audit_log` | tool-executor `ToolAuditLog` | tool-executor `/api/tool/audit`；wp-bff 代理 |

**共同降级不变量**：任一 PG 不可用时各模块回落到进程内存储并**如实上报 degraded**（
`status().degraded` / `audit_backend` / `pgAvailable`），绝不静默伪造"已落库"。

## 数据现状（快照口径）

**2026-09-20 全库快照（推荐口径）**，实测行数：

| 表 | 行数 |
|---|---|
| `knowledge_document` | 9 |
| `knowledge_chunk` | 13 |
| `brain_decision_log` | 1063 |
| `tool_audit_log` | 27 |
| `llm_model_config` | 0（本轮验证写入已清理，不留假数据） |
| `memory_entity` / `memory_relation` | 0（**本次修复后才建表**，此前从未存在） |

导出文件：`../snapshots/postgres-schema-20260920.sql`（7 表全结构）、
`../snapshots/postgres-data-20260920.sql`（column-inserts 口径）。

**2026-09-18 旧轮快照（结构不完整，仅作对照）**：`../snapshots/postgres-schema.sql` 只含
体层 `knowledge_document` + `knowledge_chunk` 两表，`postgres-data.sql` 为 8 文档 + 12 分块。
该轮**遗漏了 5 张当时已存在的表**，不要单独用它重建库。

**值得记的教训**：归档口径若只跟着"某个服务的 DDL 文件"走，就会漏掉其他服务的表；
应始终以**运行库实际表清单**（`\dt` / `pg_tables`）为准做对账。
