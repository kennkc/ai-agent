# proto 契约使用说明

> **口径时间**：2026-09-17 · **代码基线**：`cb140b2`（`scripts/proto-gen.sh` 修复前）
> **契约源**：`proto/`（6 份 `.proto`，367 行）· **生成物**：Java 90 个（已入库）/ Python 12 个（未入库）
> **定位**：跨服务 gRPC 契约的唯一事实来源。HTTP 契约见 `contracts/work-platform-bff-openapi.yaml`。

---

## 一、先说结论：契约是什么状态

**6 份契约、6 个 service、15 个 RPC 方法 —— 全部只用于编译期，当前没有任何服务在运行时调用。**

这不是遗漏，是**刻意的"契约先行"决策**（后续阶段实现 gRPC 时不必回头改接口）。但由此产生一个必须防的误读：

> ⚠️ **"契约已定义" ≠ "接口已可用"。**
> 看到 `SessionServiceGrpc` 这类生成类，**不等于**会话服务对外提供 gRPC 接口。
> 目前服务间调用**全部走 HTTP + NATS/Kafka**，与这些 proto 无关。

一个最容易被误判的点：**本仓库的 `common/health.proto` 也没有被使用**。四个服务确实在 9091-9094 开了 gRPC 端口，但那是 **`grpc-services` 依赖自带的 `HealthStatusManager`**（实现的是标准 `grpc.health.v1` 协议，供 `grpc-health-probe` 之类工具用），**不是**本仓库定义的 `com.agent.common.v1.HealthService`。两者只是名字像。

---

## 二、契约清单

| 契约文件 | 行数 | 包 | service | RPC | 消息数 | 生成 Java 文件 | 运行时调用方 |
|---|---:|---|---|---:|---:|---:|---|
| `proto/common/v1/health.proto` | 35 | `com.agent.common.v1` | `HealthService` | 1 | 3 | 8 | **无** |
| `proto/session/v1/session.proto` | 113 | `com.agent.session.v1` | `SessionService` | 5 | 13 | 28 | **无** |
| `proto/sensor/v1/sensor.proto` | 71 | `com.agent.sensor.v1` | `SensorService` | 3 | 6 | 16 | **无** |
| `proto/brain/v1/brain.proto` | 51 | `com.agent.brain.v1` | `BrainService` | 2 | 6 | 14 | **无** |
| `proto/body/v1/body.proto` | 50 | `com.agent.body.v1` | `BodyService` | 2 | 5 | 12 | **无** |
| `proto/limb/v1/limb.proto` | 47 | `com.agent.limb.v1` | `LimbService` | 2 | 5 | 12 | **无** |
| **合计** | **367** | 6 个包 | 6 个 service | **15** | **38** | **90** | **0** |

另有 2 个顶层枚举（`ChannelType`、`CollectMode`，在 `sensor`）与 1 个嵌套枚举（`HealthCheckResponse.Status`）。

### 逐份说明

**`common/v1/health.proto`** — 统一健康检查与错误结构。
`HealthService.Check` 按服务名返回 `Status`(UNKNOWN/SERVING/NOT_SERVING) + `version` + `uptime_seconds` + `dependencies` 映射（如 `redis=ok`）。`Error` 是统一错误结构（`code` / `message` / `details`）。
**唯一带 `go_package` 选项的契约**，其余 5 份没有 —— 属一致性小缺口。

**`session/v1/session.proto`** — VS1 中枢的核心数据模型，**字段逐条对齐《D5-2 核心数据字典》§3.1**，是 6 份里最"重"的一份（113 行 / 13 消息）。
- `Session`(11 字段)：含 `tenant_id` / `version`（乐观锁）等通用字段，`id` 注释标明 UUID v7。
- `Message`(10 字段)：`role`(user/assistant/system/tool)、`intent` + `confidence`（P2 起）、`source_citations`（感知可信度呈现）、`latency_ms`。
- `Citation`(5 字段)：来源标注（实时采集/知识库/模型生成）。
- 5 个 RPC：Create / AppendMessage / Get / ListMessages / UpdateSessionStatus（带 `expected_version` 乐观锁）。
- ⚠️ 第 10 行内嵌 `DEBT-003: 存储为内存 Map` —— **该注释已过期**（会话实际已用真 Redis），见 §八。

