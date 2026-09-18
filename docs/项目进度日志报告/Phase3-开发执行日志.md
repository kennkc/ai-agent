# Phase 3 开发执行日志（归档）

| 项 | 内容 |
|---|---|
| 阶段 | Phase 3 · 躯体期 |
| 记录区间 | 2026-09-17 ~ 2026-09-18（承接 `c3c7aac` 流程文档批次后开始） |
| 仓库 / 分支 | `E:\AI\ai-agent` · `dev`（跟踪 `codex/main`；三分支同内容对齐） |
| 起点提交 | `c3c7aac`（流程文档建设批次，远端 `codex/main`） |
| 终点提交 | `a5e0cd5` `feat(phase3): 躯体期知识入库与语义检索（R3-01~R3-09 + R-C03）`（代码收口）· `b4508e3` `docs(phase3): 躯体期阶段三件套、演示脚本与全局进度归档`（文档收口） |
| 前置动作 | 按 `git-prepull-sync` 技能流程先 `fetch` 并 fast-forward 到 `c3c7aac`，解读流程文档改动后再开工 |

> **时间标注说明**：本机未开启逐条命令时间戳记录；文中带「T」的时间戳来自命令/系统输出，为**精确锚点**。

---

## 一、执行流程总览

```
[Phase A] 前置同步与需求对账      fetch → fast-forward `c3c7aac` → 读《功能开发流程》→ 读 Phase3 四件套
        ↓
[Phase B] Python 侧先行           嵌入服务 → 重排服务 → 分块器 → 端点接线 → pytest
        ↓
[Phase C] Java 侧主体             依赖引入 → 出站/嵌入/重排/Qdrant 客户端 → 分块器 → 三层存储
                                 → 入库/检索/RAG 服务 → 控制器 → Kafka 消费 → 配置
        ↓
[Phase D] 会话与终端线            BodyClient 切语义检索 → wp-bff 躯体端点 → 契约登记
                                 → work-platform 躯体视图 + 模块登记
        ↓
[Phase E] 构建与测试收敛          Maven 编译 → 40 项 JUnit → 52 项 pytest → 22 项 node --test
                                 → 契约校验 → 文档覆盖 → 前端 typecheck + build
        ↓
[Phase F] 端到端验收              启动中间件 + 三服务 → E2E 脚本 35 项 → 感官事件闭环
                                 → BFF 端点实测 → 缺陷修复（Qdrant point id / PG 凭据）
        ↓
[Phase G] 文档归档                模块文档 → 阶段三件套 → DEMO → PROGRESS / 总览 / 台账
```

---

## 二、分阶段执行记录

### Phase A｜前置同步与需求对账

| 步骤 | 动作 | 结果 |
|---|---|---|
| A1 | `git fetch github --prune` + 对比 | 远端 `c3c7aac`（流程文档批次），可 fast-forward |
| A2 | `git merge --ff-only c3c7aac` | 本地 `dev` 更新到 `c3c7aac`，工作区干净 |
| A3 | 解读新提交（`docs/功能开发流程.md` 等） | 确认 8 步开发管线与三分支治理约定 |
| A4 | 读 `AGENT_CONTEXT/Phase3-躯体期/` 四件套 | 确认 R3-01~R3-09 + R-C03 范围与 DoD |
| A5 | 读现有 `body-service` / `session-manager` / `wp-bff` 结构 | 定位改造点：body-service 几乎从零重写 |

### Phase B｜Python 侧先行（复用成本低，先落）

| 步骤 | 动作 | 产物 |
|---|---|---|
| B1 | 写嵌入服务 | `app/embedding.py`：引擎链（BGE-M3 → hash n-gram 768 维确定性降级）+ 延迟统计 |
| B2 | 写重排服务 | `app/reranker.py`：交叉编码器 → 词项重合度降级，可量化提升 |
| B3 | 写分块器 | `app/chunking.py`：标题/段落边界 + 800/50 + 标题入块 + 重叠尾巴 |
| B4 | 接线端点 | `app/main.py` 新增 embed / rerank / chunk 端点与健康探测 |
| B5 | 写测试 | `test_embedding.py`（13）· `test_rerank.py`（8）· `test_chunking.py`（12） |
| B6 | 修正 | 分块器两处：标题被剥离、跨标题合并破坏小节归属 → 改为标题入块 + 同标题合并 |
| B7 | 跑测试 | `pytest -q` → **52 passed** |

### Phase C｜Java 侧主体

