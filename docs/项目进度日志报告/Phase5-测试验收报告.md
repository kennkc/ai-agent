# Phase 5 四肢期 · 测试验收报告

| 项 | 内容 |
|---|---|
| 阶段 | Phase 5 · 四肢期（Limb Stage，执行能力） |
| 仓库 | `E:\AI\ai-agent`（分支 `dev` / `codex/main` / `workbuddy/main`） |
| 代码基线 | `0921345` + 复审收尾轮 |
| 验收日期 | 2026-09-19 |
| 需求范围 | R5-01 ~ R5-08 + 终端线 R-C05(预) + IN-06 |
| **验收结论** | **通过**（Must 7/7、Should 1/1、终端线达标；**沙箱 Docker 真隔离已实测**；超范围缺口 WB/RE 如实登记，见 §8） |

---

## 1. 验收环境

| 组件 | 状态 | 说明 |
|---|---|---|
| Docker Desktop | ✅ 20.10.12 | 沙箱后端（`agent-sandbox:latest`，124 MB） |
| PostgreSQL | ✅ 运行中 | 审计真相源 `tool_audit_log` |
| Kafka | ✅ 运行中 | 审计旁路 `lifeform.tool.invoked` |
| tool-executor (8084) | ✅ 运行中 | 本轮复验启动，启动日志 `Docker 沙箱探测：available=true` |
| wp-bff (8090) | ✅ 运行中 | 含 `/api/wp/tools*` |
| work-platform | ✅ 构建通过 | 执行视图 `ExecutionView.vue` |

---

## 2. 验收范围与方法

| 层 | 方法 |
|---|---|
| 单元/集成 | Java `mvn test`、Python `pytest`、wp-bff `node --test` |
| 契约 | `scripts/contract-check.py --work-platform`（含工具 schema 基线） |
| 文档覆盖 | `scripts/java-doc-coverage.py` |
| 前端 | `npx vue-tsc --noEmit` + `npx vite build` |
| 端到端（沙箱） | 真起 8084 + **真调 Docker**，发真实 HTTP 请求（Python 客户端），含逃逸样本 |
| 沙箱隔离（内核级） | `docker run` 直调，取系统级错误码作为隔离证据 |

---

## 3. 需求逐条验收（R5-01 ~ R5-08）

| ID | 需求 | 优先级 | DoD | 验收证据 | 结论 |
|---|---|:---:|---|---|:---:|
| R5-01 | 工具注册表 | Must | 可注册、可列出、可卸载 | `ToolRegistryTest`（13 项）；`GET /api/tool/registry` → `summary.tool_count=3, change_count=3`；`DELETE /api/tool/{name}` 可用 | ✅ |
| R5-02 | 工具执行引擎 | Must | 错误友好；超时熔断 <5s | `ToolExecutorTest`（10 项）；熔断阈值 3 → 打开 30s | ✅ |
| R5-03 | 沙箱隔离 | Must | 恶意命令（rm/网络/提权）被拦 | **Docker 真隔离**：`active_backend=docker, isolated=true, degraded=false`；逃逸样本见 §5.2 | ✅ |
| R5-04 | 内置工具：计算/HTTP | Must | 两类工具调用成功 | `CalculatorToolTest`（6）· `HttpToolTest`（8）；calculator `sqrt(2)+pow(2,10)=1025.414…`；http 非白名单域名被拒 | ✅ |
| R5-05 | 内置工具：代码执行 | Must | 返回 stdout/stderr；危险操作拦截 | `CodeToolTest`（6）；`code` 正常输出 + 写盘被内核拒（`Errno 30`） | ✅ |
| R5-06 | Function Calling | Must | "计算+查询"组合任务完成 | `test_function_calling.py` 15 项；`decider="rule"` 如实标注 | ✅ |
| R5-07 | 白名单与参数校验 | Must | 白名单外拒绝；非法参数报错 | `ToolGuardTest`（16 项）；三闸顺序不可换 | ✅ |
| R5-08 | 日志与审计 | Should | 审计可追溯 | `ToolAuditLogTest`（7 项）；实测 `audit_backend=postgres, degraded=false` | ✅ |

