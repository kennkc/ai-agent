# db/ — 数据存储归档（Agent-Lifeform）

> 建立时间：2026-09-18 · 依据实测运行态（容器 Up 4h）导出 · 口径与 `docs/java-services/08-数据存储与归档.md` 同步维护

本目录归档当前开发所需的**全部数据存储结构**与**真实数据快照**。规则：结构文档随代码演进更新；`snapshots/` 是某时点的运行态导出，**只增不改**（新一轮导出用新时间戳文件，旧快照保留）。

## 一、存储介质清单（对齐 docker-compose.yml）

> 更新时间：2026-09-20（补齐 5 张遗漏表 + 新增全库快照）

| 介质 | 镜像 | 容器 | 数据卷 | 用途 | 归档位置 |
|---|---|---|---|---|---|
| PostgreSQL 16 + pgvector | `pgvector/pgvector:pg16` | lifeform-postgres | `ai-agent_pg-data` | **冷层真相源**：文档/分块元数据 | `postgres/` + `snapshots/postgres-*.sql` |
| Redis 7.2 | `redis:7.2-alpine` | lifeform-redis | `ai-agent_redis-data` | 会话状态（热）+ 检索结果缓存 + 访问计数（分层依据） | `redis/` + `snapshots/redis-keys.json` |
| Qdrant 1.12.4 | `qdrant/qdrant:v1.12.4` | lifeform-qdrant | `ai-agent_qdrant-data` | 向量库：语义检索候选 | `qdrant/` + `snapshots/qdrant-*.json` |
| MinIO | `minio:RELEASE.2024-01-16` | lifeform-minio | `ai-agent_minio-data` | 感官采集暂存（主后端；未运行时降级本地目录） | `minio/` |
| 本地文件系统 | — | — | `data/sense-staging/` | 暂存降级后端（MinIO 失效时自动接管） | `minio/staging.md` |
| Kafka 3.7 | `apache/kafka:3.7.0` | lifeform-kafka | `ai-agent_kafka-data` | 事件总线（采集事件/会话事件），非持久业务数据 | `snapshots/kafka-topics.txt` |

**服务 → 存储映射**：

| 服务 | 持久化 | 说明 |
|---|---|---|
| gateway-service | 无 | 纯路由转发 |
| session-manager | Redis | 会话哈希 + 消息列表（`session:*`，TTL 2h） |
| sense-service | MinIO / 本地目录 + 内存 | 暂存批（可回滚）+ 有界死信队列（内存 500 条） |
| body-service | PostgreSQL + Redis + Qdrant | 分层存储：冷=PG 真相源、热=Redis 缓存、向量=Qdrant |
| nlp-service | PostgreSQL（+ 内存降级） | **模型接入配置** `llm_model_config`（WB-10）、决策链审计 `brain_decision_log`、记忆图谱 `memory_entity/relation`；PG 不可用时全部回落到进程内存储并如实上报 degraded |
| tool-executor | PostgreSQL（+ 内存环形缓冲） | 工具调用审计 `tool_audit_log`（R5-05）；PG 不可用降级内存，容量可配 |
| wp-bff | 无 | 无状态代理（**不落第二份配置**：模型配置的唯一真相源在 nlp-service） |

## 二、分层存储路由（body-service 核心设计）

`TierRouter` 按热度分层：`hotness = (1 + ln(1+访问次数)) × 0.5^(age/半衰期)`，
半衰期 3600s，hot≥2.0 / warm≥1.0（均可配）。HOT→Redis 缓存优先，WARM/COLD→直连 PG + Qdrant。
**降级不变量**：PG 不可用→回落 `InMemoryMetadataStore` 并告警；Redis 不可用→绕过缓存直连 Qdrant（`cache_backend=unavailable`，命中率不虚增）；Qdrant/嵌入不可用→`AGENT_UPSTREAM_UNAVAILABLE`（检索是 Must 项，不返回假结果）。

## 三、目录内容

