# Phase 5 演示脚本：四肢期——执行与隔离 🦾

> **演示目标**：证明生命体已"长出四肢"——大脑的决策可以落到**真实动作**上（执行代码、发起 HTTP、
> 精确计算），且动作受**白名单 → 参数 Schema → 敏感规则**三道闸约束，在**真隔离沙箱**里运行，
> 结果**全量落审计**（PG 真相源 + Kafka 旁路）；隔离失效、审计降级、注册表降级一律**显式可见**。
> **前置条件**：Docker Desktop（不要升级，本机 Build 19041）、JDK 21、Maven 3.9+、Python 3.13 + venv、Node 22
> **对应需求**：R5-01 ~ R5-08（`Phase5-四肢期/需求设计文档.md`）+ 终端线 R-C05(预) + IN-06 工具契约治理
> **版本**：2026-09-19 收口版 —— 本版**按复审后的真实代码逐条核对**接口与返回值；
> 沙箱一节给出**内核级隔离证据**（不止"守卫拦截"）。

---

## 一、自动化验证（无需 Docker，先跑这一层）

### 1.1 Java 全量构建与单测

```bash
cd services/java
../../scripts/mvn-dev.sh test
```

预期（2026-09-19 实测）：

| 模块 | 测试数 | 失败 | 错误 |
|------|-------:|-----:|-----:|
| proto-contracts | 2 | 0 | 0 |
| session-manager | 43 | 0 | 0 |
| sense-service | 53 | 0 | 0 |
| body-service | 64 | 0 | 0 |
| **tool-executor** | **82** | **0** | **0** |
| **合计** | **244** | **0** | **0** |

本阶段新增 `tool-executor` 82 项：`ToolGuardTest`(16) · `ToolRegistryTest`(13) · `ToolExecutorTest`(10) ·
`GlobalExceptionHandlerTest`(9) · `HttpToolTest`(8) · `ToolAuditLogTest`(7) · `CalculatorToolTest`(6) ·
`CodeToolTest`(6) · `SandboxExecutorTest`(5) · `ToolSchemaBaselineTest`(2)。

### 1.2 Python Function Calling（R5-06）

```bash
cd services/python/nlp-service
../venv/Scripts/python.exe -m pytest -q      # Windows
```

预期（2026-09-19 实测）：

```
139 passed
```

| 测试文件 | 覆盖 |
|---|---|
| `test_function_calling.py` | 工具 schema → function-calling 描述、组合任务（计算+查询）、`decider="rule"` 诚实标注 |
| 其余 | Phase 2/3/4 存量（意图 / OCR / 分块 / 嵌入 / 重排 / HTTP 错误语义 / 大脑层 / 记忆图谱 / 评测集） |

### 1.3 wp-bff 回归

```bash
cd services/node/wp-bff
node --test
```

预期：

```
# tests 73
# pass 73
# fail 0
```

本阶段新增工具域用例：`/api/wp/tools*` 只读端点（聚合 / 注册表 / 审计 / 指标 / 沙箱）、
`POST /api/wp/tools/execute` 写路径鉴权反向用例。

### 1.4 契约与文档覆盖

```bash
cd /e/AI/ai-agent
python scripts/contract-check.py --work-platform
# → 扫描端点 60 | implemented 30 路径（31 方法） | planned 30 | 工具契约基线 3 = ToolBootstrap 注册 3 | 0 FAIL
python scripts/java-doc-coverage.py
# → 157/157 业务源文件已登记（tool-executor 42/42）
```

### 1.5 前端

```bash
cd web/work-platform
npx vue-tsc --noEmit && npx vite build
```

预期：类型检查通过，`ExecutionView` 产出独立 chunk。

---

## 二、端到端演示（需 Docker 基础设施）

### 2.0 启动

```bash
cd /e/AI/ai-agent
docker compose up -d postgres kafka          # 审计真相源 + 事件旁路
docker ps --format '{{.Names}} {{.Status}}'
```

构建沙箱镜像（**前提**，否则 `tool-executor` 启动即降级为受限子进程）：

```bash
docker build -t agent-sandbox:latest infra/docker/sandbox/
docker images agent-sandbox                  # 期望约 124 MB
```

启动 tool-executor——**务必显式指定端口**（本机环境变量会污染 `server.port`）：

```bash
cd services/java
../../scripts/mvn-dev.sh -pl tool-executor -am install -DskipTests
"E:/software/java/jdk-21/bin/java.exe" -jar tool-executor/target/tool-executor-0.1.0-SNAPSHOT.jar \
  --spring.cloud.nacos.discovery.enabled=false --spring.cloud.nacos.config.enabled=false --server.port=8084
```