**终端线 / 创新项**

| ID | 需求 | DoD | 验收证据 | 结论 |
|---|---|---|---|:---:|
| R-C05(预) | 执行视图-工具部分 | 展示工具调用记录与沙箱状态 | `ExecutionView.vue` 显示「真实调用记录」，`data_source='live'`；沙箱卡片显示 `docker / 真隔离` | ✅ |
| IN-06 | 工具契约治理 | semver + 影响分析 + 变更触发契约测试 | `schema_hash` 指纹 + `change_log` + `GET /tools/{name}/impact` + **契约基线门禁** + `ToolSchemaBaselineTest`（2 项） | ✅ |

---

## 4. 阶段 DoD 逐条验收

| DoD | 判定 |
|---|---|
| 工具可注册 / 可执行 / 可审计 | ✅ 三类调用（成功 / 守卫拦截 / 沙箱拒绝）结果与审计一致 |
| 恶意命令被拦截 | ✅ 守卫 12 条规则 + Docker 内核级隔离双保险 |
| 降级必须可见，不得静默伪造 | ✅ 沙箱 / 审计 / 注册表 / 决策四处降级均带 `degraded` / `backend` 标记 |
| 写路径鉴权 | ✅ `POST /api/wp/tools/execute` 经 BFF 与 `start/stop` 同级校验 |

---

## 5. 端到端执行实录

### 5.1 tool-executor 服务侧（真实请求）

```
GET  /api/tool/sandbox
     200  {enabled:true, active_backend:"docker", isolated:true, degraded:false,
           docker_available:true, process_fallback_available:true, timeout_ms:10000,
           note:"Docker 真隔离：网络禁用 / 只读 rootfs / 去能力 / 资源限"}

POST /api/tool/execute  {tool_name:"code", arguments:{code:"print(sum(range(10)))"}}
     200  success=true output="45" sandboxed=true sandbox_backend="docker" degraded=false
          latency_ms=3587 audit_id="audit-…-1"

POST /api/tool/execute  {tool_name:"calculator", arguments:{expr:"sqrt(2)+pow(2,10)"}}
     200  success=true output="1025.414213562373" latency_ms=3 audit_id="audit-…-4"

GET  /api/tool/audit/stats
     200  audit:{backend:"postgres", degraded:false, total:4, success:2, blocked:1, sandboxed:2}
          kafka:{truth_source:"postgres.tool_audit_log", role:"event-sidecar", available:true,
                 topic:"lifeform.tool.invoked"}

GET  /api/tool/metrics
     200  metrics:{window_size:4, total_calls:4, total_failures:2, blocked_calls:1,
                   p50_ms:3, p95_ms:3587, p99_ms:3587, calls_by_tool:{calculator:1, code:3}}
          circuit_breakers:[{tool_name:"code", consecutive_failures:2, state:"closed"}, …]
```

### 5.2 沙箱隔离逃逸样本（**Docker 真隔离，内核级证据**）

| # | 样本 | 预期 | 实测结果 | 判定 |
|---|---|---|---|:---:|
| 1 | `code`: `print(2**10)`（CLI 直调） | 正常执行 | 输出 `1024`，退出码 0 | ✅ |
| 2 | `code`: 出网 `urllib.request.urlopen('http://example.com')` | 被拦 | 服务侧 **403 `AGENT_TOOL_ARGS_BLOCKED`**（守卫规则 `urllib\.request`）；CLI 直调（跳过守卫）→ `socket.gaierror: [Errno -3] Temporary failure in name resolution` | ✅ **双保险成立** |
| 3 | `code`: 写 rootfs `open('/evil.txt','w')` | 被拦 | **400 `AGENT_TOOL_EXEC_FAILED`**，stderr `OSError: [Errno 30] Read-only file system: '/evil.txt'` | ✅ **只读 rootfs 生效** |
| 4 | `code`: `rm -rf /` | 被拦 | 命中 `AGENT_TOOL_ARGS_BLOCKED`（12 条敏感规则之一） | ✅ |
| 5 | `http`: 非白名单域名 | 被拒 | 被域名白名单 + SSRF 双闸拒绝 | ✅ |