**`sensor/v1/sensor.proto`** — 五官采集契约。
`ChannelType` 五值（VISUAL/AUDIO/TOUCH/NOSE/TASTE）与 `CollectMode`（R0 默认 / R1 动态）定义在此。`CollectResponse` 带 `quality_score`（六维质量综合分）与 `accepted`（是否过质检）。

**`brain/v1/brain.proto`** — 意图识别与任务规划（`RecognizeIntent` / `PlanTask`），`IntentResult` 含 `slots` 实体槽位，`TaskStep` 含 `depends_on` 依赖链。
⚠️ 第 1 行内嵌 `DEBT-002`，标注"大脑回答为模板占位" —— 但模板回答实际落在 **`session-manager.buildAnswer`**，归属模块标错了。

**`body/v1/body.proto`** — 知识存储与检索（`Ingest` / `Retrieve`），`RetrieveResponse` 直接带 `latency_ms`。
⚠️ 第 1 行内嵌 `DEBT-001`，写"本地文件检索简化版" —— 实际 `BodyStore` 是**纯内存 Map + 字符命中打分**，连文件都没落。

**`limb/v1/limb.proto`** — 工具调用与执行（`ExecuteTool` / `RegisterTool`），`ToolResult` 带 `sandboxed` 标志。**Phase 5 四肢层才会用**。

---

## 三、当前使用情况（实证）

### 3.1 引用矩阵

| 使用方 | 是否引用生成类 | 证据 |
|---|---|---|
| `gateway-service` | ❌ 零引用 | 无 `import com.agent.*.v1.*` |
| `session-manager` | ❌ 零引用 | 同上 |
| `sense-service` | ❌ 零引用 | 同上 |
| `body-service` | ❌ 零引用 | 同上 |
| `nlp-service`(Python) | ❌ 零引用 | 无 `import *_pb2`，无 gRPC server |
| wp-bff / work-platform | ❌ 不涉及 | 走 HTTP，与 gRPC 无关 |

四个 Java 服务的 `pom.xml` **都声明依赖 `proto-contracts`**，但代码里一行都没用 —— 依赖是"**备好待用**"，不是"已接入"。

### 3.2 复现证据（可自行跑）

```bash
# 1) 业务服务是否引用契约生成类 → 期望输出为空
grep -rn "^import com\.agent\.[a-z]*\.v1\." services/java/*/src/main/java --include="*.java"

# 2) gRPC 健康端口的真实实现 → 用的是 grpc-services 自带 HealthStatusManager
grep -rn "HealthStatusManager\|io.grpc.health.v1" services/java/*/src/main/java

# 3) Python 侧生成物是否被引用 → 期望输出为空
grep -rn "_pb2" services/python/nlp-service/app services/python/nlp-service/tests

# 4) gRPC 端口只做 TCP 探测，不调 HealthService.Check
grep -n "grpc health" -A1 scripts/healthcheck.sh
```

### 3.3 gRPC 端口现状（别误读）

| 服务 | 端口 | 实际提供 | 启动方式 |
|---|---|---|---|
| gateway-service | 9091 | `grpc.health.v1.Health/Check`（标准协议） | `GrpcHealthServer implements SmartLifecycle` |
| session-manager | 9092 | 同上 | 同上 |
| sense-service | 9093 | 同上 | 同上 |
| body-service | 9094 | 同上 | 同上 |

实现只有一个类、24 行：`NettyServerBuilder.forPort(port).addService(health.getHealthService())`，`health` 是 `HealthStatusManager`。**这不是本仓库任何 proto 的服务端实现。**
`scripts/healthcheck.sh` 的 `[grpc health]` 段也只做 `check_port`（TCP 连通性），不发起 gRPC 调用。

---

## 四、代码生成链路

### 4.1 Java 侧（90 个文件，**已入库**）

产物位置：`services/java/proto-contracts/src/main/java/com/agent/<域>/v1/`
分布：`body` 12 · `brain` 14 · `common` 8 · `limb` 12 · `sensor` 16 · `session` 28

