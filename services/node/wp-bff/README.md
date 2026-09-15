# wp-bff（work-platform BFF · 最小 Ops 子集）

前端工作平台的轻量后端服务。当前实现中间件观测与控制、链路追踪观测共三个端点，后续 BFF 端点按阶段逐步迁入。

## 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/wp/middleware` | 探测 8 个中间件（TCP 探针），返回 `MiddlewareOverview` 契约 |
| POST | `/api/wp/middleware/:key/start` | 启动中间件：`docker compose up -d <key>` |
| POST | `/api/wp/middleware/:key/stop` | 终止中间件：`docker compose stop <key>` |
| GET | `/api/wp/tracing` | Jaeger 在线时返回真实服务注册列表与每服务最近 20 条 trace 聚合统计（traces/spans/错误率/P99）及全局最新 12 条链路；Jaeger 未启动时返回 `enabled:false` |

## 安全约束

- **key 白名单**：只接受 `redis / postgres / qdrant / nats / nacos / minio / jaeger / kafka` 八个固定 key，其余一律 403
- **命令固定**：只执行 `up -d` 与 `stop` 两种形态，key 经白名单校验后拼入，不接受任何其他参数
- **异步任务跟踪**：启动/终止后进入内存 ops 表，`GET /middleware` 报告 `starting` / `stopping` 中间态，探针到达目标态后自动清除；watchdog 180s/120s 兜底
- **审计留痕**：所有控制操作追加写入 `logs/wp-bff-audit.log`

## 运行

```bash
# 仓库根目录下（Node >= 20，零依赖）
node services/node/wp-bff/server.js
# 默认端口 8090，可用 WP_BFF_PORT 覆盖
```

开发期前端由 Vite 代理 `/api/wp` → `http://127.0.0.1:8090`（见 `web/work-platform/vite.config.ts`）；生产路径为网关 `/api/wp/**` 路由（待建）。