> **关键结论**：样本 2 / 3 证明隔离**不止依赖守卫正则**——即使绕过守卫直接进沙箱，内核仍拒绝网络与写盘。
> 这是 R5-03「Docker 真隔离」的决定性证据（此前仅验证了降级后端）。

### 5.3 链路与审计一致性

```
wp-bff(8090) → tool-executor(8084) → Docker 沙箱 → PG tool_audit_log
  成功调用  → success=true  + audit 记 success
  守卫拦截  → 403 + error_code + audit 记 blocked（含命中规则）
  沙箱拒绝  → 400/非零退出 + stderr + audit 记 failed
  三类结果与 GET /api/tool/audit 统计（success=2 / blocked=1）逐条一致
```

---

## 6. 测试基线汇总

| 套件 | 基线（Phase 4 收口） | 现在 | 变化 |
|---|---|---|---|
| Java（5 模块） | 162 | **244** | gateway 2 · session **43** · sense 53 · body **64** · **tool-executor 82** |
| Python（nlp-service） | 124 | **139** | `test_function_calling.py` +15 |
| wp-bff | 55 | **73** | 工具域 9 端点用例 +18 |
| 前端 | typecheck ✅ | typecheck ✅ / build ✅ | 执行视图独立 chunk |
| 契约门禁 | 0 FAIL | **0 FAIL** | implemented 30 路径 / 31 方法；工具 schema 基线 3 = 注册 3 |
| 文档覆盖 | 113/113 | **157/157** | 新增 tool-executor 42 个文件已登记 |

> Java 244 = 162 + 82（tool-executor）；其中 tool-executor 内部：
> `ToolGuardTest` 16 · `ToolRegistryTest` 13 · `ToolExecutorTest` 10 · `GlobalExceptionHandlerTest` 9 ·
> `HttpToolTest` 8 · `ToolAuditLogTest` 7 · `CalculatorToolTest` 6 · `CodeToolTest` 6 ·
> `SandboxExecutorTest` 5 · `ToolSchemaBaselineTest` 2。

---

## 7. 缺陷与修复验证

| # | 缺陷 | 发现方式 | 修复 | 防复发 |
|---|---|---|---|---|
| 1 | 运行期 `docker_available=false`，沙箱降级 | 运行态：真调 8084 | 构建 `agent-sandbox:latest` 后重启；启动日志 `available=true` | `DockerSandboxBackend` 启动即探一次并**写日志**（不静默降级） |
| 2 | 前端 `parameters_schema` 声明为对象、真实为 JSON 字符串 | 双向字段核对 | 类型放宽 `string \| Record<string, unknown>` | 契约注释说明 |
| 3 | 契约错误码枚举缺工具域 6 码 | 契约专项核查 | 补齐枚举 + 工具域端点 404/405 | `contract-check.py` 门禁 |
| 4 | `GET /tools/{name}` 与 `/tools/{name}/impact` 前端无入口 | 联通矩阵核对 | 执行视图新增「影响面」抽屉 | 联通矩阵纳入复审清单 |
| 5 | `healthcheck.sh` 未覆盖 8084/9095/8090 | 脚本核查 | 补齐探针 | —（**未重跑**，如实标注） |

---

## 8. 待补验证项与超范围缺口（不影响本阶段结论）

### 8.1 待补验证（P1 已闭合，附口径说明）

| 项 | 说明 |
|---|---|
| `healthcheck.sh` 重跑 | 脚本已纳入 8084/9095/8090，但本轮**未整体重跑**（需全量容器起来）；口径：脚本改动正确、执行留待运维轮 |
| 工具指标接 Prometheus | 当前进程内滑动窗口（DEBT-021），无跨实例聚合 |
| 注册表持久化 | 进程内内存（DEBT-019），重启丢失；单实例假设 |

