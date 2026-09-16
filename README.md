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
| 前端状态 | Pinia（服务端 Query/缓存层待 Phase 3 引入 TanStack Query） |
| 前端路由 | Vue Router |
| DAG 可视化 | 自研 SVG + CSS 拓扑（Vue Flow 待编排期引入） |
| 实时通信 | 当前为 REST 轮询；WebSocket 推送为设计目标，代码尚未接入 |
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
│   ├── java-services/             # Java 服务源码逐文件说明（模块总览/契约/各服务/配置约定，MD+HTML）
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
│   ├── python/
│   │   └── nlp-service/           # 规则+L0 级联意图识别、OCR 代理
│   └── node/
│       └── wp-bff/                # work-platform BFF 最小 Ops（:8090 中间件探针/真实启停、链路追踪观测）
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

### 4.3 单独启动 Vue 工作平台（含观测区）

```bash
# 1) 启动 BFF（观测区的中间件探针/启停与链路追踪数据源，零依赖 Node >= 20）
node services/node/wp-bff/server.js        # 监听 127.0.0.1:8090

# 2) 启动前端（双栈监听，localhost / 127.0.0.1 / 局域网 IP 均可访问）
cd web/work-platform
npm install
npm run dev
```

默认地址：`http://localhost:3001`。开发期 Vite 代理 `/api/wp` → `http://127.0.0.1:8090`。

数据源切换：`web/work-platform/.env` 中 `VITE_DATA_SOURCE=mock|api`（当前开发环境为 `api`）。
API 模式下观测区两页展示真实数据：**中间件监控**（8 容器实时探针、单卡片启停、一键串行启动/终止，真实执行 `docker compose up -d / stop`，8-key 白名单）与**链路追踪**（真实 Jaeger 服务统计与最近链路）。

**数据源降级不再静默**（2026-09-16 起）：BFF 未覆盖或请求失败的数据域仍会回落 Mock，但顶栏会显示 `API · 降级 N` 徽标并在页面顶部给出黄色横幅，点击可查看是哪些数据域、因为什么原因在展示 Mock 数据。

**中间件控制面需要令牌**：`POST /api/wp/middleware/:key/start|stop` 必须同时满足「来源白名单」与「控制令牌」两项校验。令牌优先取环境变量 `WP_BFF_CONTROL_TOKEN`，未配置时由 wp-bff 启动时随机生成到 `services/node/wp-bff/logs/wp-bff-control-token`；开发期 Vite 代理在服务端读取该文件并注入请求头，令牌不会进入浏览器。局域网 IP 访问开发服务器时需把该来源加入 `WP_BFF_ALLOWED_ORIGINS`。详见 `services/node/wp-bff/README.md`。

---

## 5. 安全基线

- 开发环境通过 `JWT_SECRET` 注入本地密钥，生产环境必须使用独立密钥管理系统。
- `/api/auth/token` 仅在开发配置下启用，并限制 loopback 访问。
- 所有业务请求必须携带有效 JWT。
- Gateway 将已验证 JWT 中的 `tenant_id` 写入 `X-Tenant-Id`。
- Session、Sense、Body 服务不得信任 query/body 中的租户字段。
- URL 采集默认禁止 loopback、私网、链路本地、组播和自动重定向。
- 工作平台 BFF 控制面（`wp-bff`）固定绑定 `127.0.0.1`，并叠加来源白名单（Origin/Referer）+ 控制令牌双校验；CORS 不再使用通配符。
- 生产环境仍需补充 OAuth2/RBAC、mTLS、Vault/KMS、限流和审计保留策略；`wp-bff` 的进程级静态令牌需替换为租户级 JWT 鉴权与最小权限模型（Phase 7）。

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
../../scripts/mvn-dev.sh clean package     # 61 tests, 0 failures

cd services/node/wp-bff
node --test                                # 14 passed（控制面安全回归）

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

### 8.0 通用前置：变更前先同步远端（强制）

