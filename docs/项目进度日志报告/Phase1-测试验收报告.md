# Phase 1 神经期 · 测试验收报告

| 项 | 内容 |
|---|---|
| 阶段 | Phase 1 · 神经期 |
| 验收日期 | 静态走查 2026-09-12；**动态验证与业务链路冒烟 2026-09-13**（本机） |
| 报告性质 | 追溯性补档（编制于 2026-09-15） |
| 验收结论 | **通过**（集成缺陷 D-1 已修复并复测，回归用例固化） |

---

## 1. 构建证据

```
mvn clean package（services/java，2026-09-13 本机）
[INFO] proto-contracts / gateway-service / session-manager / sense-service / body-service
[INFO] BUILD SUCCESS
```

## 2. 单元测试（Phase 1 相关模块，D-1 修复后 2026-09-13）

| 模块 | 测试类 | 用例数 | 结果 |
|---|---|---:|---|
| gateway-service | `JwtServiceTest` | 2 | ✅ 0 失败 |
| session-manager | `BusProxyTest` / `SessionControllerTest` / `OutboundHttpTest` / `UpstreamDegradeTest` | 10 | ✅ 0 失败 |
| body-service | `BodyStoreTest` | 1 | ✅ 0 失败 |
| **合计** | | **13** | **0 失败 0 错误** |

> 回归用例说明：`OutboundHttpTest` 用裸 socket 断言出站请求为 HTTP/1.1 且不含 `h2c`/`HTTP2-Settings` 头；
> `UpstreamDegradeTest` 断言上游不可用时默认降级（意图→FALLBACK、检索→空结果）与 `degrade-on-failure=false` 时 502 语义。

## 3. 健康检查（容器级）

```
[infrastructure]   8/8 OK（redis/postgres/qdrant/nats/nacos/minio/kafka/jaeger）
[java services]    4/4 HTTP 200（gateway 8080 / session 8081 / sense 8082 / body 8083）
[grpc health]      9091 / 19092 / 9093 / 9094
[python+frontend]  nlp-service 8000 / work-platform 3001
healthcheck.sh 合计 16/16 OK；Nacos 服务发现 4 服务互见
```

## 4. 端到端业务链路冒烟（2026-09-13，10 步实录）

| # | 步骤 | 结果 |
|---|---|---|
| 1 | `POST /api/auth/token?tenantId=default`（dev 端点） | ✅ 200，HS384 JWT（loopback-only 校验生效） |
| 2 | 无 token 调 `POST /api/session` | ✅ 401 `AGENT_UNAUTHORIZED`（鉴权生效） |
| 3 | `POST /api/session`（带 token） | ✅ 200 返回 `session_id`；Redis `session:{id}` 哈希 6 字段、TTL 2h |
| 4 | `GET /api/session/{id}` | ✅ 200，`status=ACTIVE`、`message_count=0`，租户归属校验生效 |
| 5 | NATS 总线 `lifeform.rpc.sense.ping` | ✅ `bus_reply={"service":"sense-service","status":"pong","channel":"TOUCH"}`；停总线优雅降级 `direct` |
| 6 | Kafka 事件 `lifeform.session.created` | ✅ 生产 offset=0，KafkaEventConsumer 消费到同一 payload |
| 7 | `POST /api/body/ingest` | ✅ 200，`chunk_count=3`（直连与经网关均通过） |
| 8 | `POST /api/body/retrieve` | ✅ 200，命中 2 个 chunk |
| 9 | `POST /api/session/{id}/ask` | ✅ **200 / 875ms**，`intent=闲聊` + 模板回答 + 1 条 citations（D-1 修复前 503） |
| 10 | 消息落库 `session:{id}:messages` | ✅ `LLEN=2`、`message_count=2`（user+assistant，latency_ms=171） |

**结论：8 条链路（会话生命周期 / 租户隔离 / JWT 鉴权 / NATS 总线 / Kafka 收发 / 知识入库检索 / 意图问答 / 消息落库）全部实测通过。**

## 5. 负向安全用例（Phase1-DEMO）

| 用例 | 预期 | 实测 |
|---|---|---|
| 无 token 访问受保护端点 | 401 | ✅ |
| 跨租户读会话 | 403 | ✅ |
| SSRF：采集 loopback 地址（`http://127.0.0.1:8080/actuator/health`） | 拒绝 | ✅（出站守卫拦截） |

## 6. 缺陷记录与回归

| 编号 | 缺陷 | 修复 | 回归证据 |
|---|---|---|---|
| D-1 | JDK HttpClient 默认 HTTP/2 对明文连接发 `Upgrade: h2c`，uvicorn(h11) 判为非法并丢弃 body → Java 侧 503（影响意图识别与 OCR 调用链） | 统一出口锁定 HTTP/1.1（`OutboundHttp`/`HttpClients`）+ 超时 + 降级 + `AGENT_UPSTREAM_UNAVAILABLE` | 冒烟步骤 9/10 修复前后对照（503→200/875ms，LLEN 0→2）；杀掉 nlp-service 后 ask 仍 200（FALLBACK）；Java 测试 54→61 全绿；契约校验 FAIL 0 无漂移 |

## 7. DoD 对照总表

| DoD（规划 §1.4 P1） | 判定 |
|---|:---:|
| 请求从浏览器/网关 → 总线 → 服务返回，链路可追踪 | ✅（Jaeger 16686 可视化） |
| 链路 4 节点可见 | ✅（gateway→session→nlp/body） |
| P99 < 500ms | ⚠️ 单次实测 875ms（含首次冷启动与容器网络）；系统性 P99 待 Phase 3 压测基线（V1-1 性能基准实验室）校准，**诚实登记为待补实测** |
| 演示脚本可重复执行（Phase1-DEMO.md） | ✅ |
| 业务链路冒烟纳入验收（D-1 教训沉淀） | ✅（本报告 §4） |

## 8. 待补验证项（诚实留痕）

| # | 项 | 原因 | 计划 |
|---|---|---|---|
| 1 | 链路 P99 系统性实测 | 需压测工具与场景（V1-1 设计已备） | Phase 3 起建立压测基线 |
| 2 | `git push` 真实 GitHub 凭据 | 本机直连 GitHub 不通 | 需要远程协作时验证 |

## 9. 结论

Phase 1 验收**通过**。13 项单测全绿、16/16 健康检查通过、8 条业务链路冒烟实测通过、3 项负向安全用例通过；
唯一集成缺陷 D-1 已修复并固化 7 个回归用例。

---

*追溯性补档编制：WorkBuddy · 2026-09-15 · 动态证据引自 `docs/dev分支代码分析与进度验证-2026-09-12.md` §八（2026-09-13）*
