# 09 · tool-executor 四肢服务（Phase 5 四肢期）

> 模块路径：`services/java/tool-executor/`
> 源文件：**32 个主代码（3003 行）+ 11 个测试（1420 行 / 86 个用例，全绿）**
> HTTP 端口：**8084** · gRPC 端口：**9095**
> 外部依赖：PostgreSQL（审计真相源）· Kafka（审计事件旁路）· Docker（沙箱主路径）
> 阶段来源：Phase 5 四肢期 —— R5-01（注册表）/ R5-02（执行）/ R5-03（沙箱）/ R5-04（计算·HTTP）/ R5-05（代码执行）/ R5-07（安全闸）/ R5-08（审计），以及创新项 IN-06（工具契约治理）

## 1. 模块职责

`tool-executor` 是生命体的**四肢**：大脑（`session-manager` 的规划器）决定「做什么」，
本服务负责「**安全地做出来**」。它是全平台**唯一**允许执行外部动作（算数、出网、跑代码）的入口。

| 需求 | 能力 | 落点 |
|---|---|---|
| R5-01 | 工具注册表：注册 / 列表 / 详情 / 卸载 | `registry/ToolRegistry` + `registry/ToolBootstrap` |
| R5-02 | 执行引擎：校验 → 执行 → 超时熔断 → 结果标准化 | `exec/ToolExecutor` |
| R5-03 | 沙箱隔离：Docker 真隔离 + 受限子进程降级 | `sandbox/*` |
| R5-04 | 内置工具：计算器（无 eval）+ HTTP（域名白名单） | `tools/CalculatorTool`、`tools/HttpTool` |
| R5-05 | 代码执行：stdout/stderr 回传 + 危险操作拦截 | `tools/CodeTool` + `sandbox/SandboxExecutor` |
| R5-07 | 安全三闸：白名单 / 参数 Schema / 敏感参数 | `guard/ToolGuard` |
| R5-08 | 全量审计：工具 / 参数 / 结果 / 耗时 / 租户 | `audit/ToolAuditLog` + `audit/AuditEventPublisher` |
| IN-06 | 契约治理：semver + schema 指纹 + 变更影响分析 | `registry/ToolRegistry.impact()` + `registry/ImpactReport` |

对应工作平台**执行视图**（`web/work-platform/src/views/ExecutionView.vue`）：工具卡片、试运行抽屉、
沙箱状态灯、审计流水、熔断徽标、**影响面抽屉**（IN-06 前端入口）。

## 2. 执行链路（固定顺序，不可换）

```text
POST /api/tool/execute
  │
  ├─ ① 白名单        guard.checkWhitelist(name)      ← 不在名单 → AGENT_TOOL_NOT_ALLOWED（不触达执行器）
  ├─ ② 参数 Schema   guard.checkSchema(def, args)    ← Draft-07 校验 → AGENT_TOOL_ARGS_INVALID（带字段级 details）
  ├─ ③ 敏感参数      guard.checkSensitive(def, args) ← 全局 + 工具级双重扫描 → AGENT_TOOL_ARGS_BLOCKED
  ├─ ④ 熔断检查      breaker.open()?                 ← 打开中 → AGENT_TOOL_CIRCUIT_OPEN（快速失败）
  ├─ ⑤ 带超时执行    pool.submit(handler.execute).get(timeoutMs)   ← 超时 → AGENT_TIMEOUT + 计一次失败
  ├─ ⑥ 结果标准化    ToolOutcome → ToolCallResult     ← 失败也回传 stdout/stderr/exit_code（截断 2000 字）
  └─ ⑦ 收口          audit(必写) + metrics + Kafka 旁路
```

**两条不变量**（写进 `ToolExecutor` 类注释，并有单测守卫）：

1. **审计不丢**：无论成功、被拦、超时还是熔断，**都写一条审计**（R5-08「全量记录」）。
   因此 `ToolExecutor.execute()` **不抛异常**——失败也返回标准化结果，由 Controller 决定 HTTP 语义。
2. **超时不悬挂**：工具在独立守护线程池（固定 8 线程）执行并带硬超时；超时后 `future.cancel(true)`、
   计一次失败，达到阈值（默认 3）触发熔断，后续调用快速失败。

## 3. 与 Phase 1/2 的关系

Phase 5 之前平台**没有**执行层：`session-manager` 的规划器只能"说"要调用工具，
没有任何落点。本模块是**新增**服务（端口 8084 亦为本阶段新分配），不替换任何旧实现，
因此不存在兼容包袱；对上游只暴露一条无状态 HTTP 契约（`contracts/work-platform-bff-openapi.yaml` 的 `/tools` 段）。

## 4. 文件清单