| 步骤 | 动作 | 产物 |
|---|---|---|
| C1 | `pom.xml` 引入依赖 | Redis / JDBC / PostgreSQL 驱动 / Kafka 客户端 / OTLP |
| C2 | 出站与客户端 | `OutboundHttp`（HTTP/1.1）、`EmbeddingClient`、`RerankClient`（含真实健康探测）、`QdrantClient` |
| C3 | 统一错误体系 | `ErrorCode` / `BizException` / `GlobalExceptionHandler` |
| C4 | 分块器 | `ChunkProcessor`（与 Python 同算法） |
| C5 | 存储模型 | `DocumentStatus` / `StoredDocument` / `StoredChunk` / `MetadataStore` |
| C6 | 三层存储 | `PgMetadataStore`（冷）+ `InMemoryMetadataStore`（降级）+ `HotCacheStore`（热，租户前缀指纹）+ `TierRouter`（热度分层）+ `StorageFacade` |
| C7 | 装配 | `BodyStorageConfig`（DataSource 缺失时稳健降级，不硬依赖） |
| C8 | 服务层 | `IngestService`（分块→嵌入→入库）、`KnowledgeMetrics`、`RetrievalService`（缓存→召回→重排）、`RagPipeline` |
| C9 | 控制器 | `KnowledgeController`：`POST /api/body/knowledge`、`GET /api/body/knowledge`、`POST /api/body/retrieve`、`POST /api/body/rag`、`GET /api/body/knowledge/stats`、`DELETE /api/body/knowledge/{docId}`、`GET /api/body/health` |
| C10 | 事件闭环 | `SenseCollectedConsumer` 消费 `lifeform.sense.collected` |
| C11 | 扩展事件源 | `SenseEventPublisher` payload 增加 `title`/`content`（D6） |
| C12 | 配置 | `application.yml` 全量配置（Redis/PG/Qdrant/Kafka/OTLP），PG 凭据对齐 compose |
| C13 | 销债 | 删除 `BodyController` / `BodyStore` / `BodyStoreTest`（DEBT-001 销项） |
| C14 | 写测试 | 7 个测试类：分块、分层、缓存、内存元数据、入库、检索、Qdrant id |
| C15 | 修正 | 分块器标题前置；检索测试断言类型与期望值 4 处；`HotCacheStore` 重复方法 |
| C16 | 编译 | `mvn -pl body-service -am compile` → BUILD SUCCESS |
| C17 | 跑测试 | `mvn -pl body-service -am test` → body-service **40 通过** |

### Phase D｜会话与终端线

| 步骤 | 动作 | 产物 |
|---|---|---|
| D1 | 会话切换语义检索 | `BodyClient.retrieve()` 显式 `top_k` + 缓存优先，不可用时降级空结果 |
| D2 | BFF 端点 | `server.js` 新增 `/api/wp/knowledge`（GET）与 `/api/wp/knowledge/search`（POST，含 `buildSnippet` 高亮） |
| D3 | BFF 测试 | `server.test.js` 新增 8 例（端点可用/降级/参数裁剪/高亮窗口/租户透传），共 **22 passed** |
| D4 | 契约登记 | `work-platform-bff-openapi.yaml` + 两个 schema，`x-wp-status: implemented` |
| D5 | 前端类型 | `types/index.ts` 追加 `KnowledgeStats` / `KnowledgeSearchResult` 等 |
| D6 | 前端数据层 | `provider.ts` 新增 `getKnowledgeStats` / `searchKnowledge`（含降级 scope 登记） |
| D7 | 前端视图 | 新建 `KnowledgeView.vue`（知识量 / 检索质量 / 三层存储 / 检索测试高亮） |
| D8 | 模块登记 | `modules.ts` 登记 `knowledge` 模块 + `router/index.ts` 路由 + `Files` 图标映射 |
| D9 | 校验 | `vue-tsc --noEmit` 通过；`vite build` 通过 |

### Phase E｜构建与测试收敛

| 步骤 | 命令 | 结果 |
|---|---|---|
| E1 | `mvn -pl body-service -am test` | 100 / 0（gateway 2 · session 10 · sense 48 · body 40） |
| E2 | `pytest -q` | 52 passed |
| E3 | `node --test` | 22 passed |
| E4 | `contract-check.py --work-platform` | 实现端点 8 ↔ BFF 8，0 FAIL |
| E5 | `java-doc-coverage.py` | body-service 33/33，全仓 100/100 |
| E6 | `vue-tsc --noEmit` + `vite build` | 通过 |

### Phase F｜端到端验收与缺陷修复

