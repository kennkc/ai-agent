# Phase 0 胚胎期 · 开发执行日志

| 项 | 内容 |
|---|---|
| 阶段 | Phase 0 · 胚胎期 |
| 日志性质 | **追溯性补档**（编制于 2026-09-15，依据 git 提交历史、PROGRESS.md 与 dev 分支分析文档还原） |
| 覆盖区间 | 2026-08-12（设计基线/仓库初始化）→ 2026-08-30（阶段验收）→ 2026-09-03（构建修复） |

---

## 1. 执行时间线

| 日期 | 提交 | 事件 |
|---|---|---|
| 2026-08-12 | `7ceb5d9` `370b67f` `611b7c3` | 仓库初始化：第一期项目骨架、设计文档基线入库（架构 v3.2 于同月定稿） |
| 2026-08-30 | `c4a842f` | **Phase 0 全量验收通过**：骨架运行验证与修复（docker-compose 精简、infra/README、healthcheck 修正、gateway pom 修正、清理误提交的 .pyc） |
| 2026-08-30 | `075f8cb` | Phase 1 神经期主干首批落地（网关鉴权 / NATS / Kafka / 链路追踪 / 统一错误码）——同日完成 P0→P1 切换 |
| 2026-09-03 | `78aba15` | 构建修复：os-maven-plugin 激活为 Maven 扩展，解决 `os.detected.classifier` 未解析 |
| 2026-09-11 | `db0cc27` | `feat: complete phase0 and phase1 development`——P0/P1 收尾（body-service MVP、gRPC health 四服务齐备、JWT 体系重构、VS1 垂直切片、测试补齐，56 文件 +2013/-562） |

## 2. 关键决策记录（ADR 摘录）

| # | 决策 | 依据 |
|---|---|---|
| 1 | 基础设施 8 件套一次配齐（redis/postgres-pgvector/qdrant/nats/nacos/minio/jaeger/kafka） | 规划 §1.7 复杂度分期要求 P0 只引必需组件；实际按"一次配齐、按需使用"执行，避免 Phase 3 再扩 compose 的回归风险 |
| 2 | gRPC 健康服务用各服务内嵌 `GrpcHealthServer`，而非统一 sidecar | 保持服务自包含，healthcheck.sh 可独立探测 |
| 3 | proto 生成物双侧入库（Java 90 文件 + Python 12 文件） | Windows 下 protobuf-maven-plugin 存在删除目录阻塞风险（后续 Phase 2 A-1 证实），CI 不依赖网络下载 protoc |
| 4 | 启动脚本内清理 `SERVER__*` 环境变量、gRPC 端口走 `${GRPC_PORT:9092}` | 规避 Spring 宽松绑定污染与 Kafka 端口冲突（P0-3/P0-4） |

## 3. 命令台账（可复现）

```bash
# 一键启动 + 健康检查
./scripts/start.sh all
./scripts/healthcheck.sh

# Phase 0 演示三条冒烟
curl -X POST http://127.0.0.1:8081/api/session
curl -X POST http://127.0.0.1:8082/api/sense/collect -H 'Content-Type: application/json' -d '{"data_source":"https://example.com"}'
curl -X POST http://127.0.0.1:8000/api/nlp/intent -H 'Content-Type: application/json' -d '{"text":"今天天气怎么样"}'

# Nacos 服务列表（浏览器）
# http://127.0.0.1:8848/nacos （nacos/nacos）
```

## 4. 环境侧记录

| 项 | 记录 |
|---|---|
| Docker Compose | 缺 `.env` 会直接失败；已从 `.env.example` 生成（`MINIO_ROOT_USER` 等 4 变量） |
| 端口 | Kafka 占宿主 9092 → session gRPC 需 `GRPC_PORT=19092` |
| 宿主污染 | `SERVER__PORT=51007` 残留会让 4 个 Java 服务抢同一端口，脚本已固化清除逻辑 |
| Maven | 部分宿主 `mvn.cmd` 损坏 → 后续以 `scripts/mvn-dev.sh` 直调包装（Phase 2 沉淀） |

---

*追溯性补档编制：WorkBuddy · 2026-09-15 · 依据提交 `7ceb5d9…db0cc27` 与 dev 分支分析文档*