### 4.1 主代码（32 个，3003 行）

| 文件 | 类型 | 行数 | 职责 |
|---|---|---:|---|
| `ToolExecutorApplication.java` | 启动类 | 24 | Spring Boot 入口 + Nacos 注册 |
| `common/ErrorCode.java` | 枚举 | 44 | 统一错误码（R1-08 对齐副本 + **7 个工具域专属码**） |
| `common/BizException.java` | 异常 | 33 | 携带错误码 + 明细，支持链式 `.with()` |
| `common/GlobalExceptionHandler.java` | 切面 | 98 | 错误码 → HTTP 响应体 + 路由层 404/405/415 分流 |
| `grpc/GrpcHealthServer.java` | 组件 | 37 | gRPC Health 探针（**9095**） |
| `model/ToolMeta.java` | record | 45 | 工具元数据（semver / JSON Schema / 超时 / 敏感正则 / 废弃期） |
| `model/ToolDefinition.java` | record | 8 | 注册表条目：元数据 + 实现绑定 |
| `model/ToolHandler.java` | 接口 | 17 | 工具实现契约（**不得**自行校验或审计） |
| `model/ToolContext.java` | record | 19 | 执行上下文（租户 / callId / 截止时间） |
| `model/ToolOutcome.java` | 值对象 | 61 | 工具原生结果（**容忍 null**，失败路径 stdout 常为 null） |
| `model/ToolCallResult.java` | 值对象 | 109 | 标准化结果（成功/失败**形状恒定**） |
| `registry/ToolRegistry.java` | 组件 | 217 | R5-01 注册表 + IN-06 版本治理 / **schema 指纹** / 影响分析 |
| `registry/ToolBootstrap.java` | 组件 | 70 | 启动时内置工具自注册 + 消费方登记（`ApplicationReadyEvent`） |
| `registry/ToolVersionRecord.java` | record | 29 | 一条版本变更记录 |
| `registry/ImpactReport.java` | record | 25 | 影响分析报告（受影响 Agent / 流程 / 是否需契约测试） |
| `guard/ToolGuard.java` | 组件 | 182 | R5-07 三闸 + **静态代码预检**（12 条规则） |
| `exec/ToolExecutor.java` | 组件 | 232 | R5-02 执行引擎 + 简单熔断器 + 失败细节回传 |
| `exec/ToolMetrics.java` | 组件 | 75 | 成功率 / P50·P95·P99 / 拦截计数（滑动窗口 500） |
| `sandbox/SandboxBackend.java` | 接口 | 15 | 沙箱后端契约（可插拔） |
| `sandbox/SandboxExecutor.java` | 组件 | 105 | 沙箱**唯一入口**：静态预检 → 选后端 → 执行 → 标降级 |
| `sandbox/DockerSandboxBackend.java` | 实现 | 161 | R5-03 主路径：Docker 真隔离（网络禁用 / 只读 rootfs / 去能力）+ **非阻塞探测** |
| `sandbox/RestrictedProcessBackend.java` | 实现 | 86 | **降级路径**（非隔离边界，恒 `degraded=true`） |
| `sandbox/ProcessRunner.java` | 工具 | 90 | 子进程执行：硬超时 + 并发排空 + 超时强杀 |
| `sandbox/SandboxSpec.java` | record | 29 | 沙箱请求（语言 / 代码 / 超时 / 内存 / 出网开关） |
| `sandbox/SandboxResult.java` | record | 33 | 沙箱结果（含 `degraded` / `rejected` / `backend`） |
| `audit/ToolAuditEntry.java` | record | 48 | 一条审计记录 |
| `audit/ToolAuditLog.java` | 组件 | 299 | R5-08 审计真相源（PG 表 + **内存降级** + 参数脱敏） |
| `audit/AuditEventPublisher.java` | 组件 | 78 | Kafka 事件旁路（**非真相源**，失败即降级不抛出） |
| `controller/ToolController.java` | 控制器 | 259 | 注册表 / 执行 / 审计 / 指标 / 沙箱 五组接口 |
| `tools/CalculatorTool.java` | 工具 | 248 | R5-04 算术（**手写递归下降解析，无 eval**） |
| `tools/HttpTool.java` | 工具 | 157 | R5-04 HTTP（域名白名单 + SSRF 内网拦截） |
| `tools/CodeTool.java` | 工具 | 70 | R5-05 代码执行（**一律经 `SandboxExecutor`**） |

### 4.2 测试（11 个文件，86 个用例，全绿）

