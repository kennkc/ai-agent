# Phase 5 开发执行日志（归档）

| 项 | 内容 |
|---|---|
| 阶段 | Phase 5 · 四肢期（Limb Stage） |
| 项目 | Agent-Lifeform · AI Agent 生命体架构 |
| 仓库 | `E:\AI\ai-agent`（开发分支 `dev`，跟踪 `origin/workbuddy/main`） |
| 分支 | `dev` / `codex/main` / `workbuddy/main`（三分支同内容对齐） |
| 执行日期 | 2026-09-19 |
| 代码基线 | `0921345`（四肢期代码收口）→ 复审收尾轮（文档 + 门禁 + 沙箱复验） |
| 需求范围 | R5-01 ~ R5-08（《阶段性需求拆解分析》Phase 5 表）+ 终端线 **R-C05(预)** + **IN-06** |
| 执行结论 | **完成**：Must 7/7、Should 1/1、终端线达标；沙箱 Docker 真隔离已复验通过；超范围缺口（WB/RE）如实登记待决策 |

---

## 一、执行流程总览

```
Phase A 前置同步与需求对账
   └─ 拉远端 → 解读提交链 → 确认 Phase 4 已收口、Phase 5 需求表处于冻结/未开工
Phase B Java 新服务 tool-executor（R5-01/R5-02/R5-03/R5-04/R5-05/R5-07/R5-08 + IN-06）
   └─ 注册表 → 安全三闸 → 执行引擎 → 沙箱双后端 → 三内置工具 → 审计 → 控制层
Phase C Python Function Calling 适配（R5-06）
   └─ app/tools/function_calling.py → 转 schema → 调 tool-executor → 诚实标注 decider
Phase D BFF 工具域代理（R-C05 预）+ 契约落标
     └─ /api/wp/tools* 9 方法 · 契约 7 路径 planned → implemented · 工具域错误码枚举
Phase E 终端线：work-platform 执行视图（R-C05）
Phase F 契约治理（IN-06）：schema 基线 + 指纹 + 影响面 + 门禁
Phase G 运行态验证与缺陷修复（真起 8084 + 真调 Docker）
Phase H 阶段收口：三件套 + HTML 孪生 + 进度文档对齐（本轮）
```

设计文档定义的链路（`AI-Agent生命体架构-阶段性需求拆解分析.md` Phase 5）：

```
意图 → 规划 → 工具选择 → 白名单 → 参数 Schema → 敏感参数扫描 → 沙箱执行 → 结果标准化 → 审计 → 回执
```

---

## 二、分阶段执行记录

### Phase A｜前置同步与需求对账

- 拉取远端并解读提交链，确认 Phase 4 已交付并复审补全、Phase 5 需求表未开工。
- 逐条抄录 R5-01 ~ R5-08 的 DoD 作为验收基线；确认终端线 R-C05(预) 与 IN-06 属本阶段。
- 确认依赖：Docker Desktop 4.4.4 可用（**注意：本机 Build 19041，Docker 不得升级**）。

### Phase B｜Java 新服务 `tool-executor`（R5-01 ~ R5-08 + IN-06）

新增独立 Maven 模块 `services/java/tool-executor`（32 主 / 10 测，2952 + 1311 行）：

| 包 | 关键类 | 对应需求 | 关键决策 |
|---|---|---|---|
| `registry/` | `ToolRegistry` / `ToolBootstrap` / `ToolVersionRecord` / `ImpactReport` | R5-01 · IN-06 | 注册表 + 版本 `change_log` + semver + `schema_hash` 指纹；影响分析只覆盖**显式登记**的消费方（DEBT-019） |
| `guard/` | `ToolGuard` | R5-07 | 三闸严格有序：白名单 → Draft-07 Schema（networknt）→ 12 条敏感正则；顺序不可换 |
| `exec/` | `ToolExecutor` / `ToolMetrics` | R5-02 | 带超时执行 + 失败归类 + 回执标准化；连续失败 3 次熔断 30s |
| `sandbox/` | `SandboxExecutor` / `DockerSandboxBackend` / `RestrictedProcessBackend` / `ProcessRunner` | R5-03 / R5-05 | Docker 主路径（`--network none` / `--read-only` / `--cap-drop ALL` / 非 root / 资源限）；不可用时**显式**回落受限子进程并标 `degraded=true` |
| `tools/` | `CalculatorTool` / `HttpTool` / `CodeTool` | R5-04 / R5-05 | calculator 手写解析器**无 eval**；http 域名白名单 + SSRF 双闸；code 一律经沙箱 |
| `audit/` | `ToolAuditLog` / `AuditEventPublisher` | R5-08 | PG `tool_audit_log` 为真相源，Kafka `lifeform.tool.invoked` 为旁路，PG 不可用时内存降级 |
| `controller/` | `ToolController` | 对外 | 12 端点；错误走统一信封 + 路由层 404/405/415 分流 |

