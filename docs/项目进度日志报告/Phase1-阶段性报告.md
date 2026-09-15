# Phase 1 神经期 · 阶段性报告

| 项 | 内容 |
|---|---|
| 阶段 | Phase 1 · 神经期（Neural Stage） |
| 项目 | Agent-Lifeform · AI Agent 生命体架构 |
| 仓库 | `E:\AI\ai-agent`（GitHub: kennkc/ai-agent） |
| 分支 | `workbuddy/main`（开发主线）→ 2026-09-12 起本地跟踪分支 `dev` |
| 代码基线 | `075f8cb feat(phase1): 神经期脊柱数据总线`（2026-08-30）→ `db0cc27 feat: complete phase0 and phase1 development`（2026-09-11，56 文件 +2013/-562） |
| 事后加固 | `701aa3f fix(d1)`（2026-09-13，跨服务调用统一 HTTP/1.1，见 §5） |
| 报告性质 | **追溯性补档**（编制于 2026-09-15；动态验证证据来自 2026-09-12/13 本机复跑） |
| 阶段结论 | **通过**（1 个集成缺陷 D-1 于阶段后复测中发现并修复，不影响阶段目标达成，复盘见 §5） |

---

## 1. 阶段目标回顾

Phase 0 立起了骨架，但器官之间"没有神经"——服务各自为政。Phase 1 的目标是**铺设脊柱神经（数据总线）**：
让请求经统一网关进入、鉴权与租户身份随链路透传、服务间经 NATS 总线通信、事件经 Kafka 发布、
全链路可追踪，并交付第一个垂直切片 **VS1：session → intent → 本地检索 → 模板回答**。

验收口径（规划 §1.4 P1 演示）：请求从浏览器 → 网关 → 总线 → 服务返回，Jaeger 链路可视化，P99 < 500ms。

---

## 2. 交付物清单

### 2.1 gateway-service（脊柱入口）

| 交付物 | 说明 |
|---|---|
| 路由配置 | `/api/session/**` `/api/sense/**` `/api/nlp/**` `/api/body/**` 四路由统一入口 8080 |
| JWT 体系 | `JwtService`（HS384 签发/校验）、`JwtAuthFilter`（过滤器链）、`AuthController`（dev token 端点，**仅 loopback 可用**）、`AuthProperties` |
| 租户透传 | JWT claims → `X-Tenant-Id`，下游服务据此做租户分区与跨租户 403 拦截 |
| 测试 | `JwtServiceTest`（2 用例） |

### 2.2 session-manager（会话与 VS1 编排）

| 交付物 | 说明 |
|---|---|
| `SessionController` | 会话生命周期（创建/查询/发消息/ask），Redis 会话哈希（TTL 2h）+ 消息列表落库 |
| `BusProxy` | **总线抽象**：`bus_channel=nats` 时走 NATS 请求/回应，不可用时优雅降级 `direct` 直连 |
| `NatsClient` | NATS 同步请求/回应客户端 |
| `KafkaEventPublisher` / `KafkaEventConsumer` | 事件发布/消费基线（如 `lifeform.session.created`） |
| `NlpClient` / `BodyClient` | 意图识别与知识检索的跨服务客户端（VS1 编排依赖） |
| VS1 问答链路 | ask → 意图 → 检索 → 模板回答 + citations |
| 统一错误模型 | `ErrorCode` / `BizException` / `GlobalExceptionHandler`（HTTP 状态映射） |
| 测试 | `BusProxyTest` / `SessionControllerTest` 等（10 用例） |

### 2.3 sense-service / body-service（总线接入侧）

| 交付物 | 说明 |
|---|---|
| `NatsBusServer` + `MinimalNatsClient` | sense-service 挂载总线，响应 `lifeform.rpc.sense.ping`（`bus_reply` 携带服务/渠道信息） |
| 统一错误模型 | 与 session-manager 同构的 `ErrorCode`/`BizException`/`GlobalExceptionHandler` |
| body-service 本地检索 MVP | `BodyController`（ingest/retrieve）+ `BodyStore`（内存/本地打分检索），支撑 VS1 |
| gRPC 健康 | 四服务 `GrpcHealthServer` 齐备 |

### 2.4 可观测性与基础设施