| 文件 | 行数 | 用例 | 覆盖验收点 |
|---|---:|---:|---|
| `registry/ToolRegistryTest.java` | 191 | 13 | R5-01 注册/卸载幂等、IN-06 semver 判定 / schema 指纹 / 影响分析 |
| `registry/ToolSchemaBaselineTest.java` | 90 | 2 | **IN-06 契约基线**：内置工具 schema_hash 与 `contracts/tool-schema-baseline.json` 一致 |
| `guard/ToolGuardTest.java` | 180 | 16 | R5-07 三闸：白名单拒绝 / Schema 字段级错误 / 12 条危险模式 / SSRF |
| `exec/ToolExecutorTest.java` | 263 | 10 | R5-02 全序：成功 / 未注册 / 参数错 / 被拦 / 超时熔断 / **审计必写** |
| `sandbox/DockerSandboxBackendTest.java` | 109 | 4 | **探测性能契约**：慢探测不得阻塞调用方 / TTL 内不重探 / 禁用零成本 |
| `sandbox/SandboxExecutorTest.java` | 95 | 5 | R5-03 静态预检拦截 / 后端选择 / **降级必标 degraded** |
| `audit/ToolAuditLogTest.java` | 138 | 7 | R5-08 全量记录 / 租户隔离 / **参数脱敏** / PG 不可用降级 |
| `tools/CalculatorToolTest.java` | 73 | 6 | R5-04 运算优先级 / 函数 / 非法字符 / **无 eval** |
| `tools/CodeToolTest.java` | 94 | 6 | R5-05 stdout/stderr 回传 / 危险代码拦截 / 后端透传 |
| `tools/HttpToolTest.java` | 85 | 8 | R5-04 域名白名单 / 通配 `*.` / 内网地址拒绝 |
| `common/GlobalExceptionHandlerTest.java` | 102 | 9 | 错误码 → 状态码映射 / 路由层 404·405·415 / **真实故障仍 500** |

## 5. 逐文件说明（关键实现）

### 5.1 `guard/ToolGuard.java` · 安全三闸 · 182 行

**闸 1 白名单**：`TOOL_WHITELIST`（默认 `calculator,http,code`）。不在名单 → 抛
`AGENT_TOOL_NOT_ALLOWED`，**不触达执行器**（这是 R5-07 验收「白名单外拒绝」的落点）。

**闸 2 参数 Schema**：按工具 `parametersSchema`（JSON Schema **Draft-07**）逐字段校验，
失败返回**字段级 `details`**（`instanceLocation → message`），便于 LLM 自我修正后重试。

**闸 3 敏感参数**：全局 12 条危险模式 + 工具级 `sensitivePatterns` 双重扫描**字符串参数**，
命中即抛 `AGENT_TOOL_ARGS_BLOCKED` 并回传命中片段（截断 64 字）与命中的规则原文。

全局规则覆盖 R5-03 攻击样本集：文件删除（`rm -rf` / `shutil.rmtree` / `os.remove`）、
磁盘破坏（`mkfs` / `dd if=` / fork 炸弹）、关机重启、提权（`sudo` / `chmod 777` / `setcap`）、
敏感文件（`/etc/shadow` / `id_rsa` / `.aws/credentials`）、反弹 shell（`bash -i >&` / `nc -e` / `/dev/tcp/`）、
路径穿越（`../../`）、下载即执行（`curl … | sh`）、动态执行（`os.system` / `subprocess` / `Runtime.exec`）、
网络外联（`socket` / `urllib.request` / `requests.get`）。

另有 `INTERNAL_ADDRESS` 专用规则（SSRF）：`localhost` / `127.` / `10.` / `192.168.` / `172.16-31.` /
`169.254.`（云元数据）/ `metadata.google.internal`。

`staticCodeScan(code)` 是**沙箱前的第一道闸**，返回命中规则列表供 `SandboxExecutor` 决策。

> **选型留痕**：开发设计文档写 "JSON Schema (everit)"，实装为 networknt `json-schema-validator` ——
> 同为 Draft-07 校验器、能力等价，差异登记《技术债台账》**DEBT-020**。
> `schemaCache` 用 `computeIfAbsent` 缓存已解析 schema，避免每次调用重新解析。

### 5.2 `exec/ToolExecutor.java` · 执行引擎 · 232 行

顺序见 §2。三处关键取舍：

1. **熔断器计"业务失败"**：不只是超时，工具返回 `success=false` 也计一次连续失败——
   连续失败说明工具本身不可靠，而不是调用方运气差。
2. **被安全拦截也计失败**：`AGENT_TOOL_ARGS_BLOCKED` / `AGENT_SANDBOX_REJECTED` 走 WARN 日志并计入熔断，
   防止"被拦 → 重试 → 再被拦"的刷调用行为。
