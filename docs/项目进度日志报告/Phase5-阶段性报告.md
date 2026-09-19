# Phase 5 四肢期 · 阶段性报告

| 项 | 内容 |
|---|---|
| 阶段 | Phase 5 · 四肢期（Limb Stage，执行能力） |
| 项目 | Agent-Lifeform · AI Agent 生命体架构 |
| 仓库 | `E:\AI\ai-agent`（开发分支 `dev`，跟踪 `origin/workbuddy/main`） |
| 分支 | `dev` / `codex/main` / `workbuddy/main`（三分支同内容对齐） |
| 报告日期 | 2026-09-19 |
| 代码基线 | `0921345`（Phase 5 代码收口）+ 复审收尾轮（文档与门禁） |
| 需求范围 | R5-01 ~ R5-08 + 终端线 R-C05(预) + IN-06 |
| 阶段结论 | **通过**（Must 7/7 · Should 1/1 · 终端线达标；沙箱 Docker 真隔离已实测，服务侧降级口径如实登记） |

---

## 1. 阶段目标回顾

Phase 4（大脑期）让生命体**会思考**：能规划、能检索、能生成、能标注来源。但它**只会说，不会做**——
所有能力都锁在"读 + 想"的闭环里，无法真正调用外部工具、执行代码、访问网络。

Phase 5 的目标是**长出四肢**：让大脑的决策可以落到真实动作上，并且这个动作必须是
**可控的、可审计的、跑不掉的**。设计文档定义的链路（《阶段性需求拆解分析》Phase 5 表）：

```
意图 → 规划（大脑） → 工具选择 → 白名单校验 → 参数 Schema 校验 → 敏感参数扫描
     → 沙箱执行（隔离） → 结果标准化 → 审计落库 → 回执
```

关键约束：**工具一旦执行，必须留下不可篡改的痕迹**（审计真相源）；**隔离失败必须可见**（不冒充沙箱）。

---

## 2. 交付物清单

### 2.1 Java 新服务 `tool-executor`（32 主 / 10 测，2952 + 1311 行）

| 包 | 文件 | 对应需求 |
|---|---|---|
| `registry/` | `ToolRegistry` · `ToolBootstrap` · `ToolVersionRecord` · `ImpactReport` | R5-01 注册表 + IN-06 版本化/影响分析 |
| `guard/` | `ToolGuard`（白名单 → Draft-07 Schema → 12 条敏感规则三闸，顺序不可换） | R5-07 |
| `exec/` | `ToolExecutor` · `ToolMetrics`（滑动窗口 + 熔断 3 次/30s） | R5-02 |
| `sandbox/` | `SandboxExecutor` · `DockerSandboxBackend`（真隔离）· `RestrictedProcessBackend`（显式降级）· `ProcessRunner`（硬超时）· `SandboxSpec/Result/Backend` | R5-03 / R5-05 |
| `tools/` | `CalculatorTool`（手写解析器，**无 eval**）· `HttpTool`（域名白名单 + SSRF 双闸）· `CodeTool`（一律经沙箱） | R5-04 / R5-05 |
| `audit/` | `ToolAuditLog`（PG 真相源 + 内存降级）· `AuditEventPublisher`（Kafka 旁路 `lifeform.tool.invoked`） | R5-08 |
| `controller/` | `ToolController`（12 个端点） | 对外接口 |
| `common/` | `ErrorCode`（通用 11 + 工具域 6）· `BizException` · `GlobalExceptionHandler`（含路由层 404/405/415 分流） | 错误语义 |

端点：`GET /api/tool/{list,registry,{name},audit,audit/stats,metrics,sandbox,health}`、
`DELETE /api/tool/{name}`、`POST /api/tool/{name}/deprecate`、`GET /api/tool/{name}/impact`、`POST /api/tool/execute`。

### 2.2 Python nlp-service（Function Calling 适配）

| 交付物 | 行数 | 对应需求 |
|---|---|---|
| `app/tools/function_calling.py` | 351 | R5-06 把工具 schema 转成 LLM function-calling 描述；诚实标注 `decider="rule"` |
| `tests/test_function_calling.py` | 148 | 15 项 |

### 2.3 wp-bff（工具域代理，R-C05 预）

`GET /api/wp/tools`、`/tools/registry`、`/tools/audit`、`/tools/audit/stats`、`/tools/metrics`、`/tools/sandbox`；
`POST /api/wp/tools/execute`（**写路径，与 `start/stop` 同级鉴权**）。契约 7 路径由 `planned` 改 `implemented`。

### 2.4 终端线

