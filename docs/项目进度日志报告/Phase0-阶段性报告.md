# Phase 0 胚胎期 · 阶段性报告

| 项 | 内容 |
|---|---|
| 阶段 | Phase 0 · 胚胎期（Embryonic Stage） |
| 项目 | Agent-Lifeform · AI Agent 生命体架构 |
| 仓库 | `E:\AI\ai-agent`（GitHub: kennkc/ai-agent） |
| 分支 | `workbuddy/main`（开发主线）→ 2026-09-12 起本地跟踪分支 `dev` |
| 代码基线 | `c4a842f feat(phase0): 骨架运行验证与修复——Phase0 胚胎期全量验收通过`（2026-08-30） |
| 验收日期 | 2026-08-30（源机）；2026-09-13 本机容器级复测通过 |
| 报告性质 | **追溯性补档**（编制于 2026-09-15；本目录三件套规范建立于 Phase 2，此前阶段按同格式补齐） |
| 阶段结论 | **通过** |

---

## 1. 阶段目标回顾

Phase 0 是生命体的"胚胎期"——**不追求任何业务能力，只把骨架立起来**：
Monorepo 结构 + Maven 多模块构建 + 四类服务（网关/会话/感官/躯体）空壳 + Python FastAPI 骨架 +
docker-compose 基础设施 + 注册与健康检查链路。

验收口径（规划 §1.4 P0 演示）：`./scripts/start.sh` 一键启动，3 服务注册 Nacos，gRPC 健康 SERVING，健康检查全绿。

---

## 2. 交付物清单

| 层次 | 交付物 | 说明 |
|---|---|---|
| 工程 | Monorepo 顶层结构（`proto/ contracts/ services/ web/ infra/ scripts/ docs/`） | 单仓多模块，契约与实现同仓 |
| 工程 | Maven 多模块构建（`services/java` 根 pom） | gateway-service / session-manager / sense-service / body-service / proto-contracts |
| 工程 | Python FastAPI 骨架（`services/python/nlp-service`） | uvicorn 启动，`/api/nlp/intent` 骨架端点 |
| 基础设施 | `docker-compose.yml`（8 中间件） | redis / postgres-pgvector / qdrant / nats / nacos / minio / jaeger / kafka |
| 基础设施 | `infra/README.md` | 基础设施启动与端口说明 |
| 服务 | Nacos 注册配置（4 个 Java 服务） | `application.yml` 统一 NACOS_ADDR |
| 服务 | gRPC 健康服务（端口 9091–9094） | 4 服务各一个 `GrpcHealthServer`，标准 health 协议 |
| 契约 | proto/gRPC 生成（Java + Python 双侧） | 6 个 `.proto`，Java 90 生成源、Python 12 生成文件 |
| CI | `.gitlab-ci.yml`（build → test → package 三段） | contract-check 纳入 CI |
| 运维 | `scripts/start.sh`（一键启动）、`scripts/healthcheck.sh` | 分段启动 + 分组健康检查 |
| 文档 | `docs/demo/Phase0-DEMO.md` | 阶段验收演示脚本 |

---

## 3. 验收对照

| DoD 项（规划 §1.4 P0） | 目标 | 实测 | 结论 |
|---|---|---|---|
| 一键启动 | `start.sh all` 拉起全部 | 基础设施 + 4 Java + nlp-service 全部拉起 | ✅ |
| 服务注册 | 3 服务注册 Nacos | gateway / session / sense（后续 body 也注册） | ✅ |
| gRPC 健康 | SERVING | 9091 / 9093 / 9094（+session 19092）应答正常 | ✅ |
| 健康检查 | 全绿 | `healthcheck.sh` 分组检查全 OK；2026-09-13 复测 **16/16 OK** | ✅ |
| CI | 骨架跑通 | build/test/package 三段 + contract-check | ✅ |
| 3 条冒烟命令 | 全部返回预期 | 建会话 / 采集 / 意图识别均 200 | ✅ |

---

## 4. 本阶段问题与解决记录

| 编号 | 问题 | 根因 | 解决 | 教训 |
|---|---|---|---|---|
| P0-1 | Maven 构建报 `os.detected.classifier` 未解析 | protobuf/grpc 插件需要 `os-maven-plugin` 提供 `${os.detected.classifier}`，未注册为 Maven 扩展 | `78aba15`（2026-09-03）：在根 pom 将 os-maven-plugin 激活为 `<extensions>` | platform-dependent 插件必须先注册扩展再引用 |
| P0-2 | `docker compose up -d` 直接失败 | `docker-compose.yml` 引用 `MINIO_ROOT_USER` 等 4 个变量，缺 `.env` | 从 `.env.example` 生成本地 `.env`；compose 配置校验通过 | 引入环境变量依赖时同步提供 `.env.example` 并在 README 声明 |
| P0-3 | session-manager 启动失败 | Kafka 占用宿主 9092，与 session gRPC 端口冲突 | 启动时设 `GRPC_PORT=19092`（配置支持 `${GRPC_PORT:9092}`） | 容器端口与宿主服务端口要做全局端口预算 |
| P0-4 | 4 个 Java 服务同时抢同一端口 | 宿主残留 `SERVER__PORT` 环境变量被 Spring 宽松绑定识别 | 启动脚本先清除 `SERVER__*` 再拉起服务 | 环境变量污染是 Spring 宽松绑定的隐蔽坑 |
| P0-5 | `.pyc` 缓存曾被误提交 | 构建产物混入仓库 | `c4a842f` 清理并完善 `.gitignore` | 生成物不入库（proto 生成源码是 Phase 2 A-1 的例外决策） |

> P0-2 ~ P0-4 在 2026-08-30（源机）即已解决并固化进启动脚本；2026-09-13 本机复测确认同样规避有效。

---

## 5. 风险与遗留项

| # | 项 | 类型 | 处置 |
|---|---|---|---|
| 1 | `healthcheck.sh` 的 `check_port session-grpc 9092` 实际探到的是 Kafka（应为 19092） | 脚本缺陷 | 建议随 Phase 3 一并修正 |
| 2 | 本机直连 GitHub 不通，clone/fetch 需走镜像 | 环境 | `git push` 尚未验证 |
| 3 | nlp-service 仅有骨架端点，意图识别为 Phase 1/2 内容 | 规划内 | 按阶段推进 |

---

## 6. 阶段结论

Phase 0 胚胎期**达成设计目标**：骨架立起、4 类服务可一键启动、注册与健康检查链路完整、CI 可跑。
生命体自此"有了身体形态"，进入 Phase 1 神经期铺设数据总线。

---

## 附录：关联文档

| 文档 | 位置 |
|---|---|
| 演示脚本 | `docs/demo/Phase0-DEMO.md` |
| 开发执行日志 | 本目录 `Phase0-开发执行日志.md` |
| 测试验收报告 | 本目录 `Phase0-测试验收报告.md` |
| 进度总览 | 项目根 `PROGRESS.md` |

---

*追溯性补档编制：WorkBuddy · 2026-09-15 · 代码基线 `c4a842f`*