3. **失败路径必须回传沙箱事实**（`failureDetails()`）：`ToolOutcome.data()` 是 `Map<String,Object>`，
   而 `ToolCallResult.details` 是 `Map<String,String>`，此前直接丢弃 data，
   导致"沙箱执行返回非零退出码 1"成了调用方唯一能看到的线索（R5-02「错误友好」不达标）。
   现挑出 `exit_code` / `sandbox_backend` / `stdout` / `stderr` 四项，stdout/stderr 截断 2000 字。

### 5.3 `sandbox/SandboxExecutor.java` · 沙箱唯一入口 · 105 行

```text
静态预检（guard.staticCodeScan）── 命中 → rejected（AGENT_SANDBOX_REJECTED，未进入任何后端）
  └─ 通过 → 选后端：Docker available()? ── 是 → docker 真隔离（isolated=true, degraded=false）
                                        └─ 否 → 受限子进程（isolated=false, degraded=true）
       → 执行（硬超时）→ 超时 → 强制终止 + 熔断信号
```

**总开关**：`SANDBOX_ENABLED=false` 时直接抛 `AGENT_SANDBOX_REJECTED`（部署文档 §8 回滚路径）。

**降级不可隐瞒**：`execute()` 末尾有一处**强制补标** —— 只要后端 `isolated()==false`，
无论结果怎么构造，`degraded` 一律置 `true`。这是"受限子进程绝不冒充沙箱"的代码级保证。

`status()` 是执行视图「沙箱状态」的数据源，字段：`enabled / active_backend / isolated / degraded /
docker_available / process_fallback_available / timeout_ms / memory_mb / note`。

### 5.4 `sandbox/DockerSandboxBackend.java` · 真隔离 · 110 行

`docker run` 完整参数（R5-03「只读 rootfs + 网络禁用 + 资源限制」）：

```text
--rm -i
--network none                                  # 网络禁用（验收「网络访问被拦」）
--memory 256m --memory-swap 256m                # 内存硬限（禁 swap 规避）
--cpus 0.5                                      # CPU 限
--pids-limit 64                                 # 防 fork 炸弹
--read-only --tmpfs /tmp:rw,size=16m            # 只读 rootfs + 可写 tmpfs
--user 1000:1000                                # 非 root
--cap-drop ALL --security-opt no-new-privileges # 去能力 + 禁提权
agent-sandbox:latest python3 -
```

`available()` 探两件事：`docker info`（守护进程可达）**且** `docker image inspect <image>`（镜像已构建）。
两段探测结果缓存 **15 秒**（`PROBE_TTL_MS`），避免每次执行都浪费一个启动周期才降级。

镜像由 `infra/docker/sandbox/Dockerfile` 构建。**镜像未构建时本后端 `available()=false`**，
`SandboxExecutor` 自动落到受限子进程并如实标降级 —— 见 §8「当前实测状态」。

#### ⚠️ 探测必须非阻塞（2026-09-19 缺陷修复）

**先前的写法**（读起来完全合理，但有隐蔽缺陷）：`available()` 在缓存过期时**同步**跑 docker CLI。
实测在 Windows Docker Desktop 上 `docker info` + `image inspect` **冷探测需 5.7 秒**，
而上游 wp-bff 给工具域子请求的预算是 **5000ms**（`httpRequestJson` 的默认超时，
`services/node/wp-bff/server.js:55`；工具域聚合调用时未传 `timeoutMs`）。
**两者只差 0.7 秒** —— 任何一次负载抖动都会复发。于是：

```text
curl /api/tool/sandbox         → 200，但耗时 5.7s（服务侧其实"能答"）
BFF 内部请求（5.0s 预算）      → 超时 → 落 null
GET /api/wp/tools              → sandbox: null, partial: true, ["sandbox_unavailable"]
执行视图                       → 显示"沙箱不可用"（与实际相反）
```

> **口径更正（2026-09-19 复核）**：本节初稿写"上游 2500ms（`server.js:46` `httpGetJson`）"——
> 那是**另一个仅 GET 的辅助函数**，与工具域无关。工具域走 `jsonRequest` → `httpRequestJson`，
> 默认 5000ms。保留更正痕迹以免后人重蹈。另：修复后工具域的预算已显式化为
> `WP_BFF_TOOL_TIMEOUT_MS=5000` / `WP_BFF_TOOL_SANDBOX_TIMEOUT_MS=8000`（沙箱分片单独放宽）。

**一个"慢"被上游表达成了"没有"** —— 而巧合的是，它在 Phase 5 早期确实对应
"镜像未构建"，所以错误结论看起来一直是对的，直到镜像构建完成后仍继续误报才暴露。

**现在的契约**：