| 生成文件 | 来源 | 作用 |
|---|---|---|
| `<Message>.java` | 每个 message | 不可变消息类（含 Builder） |
| `<Message>OrBuilder.java` | 每个 message | 只读视图接口 |
| `<ProtoFile>.java` | 每个 `.proto` | 文件级外层类（存描述符） |
| `<Service>Grpc.java` | 每个 `service` | gRPC 桩：`newBlockingStub` / `newStub` / `ImplBase` |
| `<Enum>.java` | 每个顶层 enum | proto3 枚举 |

**命名陷阱**：`session.proto` 里存在名为 `Session` 的 message，与"由文件名推导的外层类名"冲突 → `protoc` 把外层类改名为 **`SessionOuterClass.java`**。在 `session` 包里 `Session` 指的是**消息类**。其余 5 份的外层类分别是 `Health` / `Sensor` / `Brain` / `Body` / `Limb`。

**为什么入库（关键工程决策）**：`protobuf-maven-plugin` 挂在默认生命周期时，每次构建会先删 `target/protoc-dependencies/` 再解压，而在本机 Windows 上该删除会**在原生层永久阻塞**（`WinNTFileSystem.delete0`），表现为「Maven 无任何输出、永久卡死」。现已把插件从默认生命周期移除，产物入库；构建从"永久卡死"变为约 39 秒稳定通过。
完整的 jstack 证据链与已排除项见 `docs/java-services/02-proto-contracts.md` §4。

### 4.2 Python 侧（12 个文件，**未入库**，已 gitignore）

产物位置：`services/python/nlp-service/generated/<域>/v1/{<name>_pb2.py, <name>_pb2_grpc.py}`

- `.gitignore:32` → `services/python/nlp-service/generated/`
- **从未入库过**（`git log --all -- <该目录>` 为空）
- ⚠️ 这与 `docs/项目进度日志报告/Phase0-开发执行日志.md` 第 3 条"proto 生成物**双侧入库**（Java 90 文件 + Python 12 文件）"的表述不符 —— 已在技术债台账登记为口径漂移。

**生成命令（已实测可复现现有产物）**：

```bash
# 推荐：仓库脚本（2026-09-17 修复后可用）
bash scripts/proto-gen.sh
# Windows cmd：
scripts\proto-gen.bat
```

脚本等价于：

```bash
cd <repo-root>                     # 必须是仓库根，且用相对路径 —— 见下"坑"
services/python/venv/Scripts/python.exe -m grpc_tools.protoc \
  -I proto \
  --python_out=services/python/nlp-service/generated \
  --grpc_python_out=services/python/nlp-service/generated \
  proto/common/v1/*.proto proto/session/v1/*.proto proto/brain/v1/*.proto \
  proto/sensor/v1/*.proto proto/body/v1/*.proto proto/limb/v1/*.proto
```

依赖：`grpcio-tools`（当前 venv 内为 **1.83.1**，已装）。`requirements.txt` 里**没有声明** `grpcio-tools` —— 换机重建环境时需额外装。

### 4.3 本机坑（实测）

| 坑 | 现象 | 结论 |
|---|---|---|
| **POSIX 路径传给原生 Windows Python** | `scripts/proto-gen.sh` 原版用 `pwd` 得到 `/e/ai_workspace/...` 再传给 `venv/Scripts/python.exe`，protoc 报 `directory does not exist` / `File does not reside within any path specified using --proto_path`，退出码 1 | **已修复**：改为 `cd "$ROOT"` + 相对路径。`pwd -W` / `cygpath -w` 也可用 |
| **Git Bash `/tmp` 与 Windows Python 不互通** | `mkdir -p /tmp/x` 后把 `/tmp/x` 传给 Windows Python → `No such file or directory` | 临时目录用仓库内 `.build/`（已 gitignore）或 `E:/...` 绝对路径 |
| **protoc 在 `-Pproto-gen` 下可能卡死** | Maven 无输出、永久挂起 | 见 `docs/java-services/02-proto-contracts.md` §5 的手工 `protoc` 兜底方案 |

---

## 五、怎么用（分场景）

### 5.1 场景 A：想让 Java 服务真正提供某个 gRPC 接口

契约已经定义好，缺的是**服务端实现**。步骤：