| 交付物 | 说明 |
|---|---|
| OpenTelemetry OTLP 配置 | 四个 Java 服务统一导出链路 |
| Jaeger | docker-compose 服务，链路可视化（16686） |
| docker-compose 增补 | Kafka/Jaeger 等服务定义（+33 行） |
| `healthcheck.sh` | 分组健康检查脚本增强 |

---

## 3. 需求覆盖情况

对照 `PROGRESS.md` Phase 1 checklist（10/10）：

| 需求 | 实现位置 | 结论 |
|---|---|---|
| 网关路由（session/sense/nlp/body） | gateway `application.yml` | ✅ |
| JWT 鉴权 + 租户透传（已验证） | `JwtAuthFilter` + 冒烟步骤 2/3/4（401/200/租户归属校验） | ✅ |
| BusProxy 总线抽象 | `session/bus/BusProxy.java` + `BusProxyTest` | ✅ |
| NATS 请求/回应基线 | `NatsClient` + 冒烟步骤 5（bus_reply pong / direct 降级） | ✅ |
| Kafka 生产/消费基线 | `KafkaEventPublisher`/`Consumer` + 冒烟步骤 6（offset=0 自产自销） | ✅ |
| OTLP 配置 + Jaeger | compose 服务 + 四服务 yml | ✅ |
| 统一错误模型与状态映射 | 各服务 `common` 包 | ✅ |
| 跨租户访问保护 | 冒烟负向检查（跨租户读会话 → 403） | ✅ |
| SSRF 防护（URL 采集） | 网关/感官侧校验，loopback 采集被拒（DEMO 负向检查） | ✅ |
| **VS1 垂直切片** | ask 主链路，冒烟步骤 9/10 实测 200/875ms | ✅ |

**覆盖率：10/10 = 100%。**

---

## 4. 关键设计落地说明

### 4.1 脊柱总线：BusProxy 抽象 + 双通道降级

`BusProxy` 把"服务间通信"收口为一个抽象：`bus_channel=nats` 走 NATS 请求/回应；
总线不可用自动降级 `direct` 直连。生命体的神经不是单点依赖——总线挂了，反射仍在。

### 4.2 VS1 垂直切片（先纵后横的第一次落地）

```
ask → 意图识别(NlpClient→nlp-service) → 知识检索(BodyClient→body-service) → 模板回答 + citations
      ↓ 失败降级：意图→FALLBACK 闲聊；检索→空结果（不阻断会话）
```

### 4.3 安全三件套在入口收口

JWT 鉴权（401）→ 租户透传与归属校验（403）→ 出站 SSRF 校验（loopback/metadata 拒绝），
全部在 Phase 1 落地并有负向用例，为后续阶段提供安全底座。

---

## 5. 重大缺陷复盘：D-1（阶段后复测发现，2026-09-13 修复）

> 这是本阶段唯一的功能性缺陷，也是整个项目至今最有价值的教训：**全绿的健康检查掩盖了集成缺陷**。

| 项 | 内容 |
|---|---|
| **现象** | `curl` 直连 nlp-service 200，但 session-manager 调用恒 503；nlp 日志 `422 Unprocessable Entity` + `Unsupported upgrade request` |
| **定位** | JDK 21 单文件最小复现：同一 URL 同一 JSON，HTTP/2 → 422，HTTP/1.1 → 200 |
| **根因** | `RestClient` 默认 `JdkClientHttpRequestFactory`（`HttpClient` 默认 HTTP/2），对明文连接发 `Upgrade: h2c` 升级握手；uvicorn(h11) 不支持 h2c，将请求判非法并**丢弃 body** → FastAPI `422` → Java 包装 503。影响 `NlpClient`（意图）与 `VisualChannel` OCR；`BodyClient` 打 Tomcat 从未受影响（根因再校准，见 dev 分析文档 §8.6） |
| **修复** | 统一出口：session-manager 新增 `OutboundHttp`（HTTP/1.1 + connect 3s/read 10s），`NlpClient`/`BodyClient` 改走该工厂并带降级语义（新错误码 `AGENT_UPSTREAM_UNAVAILABLE`→502）；sense-service 新增 `HttpClients`，`TouchChannel`/`VisualChannel` 同步切换；新增 3 个回归测试类（7 用例：裸 socket 断言无 h2c 头、降级语义、超时断言） |
| **验证** | Java 测试 54→**61 全绿**；`ask` 从 503 → **200/875ms**（模板回答+citations）；消息落库 `LLEN=0`→**2**；杀掉 nlp-service 后 ask 仍 200（FALLBACK 降级）；契约校验无漂移 |
| **教训** | ① 健康检查只探端口/actuator 不足以证明链路可用，**验收必须含业务链路冒烟**（已沉淀为流程要求）；② 跨语言服务调用（Java↔Python/uvicorn）必须显式锁定 HTTP 版本；③ 新增跨服务调用统一走 `OutboundHttp`/`HttpClients`，禁止各自 new RestClient |