| 行为 | 时机 | 说明 |
|---|---|---|
| 同步探测 | **仅启动一次** | 构造器内执行，使启动日志与首个请求都拿到真结论 |
| `available()` | 任意时刻 | **恒 O(1)** 返回缓存值；缓存过期时<b>投递后台线程</b>刷新后立即返回 |
| 门闩 `refreshing` | 并发查询 | 同一时刻至多一个刷新在飞，避免探针风暴 |

回归守卫：`sandbox/DockerSandboxBackendTest`（4 条）——「慢探测不得阻塞调用方」
（注入 800ms 慢探测 + TTL=0 强制走刷新分支，断言 `available()` < 300ms）、
「TTL 内不重复探测」、「禁用分支零探测成本」。
**这类"性能契约"必须显式断言**：它不会让任何功能用例失败，只会让上游悄悄降级。

### 5.5 `sandbox/RestrictedProcessBackend.java` · 降级路径 · 86 行

**诚实定性**（类注释原文）：本后端**做不到**真正的隔离——无法 rootfs 只读、无法真正断网、
无法限制 pids。它只提供三件事：

1. 代码不内联在 shell 里（写临时目录后以 `python -` 独立进程执行）；
2. 工作目录隔离到每次调用独有的临时目录，`finally` 中尽力清理；
3. 硬超时 + 强制终止（与 Docker 后端**共用同一套 `ProcessRunner`**，保证超时语义一致）。

其结果**恒带 `degraded=true`**，视图层据此显示降级徽标。

### 5.6 `sandbox/ProcessRunner.java` · 子进程助手 · 90 行

带硬超时、**并发排空 stdout/stderr**（避免管道写满导致子进程假死——这是子进程执行的经典坑）、
超时后 `destroyForcibly()`。`Outcome` 记录 `exitCode / stdout / stderr / timedOut / elapsedMs / error`，
`ok()` 的判定是「未超时 且 无错误 且 exitCode==0」。沙箱与降级后端共用，避免两处各写一份超时逻辑。

### 5.7 `registry/ToolRegistry.java` · 注册表 + 契约治理 · 217 行

**存储口径（如实）**：注册表本体为**进程内注册 + 启动时内置工具自注册**；版本与变更历史同为进程内。
设计文档写 "etcd/内存"，本阶段取「**内存**」一侧 —— **未接 etcd**，跨实例共享与持久化登记 **DEBT-019**。
`GET /api/tool/list` 与 `/health` 都如实回传 `registry_backend=in-memory`，不假装是持久化注册中心。

**IN-06 三件事**：

1. **semver 版本化**：每次注册记录 `name@version` + schema 指纹。重复注册自动判定动作：
   指纹变了 → `SCHEMA_CHANGE`；指纹未变但版本变了 → `VERSION_UPGRADE`；两者都没变 → `REREGISTER`。
2. **变更影响分析**：`registerConsumer(tool, agentId, flowId)` 显式登记消费方，
   `impact(name)` 据此给出**确定的**受影响清单（不猜测）。
3. **契约测试触发**：schema 变更且存在消费方时，`ImpactReport.contractTestRequired=true`。

**schema 指纹** `fingerprint(schema)`：SHA-256 前 16 位十六进制，是「schema 是否变了」的稳定判据，
同时被 `ToolSchemaBaselineTest` 与 `scripts/contract-check.py` 消费（见 §6）。

### 5.8 `registry/ToolBootstrap.java` · 内置工具注册 · 70 行

`ApplicationReadyEvent` 时注册三个内置工具，并登记**声明过的消费方**：

| 工具 | 版本 | 超时 | 沙箱 | 登记消费方 |
|---|---|---:|---|---|
| `calculator` | 1.0.0 | 3000ms | 否 | `brain-planner`（Agent）/ `combo-task`（流程） |
| `http` | 1.0.0 | 10000ms | 否 | `brain-planner` / `combo-task` |
| `code` | 1.0.0 | 12000ms | **是** | `code-assist`（Agent + 流程） |

> **已知边界（DEBT-019）**：若日后消费方改用别的工具，**须同步改这里**，否则影响分析会漏报。
> 类注释明确写出这一点，避免被误读为"影响分析能自动发现所有调用方"。

### 5.9 `audit/ToolAuditLog.java` · 审计真相源 · 299 行

**真相源是 PG 表 `tool_audit_log`**（`CREATE TABLE IF NOT EXISTS` + 租户索引自举，不引 Flyway）。
Kafka 仅事件旁路 —— 这是有意的：**审计不能因为总线抖动而丢**。

分级口径（诚实）：

| backend | 条件 | 含义 |
|---|---|---|
| `postgres` | PG 可达 | 审计真落库（真相源） |
| `memory` | PG 不可达 | 落进程内环形缓冲（默认容量 500，**重启即失**），响应 `degraded=true` |