1. 在目标服务里实现 `XxxServiceImplBase`（来自 `com.agent.<域>.v1.<Service>Grpc`）；
2. 在 `application.yml` 里加独立端口配置（**不要复用 909x**，那四个是标准健康探针，改动会影响 `healthcheck.sh` 与 CI）；
3. 启动时 `ServerBuilder.forPort(...).addService(new XxxImpl())`（可参照 `GrpcHealthServer` 的 `SmartLifecycle` 生命周期写法）；
4. 加集成测试（本仓库目前**没有**五渠道/消息链路的集成测试，见 `docs/技术债台账.md` DEBT-006/009）；
5. 更新本文档 §三 的引用矩阵 —— **这是本节存在的意义：现状一旦变化必须同步**。

### 5.2 场景 B：想在 Python 侧用契约（已实测）

`generated/` 没有 `__init__.py`，靠 Python 3 **命名空间包**工作，把该目录加入 `sys.path` 即可：

```bash
cd services/python/nlp-service
PYTHONPATH=generated ../venv/Scripts/python.exe -c "
from session.v1 import session_pb2
s = session_pb2.Session(id='s1', agent_id='a1', title='T', status='active')
print(len(s.SerializeToString()))          # 实测 19
print(len(session_pb2.Session.DESCRIPTOR.fields))  # 实测 11
"
```

导入路径是 `from <域>.v1 import <name>_pb2`（域为 `body`/`brain`/`common`/`limb`/`sensor`/`session`）。
注意生成代码内部用相对包名导入（`from common.v1 import health_pb2`），所以**必须**让 `generated/` 本身成为包根，不能只加 `generated/session`。

### 5.3 场景 C：修改 / 新增契约（6 步）

```bash
# 1) 改契约源
#    编辑 proto/<域>/v1/<name>.proto —— 字段名必须 snake_case（门禁会拦）
#    新增契约文件还需在契约清单表（本文档 §二）登记

# 2) 生成 Java 产物（需 JDK 21；注意有卡死风险）
cd services/java
../../scripts/mvn-dev.sh -pl proto-contracts -am clean package -Pproto-gen
git status --short proto-contracts/src/main/java     # 确认产物变化

# 3) 生成 Python 产物
cd ../..
bash scripts/proto-gen.sh

# 4) 同步生成物注释里的 DEBT 标注（proto 头部注释会【原样复制】进生成类注释）
#    例：session.proto:10 的 DEBT-003 已过期 → 改注释后必须重生成，否则生成物继续撒谎

# 5) 校验
E:/software/anaconda3/python.exe scripts/contract-check.py

# 6) 提交：proto 源 + Java 生成物 一起提交（Python 产物不入库，无需提交）
```

**注意**：字段号（`= 1` `= 2`）**只增不改不复用**，否则破坏线上兼容性；改字段名/删字段要走废弃流程（`reserved`）。

### 5.4 场景 D：核对契约有没有漂移

```bash
E:/software/anaconda3/python.exe scripts/contract-check.py
```

---

## 六、校验与门禁（`scripts/contract-check.py`）

**覆盖**（proto 线）：

| 规则 | 检查内容 |
|---|---|
| 命名规则 | 所有 message 字段名必须 `snake_case`（禁驼峰/大写） |
| 字典对齐 | `Session` / `Message` 必须含《D5-2》规定的字段（`agent_id`/`title`/`message_count`/`last_activity_at`/`model`；`session_id`/`role`/`content`/`intent`/`confidence`/`source_citations`/`latency_ms`/`source`） |
| 通用字段 | `id`/`tenant_id`/`created_at`/`updated_at`/`version` 缺失报 `WARN` |
| 产物一致性 | **不检查** —— 生成物与 `.proto` 是否同步**没有门禁** |

**不覆盖**（重要空白）：

- ❌ **生成物与契约源的同步性**：改了 `.proto` 忘记重生成，门禁**不会**发现。
  建议补：`-Pproto-gen` 重生成后跑 `git diff --exit-code`（`02-proto-contracts.md` §4.3 已提过这条建议，至今未落地）。
- ❌ 字段号变更 / 兼容性破坏
- ❌ Python 侧生成物存在性（该目录 gitignore 且无 CI 步骤，**换机后可能整个缺失而无人察觉**）

当前基线：**FAIL 0 / WARN 3**（3 条 WARN 为既有通用字段告警）。

---

## 七、与 OpenAPI 契约的分工

本仓库有**两条并列的契约线**，别混淆：

