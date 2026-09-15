# Phase 1 神经期 · 开发执行日志

| 项 | 内容 |
|---|---|
| 阶段 | Phase 1 · 神经期 |
| 日志性质 | **追溯性补档**（编制于 2026-09-15，依据 git 提交历史、PROGRESS.md、dev 分支分析文档及 2026-09-12/13 本机复跑记录还原） |
| 覆盖区间 | 2026-08-30（主干落地）→ 2026-09-11（阶段收尾）→ 2026-09-13（动态验证与 D-1 修复） |

---

## 1. 执行时间线

| 日期 | 提交 | 事件 |
|---|---|---|
| 2026-08-30 | `075f8cb` | **神经期主干首批落地**（22 文件 +900/-6）：gateway JWT 体系（AuthController/JwtAuthFilter/JwtUtil）、session-manager 总线（BusProxy/NatsClient/KafkaEventPublisher）、sense-service 总线接入（MinimalNatsClient/NatsBusServer）、三服务统一错误模型、compose 增补 Kafka |
| 2026-09-03 | `78aba15` | 构建修复：os-maven-plugin 激活为 Maven 扩展（`os.detected.classifier` 未解析） |
| 2026-09-11 | `db0cc27` | **P0/P1 收尾**（56 文件 +2013/-562）：body-service MVP（ingest/retrieve + BodyStore）、四服务 gRPC health 齐备、JWT 重构（JwtService/AuthProperties，dev token 仅 loopback）、VS1 垂直切片（SessionController ask 编排 + NlpClient/BodyClient）、KafkaEventConsumer、测试补齐（BusProxyTest/SessionControllerTest/JwtServiceTest/BodyStoreTest/TouchChannelTest） |
| 2026-09-12 | —（分支） | `git checkout -b dev origin/workbuddy/main`：创建本地跟踪分支 dev（基线 `9385f23`），并完成静态代码走查（确认真实现非占位，无 TODO 残留） |
| 2026-09-12 | — | 本机工具链装齐（JDK 21.0.12.1 / Maven 3.9.11 / Node 22.23.2 / Docker Desktop 4.4.4），首次本机构建通过 |
| 2026-09-13 | `c5f11cd` 前后 | **容器级动态验证**：8 基础设施容器 + 4 Java + nlp-service + work-platform 全部起通，healthcheck 16/16 OK；业务链路冒烟发现 **D-1**（Java→Python 调用 422/503） |
| 2026-09-13 | `701aa3f` | **D-1 修复**：跨服务调用统一锁定 HTTP/1.1（新增 `OutboundHttp`/`HttpClients`），补齐超时/降级/错误码（`AGENT_UPSTREAM_UNAVAILABLE`）与 3 个回归测试类；`ask` 冒烟复测 200/875ms |
| 2026-09-13 | `717d778` | 文档归档：端到端动态验证结果与 D-1 分析补入 dev 分支分析文档 §八 |
| 2026-09-13 | `30644bc` | 合并 `codex/phase0-1-hardening`：前端工作平台迁移 Vue3 + Element Plus（含 Overview 驾驶舱等模块展示） |

## 2. 关键决策记录（ADR 摘录）

| # | 决策 | 依据 / 后果 |
|---|---|---|
| 1 | **BusProxy 抽象 + 双通道降级**（nats/direct） | 神经总线不做单点依赖；实测总线不可用时自动回落直连 |
| 2 | **JWT 用 HS384 + dev token 仅 loopback** | 单机开发阶段对称签名够用；开发便利端点加网络层护栏 |
| 3 | **统一错误模型在 Phase 1 收口**（ErrorCode/BizException/GlobalExceptionHandler 三服务同构） | 后续服务直接复用；跨服务错误语义统一（Phase 1 末补 `AGENT_UPSTREAM_UNAVAILABLE`→502） |
| 4 | **跨服务 HTTP 客户端统一出口**（session `OutboundHttp`、sense `HttpClients`） | D-1 教训的工程化落地：HTTP/1.1 + 超时 + 降级默认开 |
| 5 | **VS1 先纵后横**：先打穿"输入→处理→输出"再补横切面 | 规划 §1.3 原则的首次执行；VS2+ 在此骨架上叠加 |
| 6 | **前端主线并入**（codex/phase0-1-hardening → workbuddy/main） | 结束双线分叉；work-platform 成为统一入口基线 |

## 3. 命令台账（可复现）

```bash
# 构建 + 测试（Phase 1 相关模块）
cd services/java && ../../scripts/mvn-dev.sh clean package

# 端到端演示（需 Docker 基础设施）
./scripts/start.sh all && ./scripts/healthcheck.sh
# 按 docs/demo/Phase1-DEMO.md：dev token → 建会话 → ingest → ask → 负向检查（401/403/SSRF）

# Jaeger 链路
# http://127.0.0.1:16686
```

## 4. 环境侧记录

| 项 | 记录 |
|---|---|
| Docker Desktop | 本机 Win10 19041，最高可用 4.4.4，**勿升级**（4.49+ 需 19044/19045） |
| 宿主端口 | Kafka 9092 与 session gRPC 冲突 → `GRPC_PORT=19092` |
| 宿主污染 | 启动前清除 `SERVER__*` 环境变量 |
| 网络 | 直连 GitHub 不通，fetch 走镜像；push 需真实凭据（未验证） |

---

*追溯性补档编制：WorkBuddy · 2026-09-15 · 依据提交 `075f8cb…30644bc` 与动态验证记录*