**写路径**：`record()` 每次调用都写（不论成功/被拦/超时）。落库异常 → 自动转内存并置 `degraded`，
**不静默假装落库成功**。`dropped` 计数如实上报被环形缓冲挤掉的条数。

**参数脱敏**：`summarize()` 对 key 含 `token / secret / password / key / credential` 的字段
只留长度（`***(N chars)`），避免审计库本身成为泄露面。

**「拦截」口径（`isBlocked`）**：只有**白名单拒绝 / 敏感参数命中 / 沙箱拒绝**三种算安全拦截；
**参数格式错（`AGENT_TOOL_ARGS_INVALID`）不算拦截**——那是调用方用错了，不是安全事件。
PG 侧 `stats()` 的 SQL 与内存侧 `isBlocked()` **口径同源**，两处不能各写一套。

### 5.10 `exec/ToolMetrics.java` · 指标 · 75 行

滑动窗口保留最近 **500** 次调用样本，给出 `success_rate / p50_ms / p95_ms / p99_ms /
total_calls / total_failures / blocked_calls / calls_by_tool`。
支撑 DoD「工具成功率 ≥ 90%」「执行 P99 < 2s」的量化验收。
**未接 Prometheus**，当前只对视图/报表供数（《技术债台账》**DEBT-021**）。

### 5.11 `tools/CalculatorTool.java` · 计算器 · 248 行

**不使用任何 eval / 脚本引擎** —— 手写**递归下降解析器**。理由（类注释原文）：
计算器是 LLM 最常触发的工具，一旦走 `eval` 或 `nashorn`，等于把「参数校验」这道闸整个绕过
（表达式本身就是代码）。本实现只认白名单运算符与函数，其余字符一律词法报错。

支持 `+ - * / % ^`、括号、一元正负，以及 `sqrt abs min max round floor ceil pow sin cos tan log log10 exp`。
返回 data 含 `expr / value / integer / int_value`。

### 5.12 `tools/HttpTool.java` · HTTP 工具 · 157 行

**两道闸**（顺序：先 SSRF 再白名单 —— 避免白名单里误配内网域也能放行）：

1. `guard.checkNotInternalAddress(url)`：拒绝 localhost / 127. / 10. / 172.16-31. / 192.168. / 169.254.（云元数据）/ metadata；
2. **域名白名单** `HTTP_ALLOW_DOMAINS`（默认 `api.open-meteo.com,httpbin.org,api.github.com`），
   支持精确域与 `*.suffix` 通配。命中失败 → `AGENT_TOOL_ARGS_BLOCKED`。

响应体按 `HTTP_MAX_BODY_BYTES`（默认 8192）**截断**，避免大响应灌爆 LLM 上下文；
返回 data 含 `status / url / method / content_type / body / truncated`。

### 5.13 `tools/CodeTool.java` · 代码执行 · 70 行

**一律经 `SandboxExecutor`**，工具自身不做任何执行、不做任何校验。
把沙箱结果标准化并**透传** `sandbox_backend` / `sandbox_degraded` / `rejected`，
让「这次到底跑在哪」对调用方**可观测**（这是"降级不隐瞒"在工具层的落点）。

失败语义：`rejected=true` → `AGENT_SANDBOX_REJECTED`；`!success` → `AGENT_TOOL_EXEC_FAILED`
（消息优先用沙箱 note，否则回退"非零退出码 N"）。

