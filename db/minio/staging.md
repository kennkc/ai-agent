# 感官暂存区：MinIO 主后端 + 本地降级后端

> 归属：sense-service（R2-10 隔离暂存）· 双后端自动选择：`StagingStore` 按 MinIO 可用性实时降级，保证「低质拦截 + 批次可回滚」始终成立

## 后端选择规则

| 条件 | 后端 | 引用格式 |
|---|---|---|
| MinIO 可达 | MinIO | `minio://lifeform-staging/staging/...` |
| MinIO 失效 | 本地目录 `data/sense-staging/` | 本地路径 |
| MinIO 恢复 | 自动切回 | 读取时双后端兜底互查 |

## MinIO 对象键约定

```
bucket: lifeform-staging        （MINIO_BUCKET，bucket 不存在时自动创建）
key:  staging/{tenant}/{STAGING_STATUS}/batch-{batchId}/item-{index}.json
示例: staging/default/PENDING/batch-20260918-001/item-0.json
```

- `STAGING_STATUS`：PENDING（待质检）→ ACCEPTED / REJECTED（质检后回滚或放行）
- 对象内容：`CollectedData.toMap()` 的 JSON

## 本地降级目录（backend=auto 且 MinIO 不可用时）

```
data/sense-staging/{tenant}/{status}/batch-{batchId}/item-0.json
```

目录根由 `STAGING_LOCAL_ROOT` 控制，默认 `data/sense-staging`（相对 sense-service 工作目录）。**该目录属运行期数据，不入 git**（归档需求时手动拷入 `db/snapshots/` 并带时间戳）。

## 数据现状（2026-09-18 快照）

MinIO 容器未启动，暂存批均已走完质检流程（PENDING 清空），本地降级目录当前为空——**无现存暂存数据需归档**。暂存数据的生命周期短（采集→质检→消费→清理），长期真相由 body-service 的 PG/Qdrant 承接。

## 死信队列（DeadLetterStore，内存态）

采集重试耗尽的失败记录：**有界内存队列，上限 500 条**（FIFO 淘汰），字段 `id/channel/tenant_id/data_source/error/attempts/mode/created_at`，经 `/deadletters` 可查，供人工补偿。重启即清空，不做持久化——归档需求时以接口导出为准。