启动日志**必须**出现（否则说明沙箱降级了）：

```
Docker 沙箱探测：available=true image=agent-sandbox:latest（不可用时将降级为受限子进程）
Tomcat started on port 8084 (http)
```

启动 BFF 与前端：

```bash
cd services/node/wp-bff && node server.js          # 8090
cd web/work-platform && npm run dev                # 3001
```

---

### 2.1 R5-01 工具注册表

```bash
curl -s --noproxy '*' http://127.0.0.1:8084/api/tool/registry
```

预期关键字段：

```json
{
  "summary": { "tool_count": 3, "change_count": 3 },
  "change_log": [
    { "action": "REGISTER", "tool_name": "calculator", "impact": [] },
    { "action": "REGISTER", "tool_name": "code", "impact": [] },
    { "action": "REGISTER", "tool_name": "http", "impact": [] }
  ]
}
```

### 2.2 R5-03 沙箱状态（**先确认是否真隔离**）

```bash
curl -s --noproxy '*' http://127.0.0.1:8084/api/tool/sandbox
```

预期（镜像就绪）：

```json
{
  "enabled": true,
  "active_backend": "docker",
  "isolated": true,
  "degraded": false,
  "docker_available": true,
  "process_fallback_available": true,
  "timeout_ms": 10000,
  "memory_mb": 256,
  "note": "Docker 真隔离：网络禁用 / 只读 rootfs / 去能力 / 资源限"
}
```

> **降级口径**：若 `active_backend=process-restricted` + `degraded=true`，说明 Docker 不可用，
> 当前**不是**真隔离边界——必须据此调整对 R5-03 的结论，**不得**当作"沙箱验收通过"。

### 2.3 R5-04 / R5-05 工具执行

```bash
# 计算（无 eval 的手写解析器）
curl -s --noproxy '*' -X POST http://127.0.0.1:8084/api/tool/execute \
  -H 'Content-Type: application/json' -H 'X-Tenant-Id: demo' \
  -d '{"tool_name":"calculator","arguments":{"expr":"sqrt(2)+pow(2,10)"},"call_id":"demo-calc"}'

# 代码执行（经沙箱）
curl -s --noproxy '*' -X POST http://127.0.0.1:8084/api/tool/execute \
  -H 'Content-Type: application/json' -H 'X-Tenant-Id: demo' \
  -d '{"tool_name":"code","arguments":{"code":"print(sum(range(10)))"},"call_id":"demo-code"}'
```

预期（实测）：

```json
{ "call_id": "demo-calc", "tool_name": "calculator", "success": true,
  "output": "1025.414213562373", "latency_ms": 3, "audit_id": "audit-…" }

{ "call_id": "demo-code", "tool_name": "code", "success": true,
  "output": "45", "sandboxed": true, "sandbox_backend": "docker", "degraded": false,
  "latency_ms": 3587, "audit_id": "audit-…" }
```

### 2.4 R5-03 / R5-07 逃逸样本（**隔离的决定性证据**）

```bash
# 样本 A：出网（守卫应拦）
curl -s --noproxy '*' -X POST http://127.0.0.1:8084/api/tool/execute \
  -H 'Content-Type: application/json' \
  -d '{"tool_name":"code","arguments":{"code":"import urllib.request;print(urllib.request.urlopen(\"http://example.com\").status)"}}'

# 样本 B：写 rootfs（沙箱只读应拦）
curl -s --noproxy '*' -X POST http://127.0.0.1:8084/api/tool/execute \
  -H 'Content-Type: application/json' \
  -d '{"tool_name":"code","arguments":{"code":"open(\"/evil.txt\",\"w\").write(\"x\")"}}'
```

预期（实测）：

```json
// 样本 A → 403
{ "code": "AGENT_TOOL_ARGS_BLOCKED",
  "message": "参数命中安全拦截规则：(?i)\\b(import\\s+socket|socket\\.socket|urllib\\.request|requests\\.(get|post))\\b",
  "details": { "matched": "urllib.request", "audit_id": "audit-…" } }

// 样本 B → 400，stderr 暴露内核级拒绝
{ "code": "AGENT_TOOL_EXEC_FAILED", "message": "沙箱执行返回非零退出码 1",
  "details": { "sandbox_backend": "docker",
               "stderr": "OSError: [Errno 30] Read-only file system: '/evil.txt'" } }
```

**直调 docker 复核（跳过守卫，证明隔离不止靠正则）**：