### 5.14 `controller/ToolController.java` · 控制器 · 259 行

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/tool/list` | 工具注册表清单（含 `schema_hash` / 熔断状态 / `registry_backend`） |
| `GET` | `/api/tool/registry` | 注册表摘要 + 变更历史 + semver/废弃期策略 |
| `GET` | `/api/tool/{name}` | 单工具详情（含 JSON Schema 原文与指纹） |
| `GET` | `/api/tool/{name}/impact` | **IN-06 变更影响分析**（受影响 Agent/流程、是否需契约测试） |
| `DELETE` | `/api/tool/{name}` | 卸载工具（R5-01 验收：可卸载） |
| `POST` | `/api/tool/{name}/deprecate` | 标记废弃（默认废弃期 30 天：`2026-09-19 → 2026-10-19`） |
| `POST` | `/api/tool/execute` | **执行工具**（R5-02） |
| `GET` | `/api/tool/audit` | 审计明细（租户隔离，limit 上限 500） |
| `GET` | `/api/tool/audit/stats` | 审计汇总 + Kafka 旁路健康 |
| `GET` | `/api/tool/metrics` | 成功率 / P50·P95·P99 / 各工具熔断状态 |
| `GET` | `/api/tool/sandbox` | 沙箱状态（R-C05 执行视图数据源） |
| `GET` | `/api/tool/health` | 存活检查（含工具数 / 审计 backend / 沙箱状态） |

**执行失败时**：走统一错误信封 + **真实状态码**，`details` 额外带 `call_id` / `audit_id` / `latency_ms`，
使调用方一次就能拿到足够的排障信息。

**租户来源**：网关注入的 `X-Tenant-Id`，**不信任请求体里的租户字段**。

### 5.15 `common/GlobalExceptionHandler.java` · 统一异常出口 · 98 行

响应体 `{code, message, details}`，与 `body-service` / `sense-service` / `session-manager` **同源形状**。

工具域状态码映射（**注意与通用码的差异**）：

| 错误码 | HTTP |
|---|---|
| `AGENT_TOOL_NOT_ALLOWED` / `AGENT_TOOL_ARGS_BLOCKED` / `AGENT_SANDBOX_REJECTED` / `AGENT_FORBIDDEN` | **403** |
| `AGENT_TOOL_ARGS_INVALID` / `AGENT_BAD_REQUEST` | 400 |
| `AGENT_NOT_FOUND` | 404 |
| `AGENT_METHOD_NOT_ALLOWED` | 405 |
| `AGENT_TIMEOUT` | 504 |
| `AGENT_UPSTREAM_UNAVAILABLE` | 502 |
| `AGENT_BUS_UNAVAILABLE` | 503 |
| 兜底 | 500 `AGENT_INTERNAL_ERROR`（只记日志，不回传内部消息） |

> 语义要点：**「被拒 ≠ 服务不可用」**。前三个码都是**已落审计的真实调用结果**（403），
> 前端据此可以区分「这次调用被安全策略拒绝了」与「服务/网关坏了」。
> 「未映射路由 → 404」这条也不能少，否则 planned 端点会被兜底吞成 500。

## 6. IN-06 契约基线（P1-08 闭合）

「schema 变更触发 L1 契约测试」此前只是 `ImpactReport` 上的一个**标记位**，契约门禁并不消费它。
2026-09-19 补齐闭环：

| 环节 | 落点 |
|---|---|
| 基线文件 | `contracts/tool-schema-baseline.json`（工具名 → `version` + `schema_hash`） |
| 单测守卫 | `registry/ToolSchemaBaselineTest`（2 项）：内置工具指纹必须与基线一致 |
| 门禁消费 | `scripts/contract-check.py`：§ 工具 schema 段比对基线，不一致 → **FAIL** |

因此「改了工具 schema 却忘了更新契约基线」现在会**直接红**，而不是靠人记得去看 `contract_test_required`。

## 7. 降级清单（诚实上报，不虚构）

| 组件 | 不可用表现 | 上报方式 |
|---|---|---|
| Docker / 沙箱镜像 | 落到受限子进程 | `sandbox_backend=process-restricted` + `degraded=true` + 视图降级徽标 |
| 沙箱总开关关闭 | 代码执行直接拒绝 | `AGENT_SANDBOX_REJECTED`（404? 不，403；见 §5.15） |
| PostgreSQL | 审计落进程内环形缓冲 | `audit_backend=memory` + `degraded=true` + `dropped` 计数 |
| Kafka | 审计事件旁路关闭 | `kafka.available=false`；**不影响 PG 落库** |
| 注册表持久化 | 无 etcd，仅进程内 | `registry_backend=in-memory`（DEBT-019） |
| Prometheus 指标 | 仅进程内滑动窗口 | `metrics` 仅视图可读（DEBT-021） |
| 影响分析消费方 | 只覆盖**显式登记**的 Agent/流程 | `ImpactReport.note` 明确写出"硬编码调用方不会出现在此列表中" |

## 8. 配置项（`application.yml`）

| 配置 | 默认 | 说明 |
|---|---|---|
| `server.port` / `grpc.port` | 8084 / 9095 | HTTP / gRPC |
| `app.tool.whitelist` | `calculator,http,code` | R5-07 工具白名单（`TOOL_WHITELIST`） |
| `app.tool.circuit.failure-threshold` / `open-ms` | 3 / 30000 | 熔断阈值与打开时长 |
| `app.tool.http.allow-domains` | `api.open-meteo.com,httpbin.org,api.github.com` | HTTP 域名白名单 |
| `app.tool.http.max-body-bytes` | 8192 | 响应体截断 |
| `app.tool.sandbox.enabled` / `timeout-ms` / `memory-mb` | true / 10000 / 256 | 沙箱总开关与默认限额 |
| `app.tool.sandbox.docker.enabled` / `image` / `python` | true / `agent-sandbox:latest` / `python3` | Docker 后端 |
| `app.tool.sandbox.process.fallback-enabled` / `python-bin` | true / `python` | 降级后端 |
| `app.tool.audit.memory-capacity` | 500 | 内存审计环形缓冲容量 |
| `app.tool.audit.kafka-enabled` / `kafka-bootstrap` / `kafka-topic` | true / `127.0.0.1:9092` / `lifeform.tool.invoked` | 审计事件旁路 |
| `spring.cloud.nacos.discovery.register-enabled` | `${NACOS_ENABLED:false}` | **单体本地开发关、集成环境开**（见 §9） |
| `spring.datasource.url/username/password` | `jdbc:postgresql://127.0.0.1:5432/lifeform` / `agent` / `agent123` | 审计库 |
| `management.otlp.tracing.endpoint` | `http://localhost:4318/v1/traces` | 链路追踪 |

