# Qdrant 向量集合：lifeform_knowledge

> Qdrant 1.12.4 · 容器 lifeform-qdrant · 客户端：body-service `QdrantClient`

## 集合参数

| 项 | 值 | 来源 |
|---|---|---|
| 名称 | `lifeform_knowledge` | `QDRANT_COLLECTION` 环境变量，默认同名 |
| 向量维度 | **768** | nlp-service `HASH_DIM=768`（哈希嵌入；BGE-M3 路径为 1024，切换后集合按新 dim 重建） |
| 距离 | **Cosine** | 固定 |
| HNSW | m=16, ef_construct=100, full_scan_threshold=10000 | Qdrant 默认 |
| 创建方式 | `ensureCollection(dim)` 运行时自举 | body-service 依 nlp `/health` 返回的 `dim` 决定 |

## 点结构（payload 约定）

point id = **chunk_id**，与 PostgreSQL `knowledge_chunk.chunk_id` 一一对应：

| payload 字段 | 说明 |
|---|---|
| `doc_id` / `tenant_id` | 回表与租户过滤键（search 必带 tenant filter） |
| `chunk_index` / `heading` | 定位与展示 |
| 其余 | 随摄取管线版本演进，以快照实际 payload 为准 |

正文**不存**在 Qdrant——向量库只做候选召回（TOP-50），命中后回 PG 取 `content`。

## 检索链路（RetrievalService）

Query → Redis 缓存查 → 未命中 → nlp 向量化 → Qdrant search（tenant 过滤，TOP-50）→ 重排（Rerank）→ TOP-K → 回表 → 写缓存。
Qdrant/嵌入不可用 → `AGENT_UPSTREAM_UNAVAILABLE`（检索是 Must 项，不返回假结果）。

## 数据现状（2026-09-18 快照）

- 状态 green · 12 points · segments 8 · indexed_vectors_count 0（低于 `indexing_threshold=20000`，全量暴力扫，属小数据量预期行为）
- 全量导出（含向量）：[`../snapshots/qdrant-points.json`](../snapshots/qdrant-points.json)；集合配置：[`../snapshots/qdrant-collection.json`](../snapshots/qdrant-collection.json)