## 6. 其他问题记录

| 编号 | 问题 | 解决 | 教训 |
|---|---|---|---|
| P1-1 | `os.detected.classifier` 未解析导致构建失败 | `78aba15`：os-maven-plugin 注册为 Maven 扩展 | platform 插件先注册扩展 |
| P1-2 | dev token 端点的暴露面 | 设计为**仅 loopback 可用**（`DEV_TOKEN_ENDPOINT_ENABLED=true` 时） | 开发便利端点必须有网络层护栏 |
| P1-3 | `healthcheck.sh` session-grpc 探 9092（实为 Kafka） | 已登记 Phase 0 验收报告瑕疵项，Phase 3 顺带修 | 端口预算要集中维护 |

---

## 7. 验证结果汇总

### 7.1 构建

`mvn clean package` → BUILD SUCCESS（2026-09-12/13 本机复跑），全模块 5 个 SUCCESS。

### 7.2 测试（2026-09-13，D-1 修复后）

| 模块 | 用例 | 结果 |
|---|---:|---|
| gateway-service | 2 | ✅ |
| session-manager | 10（含 D-1 回归 2 类 4 用例） | ✅ |
| body-service | 1 | ✅ |
| **Phase 1 相关模块合计** | **13** | **0 失败** |

### 7.3 端到端业务链路冒烟（2026-09-13，10 步）

会话生命周期、租户隔离、JWT 鉴权、NATS 同步总线、Kafka 生产/消费、知识入库与检索、
意图识别与问答主链路、消息落库——**8 条链路全部实测通过**（明细见《Phase1-测试验收报告》§4）。
链路延迟：ask 200 / 875ms（目标 P99 < 500ms 为规划口径，实测单次 875ms 含冷启动，后续随压测基线校准）。

---

## 8. 风险与遗留项

| # | 项 | 类型 | 处置 |
|---|---|---|---|
| 1 | 健康检查不含业务链路探测 | 流程 | 已在验收规范中补"业务链路冒烟"要求 |
| 2 | `healthcheck.sh` 9092 探针名不符实 | 脚本 | Phase 3 顺带修正 |
| 3 | 跨语言调用的 HTTP 版本约束依赖约定 | 工程 | 依赖统一出口类规避；可考虑加 ArchUnit 规则 |
| 4 | 检索为 body-service 本地 MVP，非语义检索 | 规划内 | Phase 3 躯体期替换 |

---

## 9. 阶段结论

Phase 1 神经期**达成设计目标**：

- 脊柱数据总线贯通：网关 → 鉴权 → 租户透传 → NATS/Kafka 双通道 → 四服务挂载；
- 生命体第一次有了**端到端反射弧**（VS1：问 → 意图 → 检索 → 答）；
- 安全三件套（JWT/租户/SSRF）与统一错误模型就位；
- 唯一集成缺陷 D-1 在本机动态验证中暴露并已修复，回归用例固化，教训沉淀为验收流程要求。

---

## 附录：关联文档

| 文档 | 位置 |
|---|---|
| 演示脚本 | `docs/demo/Phase1-DEMO.md` |
| 开发执行日志 | 本目录 `Phase1-开发执行日志.md` |
| 测试验收报告 | 本目录 `Phase1-测试验收报告.md` |
| D-1 完整分析 | `docs/dev分支代码分析与进度验证-2026-09-12.md` §8.4–8.6 |
| 进度总览 | 项目根 `PROGRESS.md` |

---

*追溯性补档编制：WorkBuddy · 2026-09-15 · 代码基线 `db0cc27`（加固 `701aa3f`）*