| | proto 线 | OpenAPI 线 |
|---|---|---|
| 载体 | `proto/{body,brain,common,limb,sensor,session}/v1/` | `contracts/work-platform-bff-openapi.yaml` |
| 协议 | gRPC | HTTP/JSON |
| 用途 | **服务间**内部调用（未来） | **前端 ↔ wp-bff** 的控制面 |
| 生成物 | Java 入库 / Python 不入库 | 不生成代码，手写 TS 类型 |
| 门禁 | 字段命名 + 字典对齐 | 端点分层（implemented/planned）+ `x-wp-status` 必填 |
| 实现状态 | 0/15 RPC 实现 | 6/37 端点实现 |
| 详细文档 | **本文档** | `docs/功能开发流程.md` §3.10 |

共通的纪律：**契约先行**（先改契约再改实现）、**诚实标注状态**（未实现就标 `planned` / 保留位，不伪造）。

---

## 八、已知缺口与待办（登记）

| # | 事项 | 影响 | 建议动作 | 归属 |
|---|---|---|---|---|
| 1 | **零运行时调用** | 6 份契约、15 个 RPC 全部空转 | 不是缺陷，但**必须防止被读成"已实现"**；本文档即为此而写 | 项目侧 |
| 2 | **生成物同步无门禁** | 改契约忘重生成 → 两侧静默漂移 | CI 加 `-Pproto-gen` + `git diff --exit-code` | 工程侧 |
| 3 | **Python 生成物未入库且无 CI 步骤** | 换机后 `generated/` 整个缺失，且不会被任何检查发现 | 在 CI 加 `bash scripts/proto-gen.sh` + 导入自检；或在 `requirements.txt` 声明 `grpcio-tools` | 工程侧 |
| 4 | **`grpcio-tools` 未写进 `requirements.txt`** | 重建 venv 后生成脚本报 `No module named grpc_tools` | 补进 `requirements.txt` | 工程侧 |
| 5 | **proto 头部 DEBT 注释滞后** | `session.proto` 说"内存 Map"（实际已 Redis）、`brain.proto` 归属模块写错、`body.proto` 说"本地文件"（实际内存 Map）；且这些注释**原样复制进 90 个生成类** | 改注释 → 重生成 → 提交产物（需构建窗口，见台账 §6-2） | 需构建窗口 |
| 6 | **`proto-gen.sh` 路径 bug** | 本机 Git Bash 下脚本**完全不可用**（退出码 1） | ✅ **2026-09-17 已修复并复测** | 已完成 |
| 7 | 仅 `health.proto` 带 `go_package` | 若将来生成 Go 代码，5 份契约缺选项 | 统一补齐或明确不产 Go | 设计侧 |
| 8 | 五渠道 / 消息链路无集成测试 | gRPC 接入时无可参照的测试范式 | 见 `docs/技术债台账.md` DEBT-006/009 | 项目侧 |
| 9 | `Phase0-开发执行日志.md` 称"Python 12 文件已入库" | 与 `.gitignore` 事实不符 | 已在技术债台账登记为口径漂移（历史归档按约定**不回改**，在当期文档纠正） | 已完成登记 |

---

## 九、一页速查

```text
契约源        proto/<域>/v1/<name>.proto        6 份 367 行
Java 产物     services/java/proto-contracts/src/main/java/com/agent/<域>/v1/   90 个，已入库
Python 产物   services/python/nlp-service/generated/<域>/v1/                    12 个，不入库
生成 Java     cd services/java && ../../scripts/mvn-dev.sh -pl proto-contracts -am clean package -Pproto-gen
生成 Python   bash scripts/proto-gen.sh
校验          E:/software/anaconda3/python.exe scripts/contract-check.py
当前使用      0 个服务引用（服务间走 HTTP + NATS/Kafka）
gRPC 9091-9094  用的是 grpc-services 自带 HealthStatusManager，与本仓库 proto 无关
改契约要点    字段号只增不改；改完两侧都要重生成；proto 注释会进生成物
```

---

*本文件建立于 2026-09-17 · 维护：探索者一号（WorkBuddy 侧）*
*用法：契约相关改动**必须**同步本文档 §二 清单与 §三 引用矩阵*
*状态核实方式：逐项对源码取证据（grep 命令 + 实测输出），非沿用既有文档描述*