### 8.2 超范围缺口（**如实登记，待用户决策**）

| ID | 缺口 | 处置建议 |
|---|---|---|
| WB-06 | 连接器管理（C29 网关）：注册/授权/健康监控/启停 | 顺延 P6-P7 或单独立项 |
| WB-10 | 多模型管理面板：策略切换 + 成本可观测 | 同上 |
| RE-01~RE-04 | ADR-004 规则引擎局部回路 + `rule-loop-builder:8093` | 同上；RE-02 依赖 C29 与 tool-executor，先后关系需先确认 |
| P2-16 | 部署文档口径漂移（`HTTP_ALLOW_DOMAINS` 默认值 / 前端端口 `:3000` vs `:3001`） | **改文档需先问用户**（口径变更） |

---

## 9. 验收签字

| 角色 | 结论 |
|---|---|
| 开发执行 | 完成 R5-01~R5-08 + R-C05(预) + IN-06，测试全绿（Java 244 / Python 139 / wp-bff 73） |
| 自测验收 | **通过**；Must 7/7、Should 1/1、终端线达标；沙箱 Docker 真隔离已实测 |
| 遗留 | 超范围缺口 WB-06 / WB-10 / RE-01~RE-04 登记待决策；DEBT-019~021 待闭合；`healthcheck.sh` 未整体重跑 |

---

## 补记 A · 真实链路打通轮（2026-09-19，验收后）

> 本报告正文的口径定格在 Phase 5 收口提交。其后同日进行一轮「工作平台真实数据链路打通
> + 沙箱探测非阻塞化」修复，**改动了本轮验收范围内的代码**，故在此补记，不回改上文数字。

| 项 | 本报告口径 | 补记后口径 |
|---|---|---|
| Java 测试 | 244 / 0 失败（tool-executor 82） | **248 / 0 失败（tool-executor 86）** |
| 代码变更 | — | `sandbox/DockerSandboxBackend.java`（探测非阻塞重构）+ 新增 `sandbox/DockerSandboxBackendTest.java`（4 条） |
| 沙箱状态端点 | `docker_available=true`（5.7s 冷探测） | `docker_available=true`，**0.5s**，越过 15s TTL 亦稳定 |
| `GET /api/wp/tools` | `partial:true`, `["sandbox_unavailable"]` | **`partial:false`** |

**补记发现的缺陷（§3.2 归因）**：`DockerSandboxBackend.available()` 原先在**请求线程内同步**
执行 `docker info` + `docker image inspect`（Windows 冷探测 5.7s），超出上游 wp-bff
工具域子请求 **5.0s** 预算（`httpRequestJson` 默认值，`server.js:55`；**余量仅 0.7s**）
→ 工具域把"慢"表达为
`sandbox:null + partial:["sandbox_unavailable"]`。**这是本轮验收未覆盖的缺陷类型：
不影响任何功能断言，只在跨服务超时预算交界处静默降级。**

> **口径更正（同日复核）**：本节初稿写"2.5s 预算（`server.js:46`）"—— 那是另一个仅 GET 的
> 辅助函数 `httpGetJson`，与工具域无关。工具域走 `jsonRequest` → `httpRequestJson`，默认 5000ms。

完整证据链与修复说明见 `docs/优化日志/2026-09-19-工作平台真实链路打通与沙箱探测非阻塞化.md`。

---

## 补记 B · 超时语义区分与启动链路自检轮（2026-09-19，补记 A 之后）

> 针对补记 A 结尾留下的三条优化建议逐条落地。**再次改动了本轮验收范围内的代码**
> （wp-bff `server.js`、前端 `vite.config.ts` 与 `ExecutionView.vue`），故续记，同样不回改上文。

### B.1 缺陷：BFF 把「慢」和「没有」合并成同一个 `null`