| 端 | 交付 |
|---|---|
| work-platform（R-C05 预） | `views/ExecutionView.vue`（495 行）：工具清单 / 注册表变更史 / 指标 / 沙箱状态 / 审计记录 / **IN-06 影响面抽屉** / 工具试执行 |
| 数据源 | `provider.ts` 新增 `getExecution()` / `executeTool()` / `getToolImpact()`；Mock 与真实 API 同构 |

### 2.5 契约与基础设施

- `contracts/tool-schema-baseline.json` —— 工具 schema 基线（3 内置工具），纳入 `contract-check.py --work-platform` 门禁
- `contracts/work-platform-bff-openapi.yaml` —— 工具域 7 路径 + 工具域错误码枚举 + 404/405 声明
- `infra/docker/sandbox/Dockerfile` + `README.md` —— `agent-sandbox:latest` 镜像（只读 rootfs / 无网络 / 去能力 / 非 root / 资源限）
- `scripts/healthcheck.sh` —— 纳入 `8084 tool-executor`、`9095 tool-grpc`、`8090 wp-bff`

---

## 3. 需求达成情况

| ID | 需求 | 结论 | 关键实测 |
|---|---|:---:|---|
| R5-01 | 工具注册表（注册/列出/卸载） | ✅ | `summary.tool_count=3`、`change_log` 3 条 |
| R5-02 | 执行引擎（错误友好 + 超时熔断 <5s） | ✅ | 默认超时受控；熔断 3 次 → 打开 30s（`AGENT_TOOL_CIRCUIT_OPEN`） |
| R5-03 | 沙箱隔离（恶意命令被拦） | ✅ | **Docker 真隔离实测**（服务侧 `active_backend=docker, isolated=true, degraded=false`）：出网被守卫拦 `403`、写 rootfs 被内核拒 `Errno 30`；守卫侧 12 条规则 |
| R5-04 | 内置工具：计算 / HTTP | ✅ | calculator `sqrt(2)+pow(2,10)=86.48`；http 非白名单域名被拒 |
| R5-05 | 内置工具：代码执行 | ✅ | `code` 打印成功；`rm -rf` 被 `AGENT_TOOL_ARGS_BLOCKED` 拦 |
| R5-06 | Function Calling 组合任务 | ✅ | `function_calling.py` 真调 tool-executor；`decider="rule"` 如实标注 |
| R5-07 | 白名单与参数校验 | ✅ | 白名单外 → 403；参数非法 → 400；顺序不可换 |
| R5-08 | 日志与审计（Should） | ✅ | `audit_backend=postgres, degraded=false`；Kafka 旁路 |
| R-C05(预) | 执行视图-工具部分 | ✅ | 界面显示「真实调用记录」，`data_source='live'` |
| IN-06 | 工具契约治理 | ✅ | semver + schema 指纹 + 影响面分析 + 契约基线门禁 |

---

## 4. 架构演进

```
Phase 4：问题 → 意图 → 规划 → 检索 → 覆盖度 → 生成 → 来源标注 → 回答（只会说）
Phase 5：… → 工具选择 → 三闸校验 → 沙箱执行 → 结果标准化 → 审计落库 → 回执（开始会做）
                                  ↑ 白名单/Schema/敏感规则        ↑ PG 真相源 + Kafka 旁路
```

新增的"四肢组件"落点：

- **组件 8 工具执行器**：`ToolExecutor` + `ToolGuard` + `SandboxExecutor`（本阶段主体）。
- **双手分工**：`HttpTool` 对应"手"（对外触达），`CodeTool` 对应"脚"（落地动作），`CalculatorTool` 为确定性基准工具。
- **隔离边界**：Docker 主路径（真隔离）+ 受限子进程（诚实的次优路径，`degraded=true`）。

---

## 5. 技术债与口径差异（均已在《技术债台账》留痕）

| 项 | 设计口径 | 实现口径 | 性质 |
|---|---|---|---|
| 工具注册表持久化 | 未强制 | 进程内内存（重启丢失） | DEBT-019 |
| JSON Schema 校验器 | everit | **networknt**（Draft-07） | DEBT-020 选型差异 |
| 工具指标 | 未规定后端 | 进程内滑动窗口（500 样本），**未接 Prometheus** | DEBT-021 |
| 沙箱隔离 | "Docker 强制隔离" | Docker 主路径 + **受限子进程显式降级** | 工程取舍，降级可见 |
| 工具决策 | 期望模型决策 | 规则决策并标 `decider="rule"` | 诚实标注，非伪实现 |

新登记：**DEBT-019**（注册表仅进程内）、**DEBT-020**（校验器选型差异）、**DEBT-021**（指标未接 Prometheus）。

