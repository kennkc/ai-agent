# Phase 3 躯体期 · 阶段性报告

| 项 | 内容 |
|---|---|
| 阶段 | Phase 3 · 躯体期（Body Stage） |
| 项目 | Agent-Lifeform · AI Agent 生命体架构 |
| 仓库 | `E:\AI\ai-agent`（开发分支 `dev`，跟踪 `codex/main`） |
| 分支 | `dev` / `codex/main` / `workbuddy/main`（三分支同内容对齐） |
| 报告日期 | 2026-09-17 |
| 代码基线 | `a5e0cd5` `feat(phase3): 躯体期知识入库与语义检索（R3-01~R3-09 + R-C03）` |
| 需求范围 | R3-01 ~ R3-09（依据《Phase3-躯体期 · 需求设计文档》v1.6）+ 终端线 R-C03 |
| 阶段结论 | **通过**（Must 项 7/7 全通过；Should 项 2/2 通过；1 项外部引擎依赖诚实降级，见 §6） |

---

## 1. 阶段目标回顾

Phase 2（感官期）打通了「五感采集 → 质检 → 隔离暂存」，但生命体**采到数据却无处安放**——
暂存区里的数据没有下游，检索仍是 `BodyStore` 的**内存 Map + 字符命中率打分**（DEBT-001 🔴）。

Phase 3 的目标是**长出躯体**：让知识能够被**持久化、向量化、语义检索**，并让感官采集的数据
经事件总线自动流入知识库，形成「感官 → 躯体」的闭环。

设计文档定义的活动流程（`Phase3-躯体期/需求设计文档.md` §4~§6）：

```
入库：文档 → 分块器(标题/段落边界 800/50) → 嵌入服务(768维) → 三级存储
      (热:Redis 温:Qdrant 冷:PostgreSQL) → 元数据落库 → 检索就绪
检索：查询 → 嵌入 → 缓存优先(Redis) ──命中──→ 重排 → 返回 TOP-K
                              └──未命中──→ Qdrant 向量召回 → 重排 → 回填缓存 → 返回
生成：检索 TOP-K → RAG 管道 → 回答 + 可回溯引用
```

本阶段的全部实现即围绕这条链路落地，并**销掉 DEBT-001**（内存检索 → Qdrant 向量检索）。

---

## 2. 交付物清单

### 2.1 body-service（Java 主服务，重写：26 主源 + 7 测试类，主源 2411 行 / 测试 635 行）

| 包 | 交付物 | 对应需求 |
|---|---|---|
| `chunk` | `ChunkProcessor`（标题/段落边界分块，标题入块，同标题合并，重叠尾巴） | R3-02 |
| `client` | `OutboundHttp`（HTTP/1.1 出站）、`EmbeddingClient`、`RerankClient`、`QdrantClient` | R3-03 / R3-06 |
| `store` | `MetadataStore`（抽象）+ `PgMetadataStore` + `InMemoryMetadataStore`、`HotCacheStore`（Redis） | R3-01 |
| `store` | `TierRouter`（热度分层）、`StorageFacade`（统一增删改查门面） | R3-01 |
| `service` | `IngestService`（分块→嵌入→入库）、`RetrievalService`（缓存→召回→重排）、`RagPipeline`、`KnowledgeMetrics` | R3-04/05/07/08 |
| `event` | `SenseCollectedConsumer`（消费 `lifeform.sense.collected`，感官→躯体闭环） | R3-09 |
| `controller` | `KnowledgeController`（REST API：入库/检索/统计/列表/删除/健康） | R3-09 |
| `common` | `ErrorCode`、`BizException`、`GlobalExceptionHandler`（统一错误响应） | 一致性 |
| `config` | `BodyStorageConfig`（三层存储装配，DataSource 缺失时稳健降级） | R3-01 |

> **删除**：`BodyController`、`BodyStore`、`BodyStoreTest`（旧内存检索实现，被 `KnowledgeController` + `StorageFacade` 取代 —— 即 DEBT-001 的销项）。

### 2.2 nlp-service（Python，新增 3 模块 + 3 测试文件，共 763 行）

| 文件 | 交付物 | 对应需求 |
|---|---|---|
| `app/embedding.py` | 嵌入服务：BGE-M3 可用则用，否则**确定性 hash n-gram 后端诚实降级**（768 维），含延迟 P95 统计 | R3-03 |
| `app/reranker.py` | 重排服务：交叉编码器可用则用，否则词项重合度重排（可量化提升） | R3-06 |
| `app/chunking.py` | 分块器（与 Java 侧**同算法**，供跨语言一致性校验） | R3-02 |
| `app/main.py` | 新增端点 `/api/nlp/embed`、`/api/nlp/embed/health`、`/api/nlp/rerank`、`/api/nlp/rerank/health`、`/api/nlp/chunk` | R3-03/06 |