**关键技术坑（记录备查）**：`SandboxExecutor` 必须在**任何后端返回结果**上强制补 `degraded` 标记——
否则降级后端会"看起来和真隔离一样成功"，这是本阶段最容易踩的红线。

### Phase C｜Python Function Calling（R5-06）

- 新增 `services/python/nlp-service/app/tools/function_calling.py`（351 行）：把 tool-executor 的工具
  schema 转成 LLM function-calling 描述；组合任务（计算 + 查询）走规则决策并**如实标注 `decider="rule"`**。
- `main.py` 新增 4 个工具相关端点。
- 测试 `tests/test_function_calling.py`（148 行 / 15 项）。

### Phase D｜BFF 工具域代理 + 契约

`wp-bff/server.js` 新增 9 个方法并登记 `IMPLEMENTED_ENDPOINTS`（ROUTE_GUARD 自动派生 405/Allow）：

- 只读：`GET /api/wp/tools`、`/tools/registry`、`/tools/audit`、`/tools/audit/stats`、`/tools/metrics`、`/tools/sandbox`
- **写路径**：`POST /api/wp/tools/execute` —— 与 `middleware/{key}/start|stop` 同级校验（来源白名单 + `X-WP-Control-Token`）
- tool-executor 不可达时回 **200 + `available=false` + `gaps`**（与体层同一「可用性信封」约定）

契约 `contracts/work-platform-bff-openapi.yaml`：7 路径 `planned → implemented`，补 `ErrorEnvelope.code.enum`
的 6 个工具域码与工具域端点 404/405 声明。

### Phase E｜终端线（R-C05 执行视图）

- `views/ExecutionView.vue`（495 行）：工具清单 / 注册表变更史 / 指标（P50/P95/P99 + 熔断器）/ 沙箱状态 /
  审计记录 / **IN-06 影响面抽屉** / 工具试执行。
- `provider.ts` 新增 `getExecution()` / `executeTool()` / `getToolImpact()`；`config/modules.ts`、路由、
  图标、`types/index.ts` 同步登记。
- Mock 与真实 API **同构**：`registered_at` 校正为 epoch 毫秒、`change_log[].action` 大写、`impact` 数组、
  `success_rate` 无样本为 `null`、参数名按真实 `expr` / `code` / `url`。

### Phase F｜契约治理 IN-06

- `contracts/tool-schema-baseline.json`：3 个内置工具的 schema 基线。
- `scripts/contract-check.py` 增加"基线条数 = `ToolBootstrap` 注册条数"断言；**改工具 schema 未同步基线即 FAIL**。
- `ToolSchemaBaselineTest`（2 项）固化该约束。

### Phase G｜运行态验证与缺陷修复

坚持"真起进程 + 真数据"，抓到的问题：

1. 沙箱后端选型判定：**服务进程启动时镜像尚未构建** → 运行期报 `docker_available=false`；
   本轮镜像就绪后重启，启动日志 `Docker 沙箱探测：available=true`，`/api/tool/sandbox` 回到 `docker / isolated=true`。
2. 前端 `parameters_schema` 类型错位（声明对象、真实为 JSON 字符串）→ 类型放宽（P1-12）。
3. 契约错误码枚举缺工具域 6 码 → 补齐（P1-09）。

### Phase H｜阶段收口

三件套文档 + DEMO + HTML 孪生 + 进度文档对齐 + 三分支推送。

---

## 三、提交台账

| # | 说明 | 测试基线变化 |
|---|---|---|
| 1 | Java `tool-executor` 主体（R5-01~R5-05/R5-07/R5-08 + IN-06） | Java 162 → 240 |
| 2 | Python Function Calling（R5-06） | Python 124 → 139 |
| 3 | BFF 工具域 9 端点 + 契约落标 | wp-bff 55 → 73 |
| 4 | work-platform 执行视图（R-C05）+ Mock 对齐 | 前端 typecheck/build 通过 |
| 5 | 复审收尾轮：IN-06 前端入口 + 错误码 + 文档 + 门禁 | Java 240 → **244**；文档覆盖 118/156 → **157/157** |

> 具体 commit 哈希以 `git log` 为准（Phase 5 代码与文档为**同一开发线连续提交**）。

---

## 四、命令台账（关键命令）