任何仓库的内容变更（代码 / 文档 / 配置）之前，必须先同步并**解读**远端改动，避免覆盖协作者的工作：

1. **拉取**：`git fetch <remote>` —— 不要直接 `git pull`，先在无副作用状态下解读
2. **判断**：`git rev-list --left-right --count @{u}...HEAD`（输出为 `落后 领先`）
3. **落后 > 0 时必须解读**：
   - `git log --oneline HEAD..@{u}` —— 对方提交主题
   - `git diff --stat HEAD..@{u}` —— 涉及哪些文件
   - 触及本任务相关文件时用 `git show <sha>` 读具体改动
4. **形成结论再动手**：对方改了什么 / 是否与本任务重叠 / 采取何种处理
5. **合并后复验**：merge 或 rebase 之后重跑受影响范围的验证，再继续开发

多仓库一键扫描（只读，不改动工作区）：

```powershell
pwsh -File "$env:USERPROFILE\.agents\skills\git-sync-before-change\scripts\scan-remote-updates.ps1" -Repo "E:\ai_workspace\project_space\ai-agent-lifeform\codex\ai-agent","E:\ai_workspace\project_space\AI知识库\任务指挥中心知识库"
```

该约定已登记为 Codex 技能 `git-sync-before-change`（变更前先同步远端并解读对方改动）。

**冲突处理红线**：按「时间 + 语义」逐项合并，禁止整文件覆盖任一侧。

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

1. 先执行 §8.0 的同步检查（fetch + 解读远端改动），再从 `codex/main` 接续开发。
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

### 8.4 分支与发布治理

截至 2026-09-16，三个长期分支内容对齐、指向同一提交；同日主线更名为 `codex/main`：

| 分支 | 角色与约定 |
|---|---|
| `codex/main` | 本机主开发分支（2026-09-16 起），日常改动先落这里 |
| `dev` | 另一台远端开发机的工作分支，与主线保持同内容 |
| `workbuddy/main` | 主开发线（Phase 0/1/2 + 工作平台 + Java 服务文档集），与主线保持同内容 |
| `codex/phase0-1-hardening` | **历史归档**：Phase 0-2 期间使用的旧主线名，只读保留，不再接续开发 |

> **2026-09-16 决策**：三支保持内容对齐 —— 每次收口时用 `git merge --ff-only` 把
> `codex/main` 同步到 `dev` 与 `workbuddy/main`（推送前必须先 `git fetch`
> 确认对方进度）；吸收其他分支的提交时按「时间 + 语义」逐项合并，不做覆盖式合并。


约定：

1. 任一分支上的改动，收口时通过 `git merge --ff-only` 同步到另两个分支，保持三分支内容一致。
2. 分支名与内容出现偏差时，在阶段收口时新建语义正确的分支并归档，不做历史重写。
   —— 2026-09-16 已按此约定执行：`codex/phase0-1-hardening` 归档，主线切换为 `codex/main`。
3. 每次合并后必须重跑全量验证（Java / Python / 契约 / wp-bff / Vue typecheck+build）再推送。
4. 冲突处理按「时间 + 语义」逐项合并，禁止直接覆盖对方改动。

### 8.5 提交前检查

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
[ ] wp-bff `node --test` 通过（控制面改动时）
[ ] 未提交 node_modules、dist、target、logs 和本地密钥
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
- [x] 观测区与最小 Ops BFF：中间件监控 / 链路追踪（2026-09-15）
- [x] 工程加固（2026-09-16）：wp-bff 控制面鉴权 + CORS 收紧、数据源降级可见化、链路采样口径标注、CI 补 Python/wp-bff 测试、前端路由懒加载
- [ ] Phase 3：Qdrant/pgvector 正式知识库
- [ ] Phase 4：LLM Gateway 与 M1 问答 MVP
- [ ] Phase 5-8：工具、编排、免疫、自进化

详细进度见：`PROGRESS.md`
