# Agent-Lifeform · AI Agent 生命体架构

> 当前基线：Phase 0 / Phase 1 / Phase 2 开发完成，VS1 垂直切片已打通（61 Java tests + 19 Python tests green）。
> 技术栈：Java 21 + Spring Cloud Alibaba + Python 3.12 + FastAPI + Vue 3 + Vite + Element Plus。

---

## 1. 项目定位

Agent-Lifeform 采用“人体生命体”隐喻构建 AI Agent 系统，核心分层包括：

- 大脑层：意图识别、会话状态、任务规划、LLM 推理网关
- 感官层：文本、URL、文件、OCR、语音等采集渠道
- 躯体层：知识库、向量检索、RAG、存储生命周期
- 四肢层：工具调用、代码执行、沙箱
- 小脑层：DAG、Saga、多 Agent 编排
- 免疫层：安全、容错、审批、多租户
- 进化层：反馈学习、知识自愈、模型评测

Phase 2 感官回路（已落地）：

`R0 定时 / R1 指令 → 五感渠道 → 标准化五字段 → 质检 → 隔离暂存(MinIO) → 事件(lifeform.sense.collected) → Console 感官视图`

当前前台主入口为 Vue 3 工作平台：

```text
用户 → work-platform
→ gateway-service
→ session-manager
→ nlp-service
→ body-service
→ 回答与结果工作区
```

---

## 2. 当前技术栈

| 领域 | 选型 |
|------|------|
| Java 服务 | Java 21 + Spring Boot 3 + Spring Cloud Alibaba |
| Python AI 服务 | Python 3.12 + FastAPI |
| 前端 | Vue 3 + Vite + TypeScript + Element Plus |
| 前端状态 | Pinia + TanStack Query for Vue |
| 前端路由 | Vue Router |
| DAG 可视化 | Vue Flow |
| 实时通信 | WebSocket / socket.io-client |
| 数据层 | PostgreSQL + pgvector、Qdrant、Redis、MinIO |
| 消息总线 | NATS JetStream + Kafka |
| 可观测性 | OpenTelemetry + Jaeger + Prometheus + Grafana |
| 契约 | Protobuf/gRPC + OpenAPI |

---

## 3. 目录结构

```text
ai-agent/
├── contracts/                     # BFF OpenAPI 契约可执行副本
├── docs/
│   ├── demo/                      # 各阶段验收演示脚本（Phase0/1/2）
│   ├── 项目进度日志报告/           # 阶段报告、执行日志、测试验收报告（MD+HTML）
│   └── ...
├── infra/                         # 基础设施说明
├── proto/                         # Protobuf 契约（生成 Java 桩已入库 proto-contracts/src/main/java）
├── scripts/                       # 启动、健康检查、契约校验、mvn-dev.sh、md2html-report.py
├── services/
│   ├── java/
│   │   ├── proto-contracts/       # Proto Java/gRPC 生成模块
│   │   ├── gateway-service/       # 网关、JWT、租户透传、gRPC 健康
│   │   ├── session-manager/       # 会话、BusProxy、NATS/Kafka、VS1 编排
│   │   ├── sense-service/         # 五感渠道、R0/R1 采集、质检、暂存、死信
│   │   └── body-service/          # 本地知识检索 MVP
│   └── python/
│       └── nlp-service/           # 规则+L0 级联意图识别、OCR 代理
└── web/
    ├── work-platform/             # Vue 3 + Element Plus 主工作平台
    └── console/                   # 旧静态 Mock 运维参考（Phase 2 感官视图）
```

---

## 4. 快速启动

首次启动基础设施前，复制并填写本地环境变量：

```bash
cp .env.example .env
# 设置 MINIO_ROOT_USER / MINIO_ROOT_PASSWORD
# 以及服务侧 MINIO_ACCESS_KEY / MINIO_SECRET_KEY
```

> `.env`、本地暂存数据、Vite 缓存和构建产物均不会提交到仓库。

### 4.1 Linux / macOS

```bash
# 一键启动基础设施、Java、Python、Vue 前端
./scripts/start.sh all

# 健康检查
./scripts/healthcheck.sh
```

也可以按模块启动：

```bash
./scripts/start.sh infra
./scripts/start.sh java
./scripts/start.sh python
./scripts/start.sh frontend
```

### 4.2 Windows

```bat
scripts\start-dev.bat all
scripts\healthcheck.bat
```

按模块启动：

```bat
scripts\start-dev.bat infra
scripts\start-dev.bat java
scripts\start-dev.bat python
scripts\start-dev.bat frontend
```

### 4.3 单独启动 Vue 工作平台

```bash
cd web/work-platform
npm install
npm run dev
```

默认地址：`http://127.0.0.1:3001`

---

## 5. 安全基线

- 开发环境通过 `JWT_SECRET` 注入本地密钥，生产环境必须使用独立密钥管理系统。
- `/api/auth/token` 仅在开发配置下启用，并限制 loopback 访问。
- 所有业务请求必须携带有效 JWT。
- Gateway 将已验证 JWT 中的 `tenant_id` 写入 `X-Tenant-Id`。
- Session、Sense、Body 服务不得信任 query/body 中的租户字段。
- URL 采集默认禁止 loopback、私网、链路本地、组播和自动重定向。
- 生产环境仍需补充 OAuth2/RBAC、mTLS、Vault/KMS、限流和审计保留策略。

---

## 6. VS1 冒烟测试

