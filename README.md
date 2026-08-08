# Agent-Lifeform · AI Agent 生命体架构

> **Phase 0 胚胎期：基础骨架（进行中）** | 架构基线 v7.4 | 技术栈：Java 21 + Python 3.12 + TypeScript

## 架构概览

```
大脑(会话) → 小脑(编排) → 五官(采集) → 躯体(知识) → 四肢(执行)
    └────────── 脊柱神经(数据总线) ──────────┘
```

## 项目结构

```
agent-lifeform/
├── proto/                    # gRPC 契约（单一事实来源）
│   ├── common/v1/health.proto   # 健康检查
│   ├── brain/v1/brain.proto     # 大脑层：意图/规划
│   ├── sensor/v1/sensor.proto   # 五官层：采集
│   ├── body/v1/body.proto       # 躯体层：检索
│   └── limb/v1/limb.proto       # 四肢层：工具
├── services/
│   ├── java/                 # 核心服务（Spring Cloud Alibaba）
│   │   ├── gateway-service/    # API 网关 (8080)
│   │   ├── session-manager/    # 会话管理 (8081)
│   │   └── sense-service/      # 感官采集 (8082)
│   └── python/
│       └── nlp-service/        # NLP/意图识别 (8000)
├── infra/                    # 基础设施配置
├── scripts/                  # 启动/健康检查脚本
└── docs/demo/                # 每阶段演示脚本
```

## 快速开始

```bash
# 1. 启动基础设施（Redis/PG/Qdrant/NATS/Nacos/MinIO）
./scripts/start.sh infra

# 2. 启动全部（infra + Java + Python + 健康检查）
./scripts/start.sh all

# 3. 健康检查
./scripts/healthcheck.sh
```

## 服务端口

| 服务 | 端口 | 说明 |
|------|:---:|------|
| gateway-service | 8080 | API 网关（Phase 1 启用路由） |
| session-manager | 8081 | 会话管理 |
| sense-service | 8082 | 感官采集 |
| nlp-service | 8000 | 意图识别 |
| Nacos | 8848 | 服务注册/配置 |
| Qdrant | 6333 | 向量检索（Phase 3 启用） |
| NATS | 4222 | 消息总线（Phase 1 启用） |

## 阶段进度

- [x] **Phase 0 胚胎期**：骨架 + proto + 基础栈 + CI
- [ ] Phase 1 神经期：网关路由 + 数据总线 + Trace
- [ ] Phase 2 感官期：多渠道采集 + 意图识别
- [ ] Phase 3 躯体期：知识库 + RAG
- [ ] Phase 4 大脑期：会话 + LLM（M1 问答 MVP）
- [ ] Phase 5 四肢期：工具执行 + 沙箱
- [ ] Phase 6 小脑期：DAG + Saga（M2）
- [ ] Phase 7 免疫期：安全 + 容错 + 监控
- [ ] Phase 8 生命期：反馈学习 + 自愈（M3）

## 演示

每阶段一个演示脚本：`docs/demo/PhaseN-DEMO.md`（当前：`Phase0-DEMO.md`）

## 文档

规划与设计文档见：`E:\ai_workspace\project_space\AI知识库\任务指挥中心知识库\核心知识\`