```
db/
├── README.md                 ← 本文件（总览 + 恢复指引）
├── postgres/
│   ├── schema.sql            ← 可执行 DDL（**7 张表**，三服务内嵌 DDL 的同源副本，幂等）
│   └── schema.md             ← 表结构说明（字段/索引/状态机/写入方，含 DDL 归属表）
├── redis/
│   └── keyspace.md           ← 键空间设计（session:* / knowledge:*）
├── qdrant/
│   └── collection.md         ← lifeform_knowledge 集合参数
├── minio/
│   └── staging.md            ← 暂存双后端（bucket 约定 + 本地降级目录 + 对象键）
└── snapshots/                ← 运行态真实数据导出（两轮，只增不删）
    ├── postgres-schema-20260920.sql ← pg_dump --schema-only（**全库 7 表**，2026-09-20 补充归档）
    ├── postgres-data-20260920.sql   ← pg_dump --column-inserts（1063 决策日志 + 27 工具审计 + 9 文档 + 13 分块）
    ├── postgres-schema.sql   ← 2026-09-18 旧轮：只含体层 2 表 + 2 索引（**结构不完整，勿单独用于重建**）
    ├── postgres-data.sql     ← 2026-09-18 旧轮：8 文档 + 12 分块
    ├── redis-keys.json       ← 全部 6 键（类型/TTL/值）
    ├── qdrant-collection.json← 集合配置（768 维 Cosine）
    ├── qdrant-points.json    ← 全量 12 点（含向量与 payload）
    └── kafka-topics.txt      ← 业务 topic ×2（+内置 __consumer_offsets）
```

**两轮快照的关系（务必分清）**：`20260918` 那轮只导了体层两张表，**不代表全库结构**；
`20260920` 这轮是全库口径，**重建/审查请以带日期的那两份为准**。旧轮保留仅为对照演进。

**2026-09-20 补齐说明**：本轮归档发现 `snapshots/postgres-schema.sql`（旧轮）**遗漏 5 张已在生产库中的表**
（`llm_model_config`、`brain_decision_log`、`memory_entity`、`memory_relation`、`tool_audit_log`）——
档案与真实运行态不一致。已补齐 `postgres/schema.sql` 与 `postgres/schema.md`，并新增全库快照。
其中 `memory_entity` / `memory_relation` **此前从未在真实库中被创建过**（`pg.ensure_schema()` 的
"首次调用即短路"缺陷导致这两张表永久缺失，写入静默降级到进程内），已随本次修复一并建表，
修复过程见 `docs/功能逻辑/NLP服务-模型接入配置.md` 与 `docs/技术债台账.md`。

## 四、恢复指引（从快照重建）

```bash
# 1. 起存储容器
docker compose up -d postgres redis qdrant minio kafka

# 2. PostgreSQL：结构 + 数据（**用带日期的全库快照，不要用无日期的 0918 旧轮**）
docker exec -i lifeform-postgres psql -U agent -d lifeform < db/snapshots/postgres-schema-20260920.sql
docker exec -i lifeform-postgres psql -U agent -d lifeform < db/snapshots/postgres-data-20260920.sql
#   （空库时各服务启动也会按 postgres/schema.sql 自举，本文件供手动对齐/审查）

# 3. Qdrant：POST db/snapshots/qdrant-points.json 中的 points 到 /collections/lifeform_knowledge/points
#    （集合由 body-service 的 ensureCollection(dim=768) 自举）

# 4. Redis：快照仅含 knowledge:access:* 计数键；会话键 TTL 2h，过期属预期，无需恢复
# 5. Kafka：topic 由各服务启动时自动创建，无需恢复
# 6. 模型配置的密钥：llm_model_config.api_key_cipher 是 AES-256-GCM 密文，**恢复后必须提供
#    同一把 MODEL_CONFIG_MASTER_KEY**，否则历史密文无法解密（配置仍在，但引擎装配会取不到凭据）
```

## 五、维护约定

1. 存储结构变更（DDL/键设计/集合参数）→ 同步改 `postgres|redis|qdrant|minio/` 下文档与 `postgres/schema.sql`，并在 `docs/java-services/08-数据存储与归档.md` §变更台账登记。
2. 需要新快照时：`pg_dump` + `scripts/export-redis-snapshot.py` + Qdrant scroll，文件名带新时间戳，旧快照不删。
3. `snapshots/` 含租户数据（default / tenant-b），**不要提交到公共远端仓库前未经确认**；当前仅随私有仓库归档。
