# wp-bff（work-platform BFF · 最小 Ops 子集）

前端工作平台的轻量后端服务。当前实现中间件观测与控制、链路追踪观测共四个端点，后续 BFF 端点按阶段逐步迁入。

## 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/wp/healthz` | 进程存活 + 控制面配置自检（令牌来源、来源白名单、key 清单），不含令牌明文 |
| GET | `/api/wp/middleware` | 探测 8 个中间件（TCP 探针），返回 `MiddlewareOverview` 契约 |
| POST | `/api/wp/middleware/:key/start` | 启动中间件：`docker compose up -d <key>` |
| POST | `/api/wp/middleware/:key/stop` | 终止中间件：`docker compose stop <key>` |
| GET | `/api/wp/tracing` | Jaeger 在线时返回真实服务注册列表与每服务最近 20 条 trace 聚合统计（traces/spans/错误率/P99）及全局最新 12 条链路；Jaeger 未启动时返回 `enabled:false` |

## 安全模型（2026-09-16 加固）

读写端点采用不同的信任级别：

- **读端点（GET）**：开放访问，但 CORS 不再使用通配符，仅对 `WP_BFF_ALLOWED_ORIGINS` 内的来源回显 CORS 头。
- **控制端点（POST）**：双重校验，缺一不可。
  1. **来源校验**：浏览器请求的 `Origin`（或 `Referer`）必须命中来源白名单，否则 403 —— 阻断跨站请求伪造（CSRF）。
  2. **控制令牌**：必须携带 `X-WP-Control-Token`，与进程持有的令牌 `timingSafeEqual` 比较，否则 401。
- **令牌来源**：优先读环境变量 `WP_BFF_CONTROL_TOKEN`（推荐，日志中不出现明文）；未配置时启动生成一次性随机令牌，写入 `logs/wp-bff-control-token`（0600）并打印到 stdout。**未配置时不会退化为"无鉴权"**。
- **key 白名单**：只接受 `redis / postgres / qdrant / nats / nacos / minio / jaeger / kafka` 八个固定 key，其余一律 403。
- **命令固定**：只执行 `up -d` 与 `stop` 两种形态，key 经白名单校验后拼入，不接受任何其他参数。
- **异步任务跟踪**：启动/终止后进入内存 ops 表，`GET /middleware` 报告 `starting` / `stopping` 中间态，探针到达目标态后自动清除；watchdog 180s/120s 兜底。
- **审计留痕**：所有控制操作与鉴权失败写入 `logs/wp-bff-audit.log`。

### 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `WP_BFF_PORT` | `8090` | 监听端口（固定绑定 `127.0.0.1`） |
| `WP_BFF_CONTROL_TOKEN` | 启动时随机生成 | 控制端点令牌；生产环境必须显式配置 |
| `WP_BFF_ALLOWED_ORIGINS` | `http://127.0.0.1:3001,http://localhost:3001,http://[::1]:3001` | 允许的来源，逗号分隔 |

### 已知边界

- 服务固定绑定回环地址 `127.0.0.1`，这是首层防护；生产部署必须置于网关之后并保持回环绑定。
- 令牌是**进程级静态凭据**，尚未接入 JWT/租户级权限；多用户场景需在 Phase 7 治理期补租户鉴权与最小权限。
- 审计日志目前写入本地文件，未接入集中式日志与告警。

## 前端接入

开发期前端由 Vite 代理 `/api/wp` → `http://127.0.0.1:8090`（见 `web/work-platform/vite.config.ts`）。代理在**服务端**读取令牌并注入 `X-WP-Control-Token`，令牌不会进入浏览器 bundle。

- 通过 `127.0.0.1:3001` / `localhost:3001` 访问开发服务器时无需额外配置（已在默认来源白名单内）。
- 通过局域网 IP 访问时，需把该来源加入 `WP_BFF_ALLOWED_ORIGINS`，例如 `WP_BFF_ALLOWED_ORIGINS=http://192.168.1.10:3001`。
- 前端**不覆盖** `Origin` 头，CSRF 防护在代理链路上仍然生效。

生产路径为网关 `/api/wp/**` 路由（待建），届时由网关注入令牌并透传租户上下文。

## 运行与测试

```bash
# 仓库根目录下（Node >= 20，零运行依赖）
node services/node/wp-bff/server.js
# 默认端口 8090，可用 WP_BFF_PORT 覆盖

# 控制面安全回归测试（Node 内置 test runner，14 个用例）
cd services/node/wp-bff && node --test
```

测试覆盖：只读端点契约、令牌缺失/错误、来源越权（CSRF）、合法控制请求的命令形态、非白名单 key、CORS 收紧、预检请求、Jaeger 未启动降级、tracing 采样口径标注。

前置条件：根目录需有 `.env`（复制 `.env.example`），其中 `MINIO_ROOT_USER` 等为 `docker compose` 必需变量；
目录名变更后还需沿用既有 `COMPOSE_PROJECT_NAME`，否则会与存量容器/数据卷命名冲突。

前端中间件监控卡片**始终以本服务的真实探针结果为准**（不受 `VITE_DATA_SOURCE` mock 开关限制）；
BFF 未运行时前端回落演示数据，并在界面标注"演示数据 · BFF 未连接"。

开发期前端由 Vite 代理 `/api/wp` → `http://127.0.0.1:8090`（见 `web/work-platform/vite.config.ts`）；生产路径为网关 `/api/wp/**` 路由（待建）。