补记 A 在服务侧根治了沙箱探测慢，但**上游表达层的问题仍在**：`httpRequestJson` 把所有失败
（超时 / 连不上 / 404 / JSON 坏）一律 `resolve(null)`，调用方只能看到 `null` ——
于是"超时"与"端点不存在"与"服务没起"在响应里长得一模一样。

**修复**：新增 `httpRequestJsonMeta()` 返回 `{ok, data, reason, status}`，
`reason ∈ {timeout, unreachable, endpoint_missing, http_error, bad_json}`；
老函数 `httpRequestJson` 保留为丢弃原因后的薄封装（**向后兼容，既有 73 条用例未改**）。

工具域聚合随之升级：
- 分片缺失码带后缀 —— `sandbox_timeout`（慢）/ `sandbox_missing`（版本旧）/ `sandbox_unavailable`（真连不上）
- 新增 `reason_code`（整体不可用时的机器码）、`partial_note`（人类可读解释）、
  `probe_budget_ms`（各子请求预算，**实测 `{default:5000, sandbox:8000}`**）
- 沙箱分片**单独放宽预算** —— 它是唯一会触发容器探测的子请求

**实测（三态各造一个探针验证，2026-09-19）**：

| 场景 | 构造方式 | `reason_code` |
|---|---|---|
| 对端未启动 | 工具域指向死端口 9999 | **`unreachable`** |
| 对端是旧版本 | 工具域指向会 404 的地址 | **`endpoint_missing`** |
| 对端慢应答 | 工具域指向 9s 慢桩 | **`timeout`** |
| 分片级慢（原始缺陷形状） | 清单秒回、分片拖 9s | `partial_reasons: ['registry_timeout','metrics_timeout','sandbox_timeout','audit_stats_timeout','audit_timeout']` + `partial_note` 明示"**不代表该能力缺失**" |

> 最后一行是关键 —— **修复前，这个场景会写成 `sandbox_unavailable`（"不可用"）**，
> 即原始缺陷的精确形状；现在如实报"慢"。

### B.2 前端把机器码翻成人话

`ExecutionView.vue` 新增 `PARTIAL_REASON_LABELS` 映射，"分片缺失"告警不再直接打印 raw code：
`sandbox_timeout` → 「沙箱状态读取超时（慢，非缺失）」。
`types/index.ts` 补 `reason_code` / `partial_note` / `probe_budget_ms` 三个字段。

### B.3 vite 代理目标自检（消除"静默满屏 Mock"）

`vite.config.ts` 新增 `bffReachabilityGate()` 插件：启动时探一次代理目标，**不可达就在终端
醒目告警**（含 URL 与 `WP_BFF_PORT` 提示）；每条目标只响一次，不刷屏。
`WP_BFF_PORT` 与 `WP_BFF_URL` 此前**互不校验** —— 端口一错，请求 500 被 `safe()` 兜住
并逐域登记降级，页面只剩"满屏演示数据 + 一堆降级"，从界面上根本看不出是环境问题。

### B.4 启动脚本补齐缺失服务 + healthcheck 自废武功修复

| 脚本 | 变更 |
|---|---|
| `scripts/start.sh` | 新增 `start_bff` / `start_frontend` 两步；**`WP_BFF_URL` 由 `WP_BFF_PORT` 单点派生**（消除两个变量漂移的根源）；`JAVA_SERVICES` 补 `tool-executor` |
| `scripts/start-dev.bat` | 同上（PowerShell/cmd 版对齐） |
| `scripts/start-test.sh` | `start_java` 补 `tool-executor` |
| `scripts/healthcheck.sh` | 补 8084 / 9095 / 8090 探针；**修 `set -e` 自杀缺陷**（见下） |

**healthcheck 的自废武功**：脚本用 `code=$(curl ...)`，而 curl 连不上会以非 0（如 7）退出；
命令替换里**赋值语句的退出码 = curl 的退出码**，配合 `set -e` 会让脚本在**第一个不可达服务处
直接终止** —— 即"服务挂掉时它报不出来"，恰恰是它存在的理由。实测 `exit=7`，java 段一行都没输出。
修法：`|| true` + `-o /dev/null`（原 `-o NUL` 依赖 Windows 设备名，Git Bash 下不可靠）。