### 2.3 session-manager（会话侧切换语义检索）

- `BodyClient.retrieve()` 显式声明 `top_k` 与缓存优先语义，对齐 R3-08 口径；body-service 不可用时降级为空结果（不阻断会话）。

### 2.4 wp-bff（工作平台 BFF，新增躯体视图代理）

- 新增 `GET /api/wp/knowledge`（知识统计 + 检索质量 + 三层存储状态）与
  `POST /api/wp/knowledge/search`（检索测试，含**命中片段高亮**与**查询词解析**）两个端点，
  并登记进 `IMPLEMENTED_ENDPOINTS`（实现端点数 6 → 8）。

### 2.5 work-platform（前端躯体视图 R-C03）

- 新增 `KnowledgeView.vue`：知识总量面板（文档/切片/向量点）、检索质量面板（P99/命中率/缓存命中率）、
  三层存储健康度、检索测试框（输入查询 → 展示 TOP-K 命中 + 高亮片段 + 召回分/重排分对照）。
- 模块登记（`modules.ts` / `router/index.ts` / `types/index.ts` / 图标映射）同步补齐。

### 2.6 契约与配置

- `contracts/work-platform-bff-openapi.yaml`：新增两个端点定义 + `KnowledgeStats` / `KnowledgeSearchResult` schema，均标 `x-wp-status: implemented`。
- `services/java/body-service/src/main/resources/application.yml`：Redis / PostgreSQL / Qdrant / Kafka / OTLP 全量配置，PG 默认凭据对齐 `docker-compose.yml`（`agent/agent123`）。

---

## 3. 关键设计决策

| # | 决策 | 理由 |
|---|---|---|
| D1 | **诚实降级而非伪造**：嵌入/重排引擎缺失时走确定性算法，并在健康端点上报 `backend` 与 `degraded` | 沿用 Phase 2 OCR 的诚实口径——宁可标"降级"，不伪造能力 |
| D2 | **跨语言同算法分块**：Java `ChunkProcessor` 与 Python `chunking.py` 保持同一套边界规则 | 入库在 Java、校验在 Python，同算法才能互相验证 |
| D3 | **Qdrant point id 用 name-based UUID**：`UUID.nameUUIDFromBytes(docId#index)` | E2E 暴露真实缺陷——Qdrant 只接受整数或 UUID，且 name-based 保证**重入库幂等**（同文档同切片得到同 id，覆盖旧点） |
| D4 | **缓存指纹 = 租户 + 归一化问题**（去空白/小写/去尾标点）SHA-256 | 同一问题的不同书写命中同一缓存；租户前缀独立，便于按租户失效 |
| D5 | **热度分层可配置**：`TierRouter` 按访问计数与文档新鲜度决定冷/温/热 | 满足 R3-01「分层规则可配置」，不写死阈值 |
| D6 | **事件携带正文**：扩展 Phase 2 `SenseEventPublisher` 的 payload 增加 `title`/`content` | 事件只带 `staging_ref` 时躯体层需回读暂存（需 MinIO 在线）；携带正文使闭环自洽，大文档回读登记为后续债 |

---

## 4. 端到端验证（2026-09-17 实测）

中间件（Qdrant 1.x / Redis / PostgreSQL / Kafka / Jaeger）全部启动，body-service + nlp-service + wp-bff 联调：

| 验收项（DoD） | 实测 | 结论 |
|---|---|---|
| 文档入库 → 向量检索命中（语义相近可召回） | 入库 3 篇 → 检索 TOP-5 首条来自相关文档，命中 Redis/Qdrant/PostgreSQL 关键片段 | ✅ |
| 检索 P99 < 500ms | **366ms**（样本 40） | ✅ |
| RAG 管道端到端可用 | 回答引用知识库内容，引用带可回溯字段（`generator=template`，如实标注 DEBT-002 未闭合） | ✅ |
| 租户数据隔离 | tenant-a 文档对其他租户不可见；tenant-b 文档不泄漏给 default | ✅ |
| 缓存命中 ≥ 30%（Should） | **45%**（miss 28.8ms vs hit P50 23.1ms） | ✅ |
| 感官 → 躯体闭环（R3-09） | 向 Kafka `lifeform.sense.collected` 发布带正文事件 → body-service 消费 → 入库 → **可被语义检索召回** | ✅ |
| 重入库幂等 / 删除清理 | 重入库同 doc 切片数受控（3，无累积）；删除后文档不再召回，向量点同步清理（6 → 5，无孤儿） | ✅ |