```bash
# 1. 获取开发 Token
curl -X POST "http://127.0.0.1:8080/api/auth/token?tenantId=default"

# 2. 创建会话
curl -X POST "http://127.0.0.1:8080/api/session" \
  -H "Authorization: Bearer <token>"

# 3. 写入本地知识
curl -X POST "http://127.0.0.1:8080/api/body/ingest" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"doc_id":"doc-1","title":"架构说明","content":"Agent-Lifeform uses gateway session nlp body services","source":"demo"}'

# 4. 通过 VS1 提问
curl -X POST "http://127.0.0.1:8080/api/session/<session_id>/ask" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"question":"Agent-Lifeform 的架构是什么"}'
```

---

## 7. 验证命令

### Java

```bash
cd services/java
../../scripts/mvn-dev.sh clean package     # 52 tests, 0 failures

cd services/python/nlp-service
../venv/Scripts/python.exe tests/test_intent.py   # 15 passed, L0 holdout 90.0%, P99 0.052 ms
../venv/Scripts/python.exe tests/test_ocr.py      # 4 passed
```

> `scripts/mvn-dev.sh` 是 Maven 直调包装（通过 `plexus-classworlds` 启动），
> 用于规避部分 Windows 主机上 `mvn.cmd` 损坏导致的构建卡死。

### 契约

```bash
python scripts/contract-check.py
python scripts/contract-check.py --work-platform --openapi contracts/work-platform-bff-openapi.yaml
```

### Vue 前端

```bash
cd web/work-platform
npm install
npm run typecheck
npm run build
```

---

## 8. 更新流程

README 不仅是说明文档，也是项目更新流程入口。后续每次修改项目时，必须按以下顺序执行。

### 8.1 设计文档更新

文档源目录：

```text
E:\ai_workspace\project_space\AI知识库\任务指挥中心知识库\核心知识
```

更新步骤：

1. 修改权威 Markdown 文档。
2. 如涉及前端框架、接口、阶段计划或架构决策，补充或更新对应 ADR。
3. 更新 `AGENT_CONTEXT\D3-资源治理\文档版本索引.md`。
4. 执行 HTML 镜像同步：

```bash
cd E:\ai_workspace\project_space\AI知识库\任务指挥中心知识库\核心知识
python md2html.py
python md2html.py --dry-run
```

5. 确认 `done: 0 HTML file(s) would be updated`。
6. 提交文档仓库：

```bash
git add -u
git commit -m "docs: <中文更新说明>"
```

### 8.2 代码更新

1. 从 `codex/phase0-1-hardening` 拉取最新代码。
2. 按阶段或垂直切片修改服务代码。
3. 同步更新对应阶段文档：
   - 需求设计文档
   - 开发设计文档
   - 测试设计案例文档
   - 部署文档
4. 新增或修改接口时同步更新：
   - `proto/`
   - `contracts/`
   - `contract-check.py`
5. 更新 `PROGRESS.md`。
6. 执行验证：

```bash
# Java
cd services/java
JAVA_HOME=<jdk21> mvn test
JAVA_HOME=<jdk21> mvn package -DskipTests

# 契约
python scripts/contract-check.py
python scripts/contract-check.py --work-platform --openapi contracts/work-platform-bff-openapi.yaml

# Vue
cd web/work-platform
npm run typecheck
npm run build
```

7. 提交开发仓库：

```bash
git add -A
git commit -m "feat: <中文更新说明>"
```

### 8.3 前端更新

前端主目录：

```text
web/work-platform
```

更新步骤：

1. 先更新 `src/config/modules.ts` 和 `src/types/index.ts`。
2. 再更新路由、页面、Mock 数据和 `ConsoleDataProvider`/`WorkPlatformDataProvider`。
3. 保持 Mock 与 API 数据结构一致。
4. 新增页面必须同步更新：
   - `src/router/index.ts`
   - `src/layouts/PlatformLayout.vue`
   - `src/views/`
   - 对应 README 或阶段文档
5. 执行：

```bash
npm run typecheck
npm run build
```

6. 确认 `dist/` 构建成功后再提交。

### 8.4 提交前检查

每次提交前必须确认：

```text
[ ] Markdown 已更新
[ ] HTML 镜像已同步
[ ] 版本索引已更新
[ ] PROGRESS.md 已更新
[ ] Maven 测试通过
[ ] 契约检查 0 FAIL
[ ] Vue typecheck 通过
[ ] Vue build 通过
[ ] 未提交 node_modules、dist、target 和本地密钥
```

---

## 9. 当前进度

- [x] Phase 0：骨架、proto、gRPC Health、CI、契约校验
- [x] Phase 1：Gateway、JWT、租户隔离、BusProxy、NATS、Kafka、OTLP/Jaeger
- [x] VS1：session → intent → local retrieval → template answer
- [x] Vue 3 + Element Plus 工作平台工程
- [x] Phase 2：五感渠道、R0/R1 采集、质检、隔离暂存、意图级联（R2-01~R2-10）
- [ ] OCR 端到端验收（需安装 PaddleOCR 或 Tesseract）
- [x] Docker 运行时冒烟（2026-09-13：8 容器 + 4 Java + Python + 前端，healthcheck 16/16，问答链路 200）
- [ ] Phase 3：Qdrant/pgvector 正式知识库
- [ ] Phase 4：LLM Gateway 与 M1 问答 MVP
- [ ] Phase 5-8：工具、编排、免疫、自进化

详细进度见：`PROGRESS.md`