**整体重跑结果（2026-09-19）**：**19 OK / 6 BAD**。BAD 全部是"本轮未启动"：
`gateway` / `sense-service` 未起（含各自 gRPC 9091/9093），`NATS` / `MinIO` 非 Phase 5 依赖。
**如实列出，不刷绿。**

### B.5 本轮基线

| 项 | 补记 A 后 | 补记 B 后 |
|---|---|---|
| wp-bff `node --test` | 73 | **81 / 0 失败**（+8：失败原因分类 × 5、分片缺码后缀 × 2、预算字段 × 1） |
| Java | 248 / 0 失败 | 248 / 0 失败（本轮未动 Java） |
| 契约（工作平台） | 0 FAIL | **0 FAIL**（60 端点，implemented 30 路径 / 31 方法） |
| 文档覆盖 | 158/158 | **158/158** |
| 前端 | typecheck + build 通过 | **typecheck + build 通过** |

完整过程见 `docs/优化日志/2026-09-19-优化建议落地-超时语义区分与启动链路自检.md`。

## 补记 C：GAP 收尾轮 —— 三条跨服务超时倒挂一次闭合（2026-09-19 晚）

用户指示「按优化建议处理执行任务」后，把登记表遗留的 GAP-01 / GAP-05 / GAP-06 一次闭合，
方向统一为**内层收敛、外层覆盖**。详见 `docs/优化日志/2026-09-19-GAP收尾-三条倒挂一次闭合与登记表失真修正.md`。

### C.1 变更摘要

| 项 | 变更 |
|---|---|
| GAP-01 | `BRAIN_TOTAL_BUDGET_MS` 25s → **20s**（收紧下游；原 25s > 级联最坏 22s，从不构成约束）。session `BRAIN_TIMEOUT` 30s 恰 1.5x |
| GAP-05 | body 新增 `common/BudgetGuard.java`，检索 warm 管线端到端预算 `RETRIEVAL_TOTAL_BUDGET_MS = 10s`（超时 504）；上游 `RETRIEVAL_BUDGET_MS` 8s → 15s；**废除第二套口径** `RETRIEVAL_TIMEOUT_SECONDS` |
| GAP-06 | wp-bff 新增 **ASK 档 53s**（只服务 session /ask 边）；GENERATE 档保持 45s |
| 登记表失真修正 | TB-16 / TB-12 原登记的"下游最坏 10s"实为 body 出站读超时 —— 真实管线 = 嵌入 12s + Qdrant 2s + 重排 10s；现以端到端预算常量为准，并补下游锚点 |
| REC-02 | `start.sh` preflight 已挂双门禁（仓内无 CI，CI 部分待建） |

### C.2 本轮基线（补记 C 后）

| 项 | 结果 |
|---|---|
| Java | **251 / 0 失败**（body 64→67：BudgetGuard 2 例 + 超时用例） |
| Python `pytest` | 158 passed / 1 skipped |
| wp-bff `node --test` | 87 / 0 失败（session ask 档位断言改写 + ASK>GENERATE 防退化断言） |
| 超时预算门禁 | **ok=19 · gap=0 · fail=0**（反向验证：植入锚点漂移 19999 → fail=1 / exit=1） |
| 契约（跨服务 / 工作平台） | 0 FAIL / 0 FAIL |
| 文档覆盖 | **160/160**（登记 `BudgetGuard.java`） |

### C.3 本轮揭出的写用例陷阱

stub 打错层即假绿：管线调用的是 `embedOne`，mock 拦截层**不会**走到其内部的 `embed`，
stub `embed` 会被静默跳过、管线拿默认空值照常跑完（预算 200ms、7ms 返回的假绿即此）。
stub 必须打在**实际被调用的那一层**。