```bash
# Java 全模块测试
cd services/java && bash ../../scripts/mvn-dev.sh test

# 单独构建/启动 tool-executor（本机环境变量 SERVER__PORT 会顶掉 server.port）
cd services/java && bash ../../scripts/mvn-dev.sh -pl tool-executor -am install -DskipTests
java -jar services/java/tool-executor/target/tool-executor-0.1.0-SNAPSHOT.jar \
  --spring.cloud.nacos.discovery.enabled=false --spring.cloud.nacos.config.enabled=false --server.port=8084

# Python
cd services/python/nlp-service && ../venv/Scripts/python.exe -m pytest -q

# wp-bff
cd services/node/wp-bff && node --test

# 契约与文档覆盖门禁
python scripts/contract-check.py --work-platform
python scripts/java-doc-coverage.py

# 前端
cd web/work-platform && npx vue-tsc --noEmit && npx vite build

# 沙箱镜像构建 + 真隔离探测
docker build -t agent-sandbox:latest infra/docker/sandbox/
python .workbuddy/tmp/sbx-e2e.py        # 服务侧沙箱端到端（含逃逸样本）
```

---

## 五、环境侧记录

| 现象 | 根因 | 处置 |
|---|---|---|
| 运行期 `GET /api/tool/sandbox` 报 `docker_available=false` | 服务启动时 `agent-sandbox:latest` 镜像尚未构建 | `docker build -t agent-sandbox:latest infra/docker/sandbox/` 后重启服务，探测回到 `true` |
| 沙箱容器内 python 报 `Temporary failure in name resolution` | `--network none` 生效（预期行为） | 作为"出网被拦"的**正向证据**记录 |
| Git Bash 下 `docker run` 报 `/usr/bin/env: 'sh': No such file` | 受限 shell 环境缺 `sh` 包装 | 改用 PowerShell / Java 服务侧调用执行 docker |
| `tool-executor` 未接 Nacos 也能起 | 本地单体开发约定 `NACOS_ENABLED=false` | 启动显式 `--spring.cloud.nacos.*.enabled=false`（P1-11 口径） |

---

## 六、经验沉淀

1. **隔离要用内核级证据说话**：`403 守卫拦截` 只能证明"没放行"，`Errno 30 Read-only file system` /
   `Errno -3 name resolution` 才能证明"沙箱真的关住了"。
2. **降级标记必须是硬约束**：沙箱降级、审计降级、注册表降级、决策降级——任何"次优路径"都要带 `degraded`
   字段，否则就是把降级伪装成成功。
3. **契约门禁要能拦住 schema 漂移**：IN-06 的关键不是"有影响面接口"，而是**基线比对进 CI**。
4. **超范围缺口要如实登记**：WB-06 / WB-10 / RE-01~RE-04 有验收标准但不在 R5 主线，
   登记待决策比塞进阶段尾巴仓促实现更负责任。

---

## 补记（2026-09-19 · 复审收口轮）

> 本轮由**阶段交付复审**触发（详见 `docs/优化日志/2026-09-19-Phase5四肢期进度复审与缺口清单.md`）。
> 复审结论：**代码质量可交付，交付物不完整**。本轮补齐全部 P0 档案缺口与 P1 验证闭环，
> 并完成**沙箱 Docker 真隔离复验**（服务侧证据见《测试验收报告》§5.2）。

### 补记 A · 本轮补齐的交付物

| 项 | 补齐前 | 补齐后 |
|---|---|---|
| `tool-executor` 文档 | 全仓 `docs/` 零提及 | `docs/java-services/09-tool-executor.md` + 登记 `01-模块总览` / `README` |
| 文档覆盖门禁 | ❌ 118/156（tool-executor 5/41） | ✅ **157/157** |
| 技术债登记 | 代码引用 DEBT-019~021，台账未登记 | ✅ 台账补 3 条 + §7 标记索引 |
| 全局文档 | `PROGRESS` 写 "Phase 5 is next"；README 写"下一阶段为 Phase 5" | ✅ 全部改为"Phase 5 已交付"口径 |
| 阶段三件套 | 全缺 | ✅ 本文件 + 阶段性报告 + 测试验收报告 + DEMO |

### 补记 B · 本轮修复的验证/联通缺口

| # | 缺口 | 处置 |
|---|---|---|
| P1-06 | 沙箱真隔离未验证 | ✅ 构建镜像 + 重启服务 → `active_backend=docker, isolated=true, degraded=false`；逃逸样本实测 |
| P1-07 | IN-06 影响分析前端无入口 | ✅ 执行视图「影响面」抽屉 + `getToolImpact()` |
| P1-08 | schema 变更未触发契约测试 | ✅ `tool-schema-baseline.json` 纳入门禁 |
| P1-09 | 契约错误码枚举缺工具域码 | ✅ 补 6 码 |
| P1-10 | `healthcheck.sh` 未纳入新服务 | ✅ 补 `8084` / `9095` / `8090`（**未重跑**，如实标注） |
| P1-11 | Nacos 注册口径不明 | ✅ 写明「本地单体关、集成环境开」 |
| P1-12 | `parameters_schema` 类型错位 | ✅ 放宽为 `string \| Record<string, unknown>` |
| P1-13 | 工具域错误码未进横切文档 | ✅ `07-运行时配置与横切约定` §5.2 + `异常流程归纳` §2.5⑥ |