> 运行注意：本机（WorkBuddy 环境）注入的代理环境变量会被 Spring 宽松绑定误解析为端口。
> **启动时显式传 `--server.port=8084`**（或先 `env -u SERVER__PORT -u SERVER__HOST`）可绕开。

## 9. Nacos 注册口径（P1-11 明确）

`register-enabled` 默认 **false**，这是**有意**的，不是遗漏：

| 场景 | 取值 | 理由 |
|---|---|---|
| 本机单体开发 / 单测 / CI | `false` | 无 Nacos 也能完整运行与验证，降低环境门槛 |
| 集成环境 / 部署（`docker-compose` 全量） | `true`（`NACOS_ENABLED=true`） | 供网关按服务名发现 |

部署文档 §3 要求「Nacos 注册 (8084)」指的是**集成环境口径**；本地看到 `register-enabled=false`
不是配置漂移，而是当前场景正确取值。

## 10. 修改指引

| 需求 | 改动位置 |
|---|---|
| 新增内置工具 | 实现 `ToolHandler` → `ToolBootstrap.registerBuiltins()` 注册 → **更新 `contracts/tool-schema-baseline.json`**（否则契约门禁红） |
| 调整白名单 | `application.yml` 的 `app.tool.whitelist`（无需改码） |
| 新增敏感拦截规则 | `ToolGuard.GLOBAL_BLOCKED`（**同时看 `ToolGuardTest` 的规则计数断言**） |
| 调整熔断阈值 | `app.tool.circuit.*` |
| 换 JSON Schema 校验器 | `ToolGuard` 的 `schemaFactory`（当前 networknt，DEBT-020） |
| 让沙箱真隔离生效 | **构建镜像** `docker build -t agent-sandbox:latest infra/docker/sandbox/`，再重跑逃逸样本 |
| 接入 Prometheus | `ToolMetrics`（当前仅进程内窗口，DEBT-021） |
| 接入 etcd 持久化注册表 | `ToolRegistry` 的 `definitions` / `history`（当前内存，DEBT-019） |
| 新增错误码 | `common/ErrorCode` + `GlobalExceptionHandler` 的 switch + `contracts/*-openapi.yaml` 的 `ErrorEnvelope.code.enum` |

## 11. 接口调用示例

```bash
# 注册表清单
curl http://127.0.0.1:8084/api/tool/list

# 执行计算器
curl -X POST http://127.0.0.1:8084/api/tool/execute \
  -H "Content-Type: application/json" -H "X-Tenant-Id: demo" \
  -d '{"tool_name":"calculator","arguments":{"expr":"sqrt(2)+pow(2,10)"}}'

# 执行代码（沙箱）
curl -X POST http://127.0.0.1:8084/api/tool/execute \
  -H "Content-Type: application/json" -H "X-Tenant-Id: demo" \
  -d '{"tool_name":"code","arguments":{"code":"print(\"hello\")"}}'

# 被安全拦截（预期 403 + AGENT_TOOL_ARGS_BLOCKED，且已落审计）
curl -X POST http://127.0.0.1:8084/api/tool/execute \
  -H "Content-Type: application/json" -H "X-Tenant-Id: demo" \
  -d '{"tool_name":"code","arguments":{"code":"import shutil; shutil.rmtree(\"/\")"}}'

# 影响面（IN-06，执行视图「查看影响面」抽屉的数据源）
curl http://127.0.0.1:8084/api/tool/code/impact

# 沙箱状态
curl http://127.0.0.1:8084/api/tool/sandbox

# 审计流水
curl "http://127.0.0.1:8084/api/tool/audit?limit=20" -H "X-Tenant-Id: demo"
```

经网关访问时把主机换成 `http://127.0.0.1:8080`（路由 `/api/tool/**`），
并且**必须带 `Authorization: Bearer <token>`** —— 网关会校验令牌并覆写 `X-Tenant-Id`。
