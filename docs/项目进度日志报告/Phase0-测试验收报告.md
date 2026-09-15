# Phase 0 胚胎期 · 测试验收报告

| 项 | 内容 |
|---|---|
| 阶段 | Phase 0 · 胚胎期 |
| 验收日期 | 2026-08-30（源机验收）；**2026-09-13 本机容器级复测通过** |
| 报告性质 | 追溯性补档（编制于 2026-09-15） |
| 验收结论 | **通过** |

---

## 1. 验收方式

Phase 0 无业务逻辑，验收以**启动/注册/健康/冒烟**四类可观测证据为主，辅以 CI 流水线与契约校验。

## 2. 逐项验收证据

### 2.1 启动与健康检查（`scripts/start.sh all` + `scripts/healthcheck.sh`）

2026-09-13 本机复测（Docker Desktop 4.4.4 / JDK 21 / Maven 3.9.11）：

```
[infrastructure]   redis 6379 / postgres 5432 / qdrant 6333 / nats 4222 /
                   nacos 8848 / minio 9000 / kafka 9092 / jaeger 16686    8/8 OK
[java services]    gateway 8080 / session 8081 / sense 8082 / body 8083    4/4 HTTP 200
[grpc health]      9091 / 19092 / 9093 / 9094                              应答正常
[python+frontend]  nlp-service 8000 / work-platform 3001                   OK
合计 16/16 OK
```

### 2.2 Nacos 注册

`http://127.0.0.1:8848/nacos` 服务列表可见 gateway-service / session-manager / sense-service / body-service，
4 个服务互相可见（DoD 要求 ≥3，实际 4）。

### 2.3 冒烟命令（Phase0-DEMO §第3步）

| 命令 | 预期 | 实测 |
|---|---|---|
| `POST :8081/api/session` | 200，返回 `session_id`/`ACTIVE` | ✅（2026-09-13 经网关复测亦通过） |
| `POST :8082/api/sense/collect` | 200，`source_channel=TOUCH` | ✅（URL 采集链路可用） |
| `POST :8000/api/nlp/intent` | 200，返回意图 | ✅（骨架端点；规则级联为 Phase 2 内容） |

### 2.4 CI 与契约

| 项 | 结果 |
|---|---|
| `.gitlab-ci.yml` 三段流水线（build → test → package） | 骨架跑通 |
| `contract-check.py` | FAIL 0（2026-09-13 复测 FAIL 0 / WARN 3，WARN 为格式提示） |

## 3. DoD 对照总表

| DoD（规划 §1.4 P0） | 判定 |
|---|:---:|
| 3 服务 + 基础栈一键启动，健康检查全绿 | ✅（4 服务，超出要求） |
| 3 服务注册，gRPC 健康 SERVING | ✅ |
| 演示时间 < 10 分钟 | ✅ |
| 演示脚本可重复执行（Phase0-DEMO.md） | ✅ |

## 4. 已知瑕疵（诚实留痕）

| # | 项 | 影响 | 处置建议 |
|---|---|---|---|
| 1 | `healthcheck.sh` 中 `check_port session-grpc 9092` 实际探到 Kafka；session gRPC 真实端口为 19092 | 检查项名不符实（非漏检：19092 已在 gRPC 分组覆盖） | Phase 3 顺带修正 |
| 2 | 健康检查仅探端口/actuator，**不触发跨服务调用** | 后续证明会掩盖集成级缺陷（Phase 1 的 D-1 即因此漏网） | 教训沉淀：验收必须包含业务链路冒烟，Phase 1 起执行 |

## 5. 结论

Phase 0 验收**通过**。骨架、注册、健康、CI 四类证据齐备且在本机可复现。

---

*追溯性补档编制：WorkBuddy · 2026-09-15 · 复测证据引自 `docs/dev分支代码分析与进度验证-2026-09-12.md` §八（2026-09-13）*
