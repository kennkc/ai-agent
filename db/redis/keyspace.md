# Redis 键空间设计（session-manager + body-service）

> Redis 7.2 · 容器 lifeform-redis · 无持久化业务真相（真相在 PG），可随时清空重建

## 1. session:{sessionId} — 会话哈希（session-manager）

| 项 | 值 |
|---|---|
| 类型 | HASH |
| 写入 | `SessionController.create()` |
| TTL | **2 小时**（`SESSION_TTL`，每次消息刷新 `updated_at`/`last_activity_at`，TTL 不续期则自然过期） |
| 字段 | `tenant_id` · `status`(ACTIVE) · `created_at` · `updated_at` · `last_activity_at` · `message_count` |

关闭会话（DELETE）时连同消息列表一起删除。

## 2. session:{sessionId}:messages — 消息列表（session-manager）

| 项 | 值 |
|---|---|
| 类型 | LIST（`rightPush` 追加，JSON 串） |
| TTL | 2 小时（每次追加后刷新） |
| 内容 | 对话消息（role/content 等 JSON 序列化） |

## 3. knowledge:search:{tenant}:{query指纹} — 检索结果缓存（body-service）

| 项 | 值 |
|---|---|
| 类型 | STRING（检索结果 JSON） |
| 写入 | `HotCacheStore`（HOT 层缓存） |
| TTL | `CACHE_TTL_SECONDS`，默认 3600s |

缓存不可用时绕过直连 Qdrant，`cache_backend=unavailable`——**命中率不虚增**。

## 4. knowledge:access:{tenant}:{query指纹} — 访问计数（body-service）

| 项 | 值 |
|---|---|
| 类型 | STRING 计数（INCR） |
| 用途 | `TierRouter.hotness = (1+ln(1+访问次数)) × 0.5^(age/3600s)` 的频率输入，分层路由依据 |

## 数据现状（2026-09-18 快照）

现存 6 键，全部为 `knowledge:access:*`（default ×5、tenant-b ×1）；`session:*` 因 TTL 2h 已自然过期——**属预期行为，会话数据本就不持久**。全量导出：[`../snapshots/redis-keys.json`](../snapshots/redis-keys.json)（导出脚本：`scripts/export-redis-snapshot.py`）。