**超范围缺口（如实登记，未在本阶段实现）**：WB-06 连接器管理、WB-10 多模型管理面板、
RE-01~RE-04（ADR-004 规则引擎局部回路 + `rule-loop-builder:8093`）—— 需求文档列有验收标准但不在 R5 主线，
建议顺延至 P6-P7 或单独立项，见复审缺口清单 §5-P2。

---

## 6. 经验与下一步

1. **"降级可见"是本阶段的最高频约束**：沙箱、审计、注册表、决策四处都存在"次优路径"，
   每一处都必须在响应里显式标注 `degraded` / `backend`，否则就等于把降级伪装成成功——
   这正是复审重点核查的红线。
2. **隔离验证要打到真实内核行为**：只看"守卫返回 403"不足以证明隔离，必须真跑出网/写盘样本，
   拿到 `Errno -3` / `Errno 30` 这样的系统级证据。
3. **契约要能约束 schema 变更**：IN-06 的价值不在"有影响面接口"，而在**基线比对进 CI 门禁**——
   改了工具 schema 却没同步基线，门禁必须红。
4. **下一步（Phase 6 小脑期）**：多工具编排与任务图；在此之前建议先闭合 DEBT-019（注册表持久化）与
   DEBT-021（指标接 Prometheus），让单实例假设不再成为编排的前置债。

---

## 补记（2026-09-19 · 复审收口轮）

> 本节由**阶段交付复审**触发。复审结论：**代码质量可交付，交付物不完整**——
> 缺的不是功能，是**档案与验证闭环**。本补记为收口记录。

### 补记 A · 复审发现的 P0 缺口与处置

| # | 缺口 | 处置 |
|---|---|---|
| P0-01 | Phase 5 三件套 + DEMO 全缺 | ✅ 本轮补齐（本文件 + 执行日志 + 测试验收报告 + DEMO） |
| P0-02 | 无本阶段优化日志 | ✅ `docs/优化日志/2026-09-19-Phase5四肢期进度复审与缺口清单.md` |
| P0-03 | `tool-executor` 全仓文档零提及 | ✅ 新增 `docs/java-services/09-tool-executor.md` + 登记进 `01-模块总览` |
| P0-04 | 文档覆盖门禁红灯（118/156） | ✅ 补齐登记 → **157/157** 全绿 |
| P0-05 | 全局文档未更新 | ✅ `PROGRESS.md` / `项目进度总览` / `功能开发流程` / 两份 README / `技术债台账` 全部对齐 |
| P0-06 | 技术债登记率跌破 100% | ✅ 台账补 DEBT-019~021 + §7 代码标记索引 |

### 补记 B · 复审发现的 P1 缺口与处置

| # | 缺口 | 处置 |
|---|---|---|
| P1-06 | 沙箱真隔离未验证 | ✅ 本轮 **Docker 真隔离实测通过**（见《测试验收报告》§5.2） |
| P1-07 | IN-06 影响分析前端无入口 | ✅ 执行视图新增「影响面」抽屉（`getToolImpact()`） |
| P1-08 | schema 变更未触发契约测试 | ✅ 契约基线 `tool-schema-baseline.json` 纳入门禁（条数 + 指纹比对） |
| P1-09 | 契约错误码枚举未同步 | ✅ 补齐 6 个工具域码 + 工具域端点 404/405 声明 |
| P1-10 | `healthcheck.sh` 未纳入新服务 | ✅ 补 `8084` / `9095` / `8090`（脚本已改，**未重跑**，如实标注） |
| P1-11 | Nacos 注册默认关闭 | ✅ 口径写明：本地单体关、集成环境开（见部署文档口径） |
| P1-12 | `parameters_schema` 类型错位 | ✅ 类型改 `string \| Record<string, unknown>` |
| P1-13 | 工具域错误码未进横切文档 | ✅ `07-运行时配置与横切约定` §5.2 + `异常流程归纳` §2.5⑥ |

### 补记 C · 结论修订

| 项 | 复审前叙述 | 收口后 |
|---|---|---|
| R5-03 沙箱隔离 | 仅降级后端验证过（`docker_available=false`） | **Docker 真隔离已实测**：服务侧 `GET /api/tool/sandbox` = `active_backend=docker, isolated=true, degraded=false`；`code` 工具 `sandboxed=true, sandbox_backend=docker` |
| 服务侧沙箱状态 | 运行期报 `docker_available=false, degraded=true` | 归因为**上一次运行时镜像尚未构建 / JVM 探测时机**——本轮镜像已存在，重启后启动日志 `Docker 沙箱探测：available=true`，降级消失 |
| 交付完整性 | "功能完成、档案缺失" | **功能 + 档案 + 验证闭环齐备**，超范围缺口单独登记待决策 |