| 步骤 | 动作 | 结果 |
|---|---|---|
| F1 | `docker compose up -d qdrant redis postgres kafka jaeger` | 5 个中间件容器在线 |
| F2 | 启动 nlp-service（uvicorn 127.0.0.1:8000） | 嵌入/重排健康端点可用 |
| F3 | 打包并启动 body-service | ⚠️ **起在 53568**（环境变量污染，P2）→ 显式端口重启 8083 |
| F4 | 探测统计接口 | ⚠️ **PG 冷层不可用**（凭据不符，P3）→ 对齐 compose 凭据重启 |
| F5 | 运行 E2E 脚本 | ⚠️ **Qdrant 拒收 point id**（P1）→ 改 name-based UUID |
| F6 | 修复后重跑（清 PG/Qdrant/Redis 残留） | **35 项 PASS / 0 FAIL**（P99 366ms · 命中率 0.925 · 缓存 45%） |
| F7 | 感官闭环验证：向 Kafka 发布带正文事件 | 消费入库成功，且**可被语义检索召回** |
| F8 | BFF 端点实测（:8095） | `GET /api/wp/knowledge` 返回真实三层状态；`POST /search` 返回高亮片段与召回/重排分对照 |
| F9 | 补回归用例 | `QdrantClientTest`（point id 合法性）；`IngestServiceTest` 断言 payload 可读 chunk_id |

### Phase G｜文档归档

| 步骤 | 动作 | 产物 |
|---|---|---|
| G1 | 重写模块文档 | `docs/java-services/06-body-service.md`（Phase 3 版，覆盖 33 源文件） |
| G2 | 阶段三件套 | 本文件 + `Phase3-阶段性报告.md` + `Phase3-测试验收报告.md` |
| G3 | 演示脚本 | `docs/demo/Phase3-DEMO.md` |
| G4 | 全局进度 | `PROGRESS.md` / `docs/项目进度总览.md` 更新 |
| G5 | 技术债 | `docs/技术债台账.md` 更新 DEBT-001 → ✅ 已闭合 |
| G6 | 索引与 HTML | `docs/项目进度日志报告/README.md` 登记；`md2html-report.py` 生成 HTML 孪生 |

---

## 三、提交台账

| # | 提交 | 内容 |
|---|---|---|
| 1 | `a5e0cd5` `feat(phase3): 躯体期知识入库与语义检索（R3-01~R3-09 + R-C03）` | body-service 重写 + nlp-service 嵌入/重排/分块 + 会话切语义检索 + wp-bff 端点 + 契约 + 前端躯体视图 + 测试（67 文件） |
| 2 | `b4508e3` `docs(phase3): 躯体期阶段三件套、演示脚本与全局进度归档` | 阶段三件套 + DEMO + PROGRESS/总览/台账更新 + README 登记（30 文件） |

> 分支治理：提交后推 `dev`，并同步快进 `codex/main` 与 `workbuddy/main`，保持三分支内容对齐。

---

## 四、命令台账（关键命令）

```bash
# 前置同步
cd /e/AI/ai-agent && git fetch github --prune && git merge --ff-only <远端>

# Java 编译与测试
cd services/java && ../../scripts/mvn-dev.sh -pl body-service -am test

# Python 测试
cd services/python/nlp-service && ../venv/Scripts/python.exe -m pytest -q

# wp-bff 测试
cd services/node/wp-bff && node --test

# 契约与文档覆盖
python scripts/contract-check.py --work-platform
python scripts/java-doc-coverage.py

# 前端
cd web/work-platform && node node_modules/vue-tsc/bin/vue-tsc.js --noEmit
cd web/work-platform && node node_modules/vite/bin/vite.js build

# 中间件与端到端
docker compose up -d qdrant redis postgres kafka jaeger
java -jar body-service/target/body-service-0.1.0-SNAPSHOT.jar \
  --server.port=8083 --spring.datasource.username=agent --spring.datasource.password=agent123
python .workbuddy/tmp/phase3-e2e.py
```

---

## 五、环境侧记录

| 项 | 说明 |
|---|---|
| 环境变量污染 | WorkBuddy 注入的路由变量被 Spring 松散绑定误读为端口 → 启动显式 `--server.port` |
| Maven 仓库 | `E:\software\maven-repo`（阿里云镜像），`mvn.cmd` 损坏用 `scripts/mvn-dev.sh` 直调 |
| Python 环境 | `services/python/venv`（补装 `pytest` / `httpx`） |
| 文件锁 | Windows 下运行中的 jar 会锁定 `target/`，打包前需停实例 |
| MSYS 路径 | `curl --data-binary @/tmp/x.json` 会被路径转换破坏 → 用 `--data-binary @-` 走 stdin |
| 端口占用 | 8090 已被既有 wp-bff 实例占用 → 验收改在 8095 进行，不影响用户运行中的服务 |