```bash
docker run --rm -i --network none agent-sandbox:latest python3 -c \
  "import urllib.request;print(urllib.request.urlopen('http://example.com',timeout=3).status)"
# → socket.gaierror: [Errno -3] Temporary failure in name resolution

docker run --rm -i --read-only agent-sandbox:latest python3 -c \
  "open('/evil.txt','w').write('x')"
# → OSError: [Errno 30] Read-only file system: '/evil.txt'

docker run --rm -i --network none --read-only agent-sandbox:latest python3 -c "print(2**10)"
# → 1024（正常执行不受影响）
```

### 2.5 R5-08 审计与指标

```bash
curl -s --noproxy '*' http://127.0.0.1:8084/api/tool/audit/stats
curl -s --noproxy '*' http://127.0.0.1:8084/api/tool/metrics
```

预期：

```json
{ "audit": { "backend": "postgres", "degraded": false, "total": 4, "success": 2,
             "blocked": 1, "sandboxed": 2, "success_rate": 0.5 },
  "kafka": { "truth_source": "postgres.tool_audit_log", "role": "event-sidecar",
             "available": true, "topic": "lifeform.tool.invoked" } }

{ "metrics": { "window_size": 4, "p50_ms": 3, "p95_ms": 3587, "p99_ms": 3587,
               "blocked_calls": 1, "calls_by_tool": { "calculator": 1, "code": 3 } },
  "circuit_breakers": [ { "tool_name": "code", "consecutive_failures": 2, "state": "closed" } ] }
```

> **审计真相源**是 PG `tool_audit_log`，Kafka 仅旁路；PG 不可用时回落内存并标 `degraded=true`。

### 2.6 IN-06 工具契约治理（影响面 + 门禁）

```bash
curl -s --noproxy '*' http://127.0.0.1:8084/api/tool/calculator/impact
```

在 work-platform **执行视图**点击任一工具的「影响面」按钮 → 抽屉展示
`current_version` / `schema_changed` / `affected_agents` / `affected_flows` / `contract_test_required`。

**门禁反向验证**（应有条数断言）：

```bash
python scripts/contract-check.py --work-platform
# 期望输出含：工具契约基线 3 个工具 | ToolBootstrap 注册 3 个
# 把 contracts/tool-schema-baseline.json 删一条 → 门禁应 FAIL
```

### 2.7 终端线（R-C05 执行视图）

浏览器打开 `http://127.0.0.1:3001` → 侧栏「执行」模块：

- 工具清单（名称 / 版本 / 沙箱需求 / 超时 / 白名单域名 / 是否弃用）
- 注册表变更史（`REGISTER` / `DEPRECATE`）
- 指标卡（P50 / P95 / P99 + 熔断器状态）
- 沙箱卡片（`docker` / `真隔离` vs `受限子进程` / `已降级`）
- 审计记录（成功 / 拦截 / 失败三类）
- 「影响面」抽屉 + 工具试执行（**写路径**，经 Vite 代理注入控制令牌）

> **API 模式下**：`data_source='live'` 显示「真实调用记录」；BFF 不可达时回落演示值并打
> 「演示数据」徽标 + 顶栏降级横幅——**不静默伪造**。

---

## 三、口径说明（**诚信附注**）

| 项 | 口径 |
|---|---|
| 沙箱真隔离 | **已实测**（服务侧 `docker/isolated=true` + 内核级 `Errno 30` / `Errno -3`）；此前一轮运行曾报降级，因镜像尚未构建，已定位 |
| `healthcheck.sh` | 已补 8084 / 9095 / 8090 探针，但**本轮未整体重跑** |
| 工具指标 | 进程内滑动窗口，**未接 Prometheus**（DEBT-021） |
| 注册表 | 进程内内存，重启丢失（DEBT-019） |
| Schema 校验器 | 实装 networknt，设计写 everit（DEBT-020） |
| 工具决策 | 规则决策，如实标 `decider="rule"`（非 LLM 决策） |
| 超范围缺口 | WB-06 / WB-10 / RE-01~RE-04 **未实现**，已登记待决策 |

---

## 四、演示结论

1. **四肢可用**：三类工具真执行，回执标准化，审计可追溯。
2. **隔离可证**：恶意样本在内核层面被拒（网络 + 写盘），不依赖"守卫不放行"。
3. **降级可见**：沙箱 / 审计 / 注册表 / 决策四处降级均带标记，界面据实显示。
4. **契约可守**：工具 schema 基线与实现条数绑定，改工具必须先同步契约。