**端到端验收脚本：35 项 PASS / 0 FAIL**（脚本见 `.workbuddy/tmp/phase3-e2e.py`，不入库）。

---

## 5. 测试基线（2026-09-17）

| 层次 | 命令 | 结果 |
|---|---|---|
| Java 全量单测 | `services/java && ../../scripts/mvn-dev.sh -pl body-service -am test` | **100 通过 / 0 失败**（gateway 2 · session 10 · sense 48 · body 40） |
| Python nlp-service | `venv/Scripts/python.exe -m pytest` | **52 通过**（意图 15 + OCR 4 + 分块 12 + 嵌入 13 + 重排 8） |
| wp-bff | `node --test` | **22 通过 / 0 失败** |
| 契约校验 | `python scripts/contract-check.py --work-platform` | 实现端点 8 ↔ BFF 8，**0 FAIL** |
| Java 文档覆盖 | `python scripts/java-doc-coverage.py` | body-service 33/33，全仓 **100/100** |
| 前端 | `vue-tsc --noEmit` + `vite build` | 类型检查通过、构建通过 |

> body-service 由 Phase 2 的 1 项测试扩至 **40 项**（新增分块、分层、缓存、元数据、入库、检索、Qdrant 共 7 个测试类）。

---

## 6. 问题与解决

| # | 问题 | 根因 | 解决 |
|---|---|---|---|
| P1 | **Qdrant 拒绝入库**：`Point id must be a valid UUID or integer` | 入库用 `docId#index` 字符串作 point id，Qdrant 不接受 | 改为 `UUID.nameUUIDFromBytes(...)`（D3），并补 `QdrantClientTest` 守住该不变量 |
| P2 | **body-service 起在了错误端口**（53568 而非 8083） | 环境注入了 WorkBuddy 路由变量，Spring 松散绑定把它们解析成了 `server.port` | 启动时显式 `--server.port=8083`；配置侧不再依赖裸 `PORT` 变量 |
| P3 | **PG 冷层不可用**，回落内存 | 配置默认凭据 `lifeform/lifeform` 与 `docker-compose.yml` 的 `agent/agent123` 不符 | 默认值改为对齐 compose（D6 同批次） |
| P4 | **分块器内容覆盖不全 / 跨标题合并** | 初版把标题剥离到块外、合并仅限长度 | 标题入块 + 同标题内合并 + 重叠尾巴（Java/Python 同步修正，补断言） |
| P5 | 打包被运行中进程占用（Windows 文件锁） | jar 正被运行中的实例锁定 | 先停 8083 实例再 `package` |
| P6 | `--data-binary @/路径` 请求体被破坏 | MSYS 路径转换把 `/tmp/xx.json` 改写成 Windows 路径 | 改用 stdin（`--data-binary @-`）传输请求体 |

---

## 7. 遗留项与技术债

| 项 | 状态 | 说明 |
|---|---|---|
| **DEBT-001**（body 检索为内存实现） | ✅ **本阶段闭合** | 内存 Map → PostgreSQL 元数据 + Qdrant 向量 + Redis 热缓存三层 |
| **DEBT-002**（回答为模板拼接） | 🟡 未闭合 | `RagPipeline` 如实标注 `generator=template`；触发点 VS2（LLM 生成） |
| 嵌入/重排引擎未装 | ⚠️ 诚实降级 | BGE-M3 / 交叉编码器缺失时走确定性算法并上报 `degraded=true`，不伪造 |
| 大文档回读暂存 | 📌 后续债 | 当前事件携带正文；超长正文需回读 MinIO 暂存（登记为后续优化） |
| **DEBT-006 / DEBT-009**（测试缺口） | 🔴 未闭合 | 五渠道集成、消息链路测试，触发点 P2，非本阶段范围 |

---

## 8. 阶段结论

Phase 3 全部 Must 需求（R3-01~R3-05、R3-07）与 Should 需求（R3-06、R3-08）通过，
终端线 R-C03 躯体视图随阶段同步交付，端到端链路（含感官→躯体闭环）实测可用。

核心成果：生命体**从"只有反射的采集器"进化为"能记住、能联想、能引用"的躯体**——
采到的知识被持久化、向量化，并可通过语义相似度被召回，DEBT-001 正式销项。

> 达标 → Phase 3 验收通过 → 下一阶段 Phase 4（大脑期·推理与生成）
