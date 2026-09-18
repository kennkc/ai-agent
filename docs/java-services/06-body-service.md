# 06 · body-service 躯体服务（Phase 3 躯体期）

> 模块路径：`services/java/body-service/`
> 源文件：**26 个主代码（2411 行）+ 6 个测试（635 行）**
> HTTP 端口：**8083** · gRPC 端口：**9094**
> 外部依赖：PostgreSQL（冷层真相源）· Redis（热层缓存）· Qdrant（温层向量库）· Kafka（感官事件消费）· nlp-service（嵌入/重排）

## 1. 模块职责

`body-service` 是生命体的**知识与记忆的躯体**。Phase 3 起承担三件事：

1. **知识摄取**（R3-02/03/04/09）：`POST /api/body/knowledge` 单篇 / `/knowledge/batch` 批量，
   经分块 → 嵌入 → 向量写入 → 元数据落库，状态机 `PENDING → INDEXED / FAILED`。
2. **语义检索**（R3-05/06/08）：`POST /api/body/retrieve` 缓存优先 → Qdrant 召回 TOP-50 → 重排 TOP-K。
3. **RAG 回答**（R3-07）：`POST /api/body/rag/answer` 检索 + 引用组装（生成段仍为模板，DEBT-002）。

同时通过 `GET /api/body/knowledge/stats` 向工作平台躯体视图（R-C03）提供知识量、检索 P99、命中率与三层存储状态。

## 2. 与 Phase 1/2 的关系（替换点已生效）

Phase 1 的 `BodyStore`（进程内 `ConcurrentHashMap` + 字符命中率打分）**已随 Phase 3 删除**，
DEBT-001 由此闭合。关键点是**对外契约保持兼容**：

| 维度 | Phase 1（已删除） | Phase 3（当前） |
|---|---|---|
| 存储 | 进程内 `ConcurrentHashMap`（重启即丢） | PG 真实元数据 + Qdrant 向量 + Redis 缓存 |
| 检索 | 字符串包含 / 字符命中率 | 语义向量相似度 + 重排 |
| 分块 | 空行 + 300 字硬切 | Markdown 标题切小节 + 段落累加 800 字 + 50 字重叠 |
| 租户隔离 | Map 的 key 是 `tenantId` | Qdrant payload filter + PG 租户列 + 缓存键前缀（三重） |
| 降级 | 无 | 逐层显式降级并**如实上报**（见 §5） |

`/api/body/retrieve` 的请求体（`query` / `top_k`）与响应字段（`content` / `title` / `source`）保持兼容，
并新增 `chunk_id` / `doc_id` / `heading` / `score` / `rerank_score` / `ingest_time_iso`，
因此 `session-manager` 无需改动调用即可切到语义检索（仅把 `top_k` 从 3 提到 5、显式 `use_cache=true`）。

> 注意：`/api/body/ingest` 已被 `/api/body/knowledge` 取代（旧控制器删除，路由不再冲突）。

## 3. 文件清单

### 3.1 主代码

| 文件 | 类型 | 行数 | 职责 |
|---|---|---:|---|
| `BodyServiceApplication.java` | 启动类 | 7 | Spring Boot 入口 + Nacos 注册 |
| `chunk/ChunkProcessor.java` | 组件 | 200 | R3-02 分块（与 Python `chunking.py` 同算法） |
| `client/OutboundHttp.java` | 工具 | 48 | 出站 HTTP 统一出口，**强制 HTTP/1.1** |
| `client/EmbeddingClient.java` | 客户端 | 92 | R3-03 嵌入（调 nlp-service） |
| `client/RerankClient.java` | 客户端 | 92 | R3-06 重排（调 nlp-service，可降级） |
| `client/QdrantClient.java` | 客户端 | 229 | R3-04 向量库 REST 客户端 + point id 派生 |
| `common/ErrorCode.java` | 枚举 | 30 | 统一错误码（R1-08 对齐副本） |
| `common/BizException.java` | 异常 | 30 | 携带错误码与明细 |
| `common/GlobalExceptionHandler.java` | 切面 | 48 | 错误码 → HTTP 响应体 |
| `config/BodyStorageConfig.java` | 配置 | 55 | 冷层选型（PG / 内存回落）+ 分层规则注入 |
| `controller/KnowledgeController.java` | 控制器 | 240 | 入库 / 检索 / RAG / 状态四组接口 |
| `event/SenseCollectedConsumer.java` | 消费者 | 179 | R3-09 消费 `lifeform.sense.collected` |
| `grpc/GrpcHealthServer.java` | 组件 | 24 | gRPC Health 探针 |
| `service/IngestService.java` | 服务 | 152 | R3-02/03/04/09 入库管道与状态机 |
| `service/RetrievalService.java` | 服务 | 154 | R3-05/06/08 检索链路 |
| `service/RagPipeline.java` | 服务 | 97 | R3-07 RAG 回答与引用 |
| `service/KnowledgeMetrics.java` | 服务 | 104 | 检索/缓存/重排指标（采样口径） |
| `store/MetadataStore.java` | 接口 | 33 | 冷层抽象（真相源） |
| `store/PgMetadataStore.java` | 实现 | 156 | R3-01 PG 实现（DDL 自举） |
| `store/InMemoryMetadataStore.java` | 实现 | 80 | PG 不可用时的显式降级实现 |
| `store/HotCacheStore.java` | 组件 | 158 | R3-08 Redis 缓存优先 + 查询指纹 |
| `store/StorageFacade.java` | 门面 | 73 | R3-01 统一存储出口与三层状态 |
| `store/TierRouter.java` | 组件 | 78 | R3-01 分层规则（热度 = 频率 × 新鲜度） |
| `store/StoredDocument.java` | record | 26 | 文档元数据 |
| `store/StoredChunk.java` | record | 17 | 切片元数据 |
| `store/DocumentStatus.java` | 枚举 | 9 | `PENDING / INDEXED / FAILED / DELETED` |

### 3.2 测试（39 个用例，全绿）

| 文件 | 行数 | 覆盖验收点 |
|---|---:|---|
| `chunk/ChunkProcessorTest.java` | 98 | R3-02 无遗漏 / 标题前置 / 大小受控 / 边界可配 |
| `client/QdrantClientTest.java` | 38 | R3-04 **point id 必须是 UUID**（E2E 缺陷回归） |
| `service/IngestServiceTest.java` | 128 | R3-09 状态机 / 失败置 FAILED / 重入库清理 |
| `service/RetrievalServiceTest.java` | 147 | R3-05/06/08 缓存优先 / 重排降级不阻断 / 上游不可用不返回假结果 |
| `store/HotCacheStoreTest.java` | 84 | R3-08 指纹归一化 / Redis 不可用时不伪造命中 |
| `store/InMemoryMetadataStoreTest.java` | 78 | R3-01 覆盖式写入 / 租户隔离 / 真相源语义 |
| `store/TierRouterTest.java` | 62 | R3-01 热度模型可解释 / 规则可配置 |

## 4. 逐文件说明（关键实现）

### 4.1 `chunk/ChunkProcessor.java` · 分块器 · 200 行

顺序：**标题切小节 → 空行切段落 → 超长段按句末标点切分（回退硬切）→ 段落累加至 `max-chars`(800)
→ 同小节小块合并（跨小节不合并）→ 块首组装**。

块内容格式固定为 `[标题] → [上块重叠尾巴] → [本块正文]`：

- **标题必须在最前**：这是「块首携带标题」验收口径的前提；初版把重叠尾巴放在标题前，
  导致第 N 块的 `content` 以上一块的正文开头，标题被顶到中间，单测 `eachChunkKeepsItsHeading` 因此失败并已修正。
- **过小块只与同标题相邻块合并**：跨小节合并会破坏 heading 归属与语义边界（回归测试守卫）。
- 参数非法（`maxChars<=0`、`minChars>maxChars`、`overlap>=maxChars`）直接抛 `IllegalArgumentException`，
  避免运行期出现自膨胀的块。

与 Python 侧 `services/python/nlp-service/app/chunking.py` **同算法**，两侧用例一一对应
（`ChunkProcessorTest` ↔ `tests/test_chunking.py`），并由 `POST /api/nlp/chunk` 支持跨语言一致性校验。

### 4.2 `client/QdrantClient.java` · 向量库客户端 · 229 行

走 Qdrant REST（6333），不引入额外 SDK。三条硬约束：

1. **point id 必须是「无符号整数或 UUID」**。业务 `chunk_id` 形如 `phase3-arch#0`，
   直接当 id 会被 Qdrant 以 400 `not a valid point ID` 拒绝（E2E 首次跑出的真实缺陷）。
   现由 `pointId(chunkId)` 用 name-based UUID（v3/MD5）稳定派生：同一 chunk 每次得到相同 id，
   **重入库天然幂等**；人类可读的 `chunk_id` 随 payload 存储并在检索时回填。
2. **tenant 隔离靠 payload filter**（`tenant_id` must-match），不靠调用方自觉过滤结果。
3. **维度不一致必须重建集合**：`ensureCollection(dim)` 检测现有维度，不一致时删旧建新并返回 `true`
   （调用方据此全量重索引）——嵌入模型升级的回归风险点。

其余方法：`upsert`（`wait=true`，返回即可检索）、`search`（TOP-N + payload 还原）、
`deleteByDocument`（重入库清理旧向量）、`count`（躯体视图"知识量"口径）、`available`（真实性探针）。

### 4.3 `service/IngestService.java` · 入库管道 · 152 行

状态机：写 `PENDING` → 分块 → 分批嵌入（`embed-batch-size`=16）→ `ensureCollection` →
按 doc 清旧向量 → upsert → PG 覆盖式写切片 → 置 `INDEXED`；任一异常置 `FAILED` + 截断原因（≤900 字）后**重抛**。

关键取舍：

- **不伪造向量质量**：嵌入失败即失败，绝不写入随机向量制造"看起来入库成功、检索永不命中"。
- **批量嵌入按 `embed-batch-size` 分片**，避免一次请求过大把嵌入服务打挂。
- 每次入库都 `deleteByDocument`，因此重入库不会残留旧切片（E2E 断言"同 doc 切片数受控"）。

### 4.4 `service/RetrievalService.java` · 检索链路 · 154 行

```text
Query → recordAccess（热度输入）
      → 缓存查（Redis，命中即返回，cacheHit=true / tier=HOT）
      → 未命中：embedOne → Qdrant TOP-50（tenant 过滤）
      → 重排 TOP-K（不可用则按召回分返回并标记 degraded）
      → 写缓存 → 返回（附 source / score / rerank_score / ingest_time）
```

失败语义（**既不静默也不伪造**）：

| 环节 | 不可用时行为 |
|---|---|
| 重排（Should） | 按召回分返回 TOP-K，`rerank_degraded` 计数 +1，**不阻断检索** |
| 缓存（可选） | 直连 Qdrant，`cache_backend=unavailable`，命中率**不虚增** |
| 嵌入 / 向量库（Must） | 抛 `AGENT_UPSTREAM_UNAVAILABLE`，**不返回假结果** |

### 4.5 `store/HotCacheStore.java` · 热层 · 158 行

- 缓存键 `knowledge:search:{tenant}:{SHA-256(归一化问题)前 32 位}`，TTL 可配（默认 1h）。
  归一化 = 去空白 + 小写 + 去尾部标点，使「什么是躯体层？」与「  什么是躯体层  」命中同一缓存。
- 键**带租户前缀**，既保证隔离又支持按租户批量失效（`invalidateTenant`）。
- Redis 故障时 `available=false` 并**只降级一次**（`markUnavailable` 幂等），
  后续读写直接跳过——不会因 Redis 宕机把每个请求都拖到超时。

### 4.6 `store/PgMetadataStore.java` · 冷层 · 156 行

两张表 `knowledge_document`（真相源）+ `knowledge_chunk`（切片明细），`CREATE TABLE IF NOT EXISTS` 自举，
不引入 Flyway（后续阶段可换）。写入语义：文档 `ON CONFLICT (doc_id) DO UPDATE`；
切片 `DELETE ... WHERE doc_id` 后重插（**覆盖式，避免重入库残留**）。

真相源语义：Qdrant 只是「向量 + 冗余 payload」，**元数据以本表为准**，
对账任务（设计 §6 风险应对）按此判定向量库与元数据是否一致。

### 4.7 `event/SenseCollectedConsumer.java` · 感官事件消费 · 179 行

- 主题 `lifeform.sense.collected`（Phase 2 侧 `SenseEventPublisher` 发布），
  原生 `KafkaConsumer` 跑在守护线程（未引入 spring-kafka，避免为一个消费器拉入整套依赖）。
- 只处理 `staging_status=ACCEPTED` 且**带正文**的事件；不带正文的（只有 `staging_ref`）记 `failed` 并跳过，
  日志显式说明原因——**不猜测、不回读暂存区**（MinIO 大文档回读登记为后续债）。
- **入库失败不阻塞消费**：异常按文档粒度吞掉并计数，位点照常提交，
  避免一条坏数据卡死整条采集链路（与 Phase 2 死信思路一致）。

### 4.8 `controller/KnowledgeController.java` · 控制器 · 240 行

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/body/knowledge` | 单篇入库，返回 `chunk_count / status / vector_backend / degraded` |
| `POST` | `/api/body/knowledge/batch` | 批量入库，**单篇失败不影响其余**，返回逐篇结果 |
| `GET` | `/api/body/knowledge` | 文档列表 + `metadata_backend` |
| `DELETE` | `/api/body/knowledge/{docId}` | 删除（PG + Qdrant + 缓存三处一起清） |
| `POST` | `/api/body/retrieve` | 语义检索（兼容 Phase 1 字段） |
| `POST` | `/api/body/rag/answer` | RAG 回答（含引用） |
| `GET` | `/api/body/knowledge/stats` | 躯体视图指标 |
| `GET` | `/api/body/health` | 存活检查 |

**租户来源**：网关注入的 `X-Tenant-Id`，**不信任请求体里的租户字段**。
`defaultValue="default"` 的便利性取舍仍在（缺头时落 default 而非报错），生产前需评估（见 §7）。

### 4.9 `service/KnowledgeMetrics.java` · 指标 · 104 行

口径必须读准，避免误读：

| 指标 | 口径 |
|---|---|
| `search_hit_rate` | **有结果**的检索次数 / 检索总次数 |
| `cache_hit_rate` | 缓存命中次数 / 检索总次数（R3-08 验收 ≥ 30%） |
| `latency_p99_ms` | 最近 500 次检索延迟采样的分位，字段同时给出 `sample_size` 与 `p99_basis` |

## 5. 降级清单（诚实上报，不虚构）

| 组件 | 不可用表现 | 上报方式 |
|---|---|---|
| PostgreSQL | 回落 `InMemoryMetadataStore` | 启动 WARN + `metadata_backend=memory` |
| Redis | 跳过缓存，直连向量库 | `storage.hot.available=false`，命中率不虚增 |
| Qdrant | 入库置 FAILED；检索抛上游不可用 | 入库响应 `status=FAILED` + `error` |
| 嵌入（BGE-M3） | 降级 `hash-ngram-768`（768 维） | `embedding.degraded=true` + `backend` 字段 |
| 重排 | 按召回分返回 | `retrieval.rerank_degraded` 计数 |
| Kafka | 消费器启动失败不影响 HTTP 链路 | 启动 WARN + 消费统计 `running=false` |

## 6. 配置项（`application.yml`）

| 配置 | 默认 | 说明 |
|---|---|---|
| `server.port` / `grpc.port` | 8083 / 9094 | HTTP / gRPC |
| `app.body.nlp.base-url` | `http://127.0.0.1:8000` | 嵌入 / 重排 |
| `app.body.qdrant.base-url` / `collection` | `:6333` / `lifeform_knowledge` | 向量库 |
| `app.body.chunk.max-chars` / `min-chars` / `overlap` | 800 / 200 / 50 | 分块 |
| `app.body.tier.half-life-seconds` / `hot-threshold` / `warm-threshold` | 3600 / 2.0 / 1.0 | **分层规则可配置** |
| `app.body.cache.ttl-seconds` | 3600 | 缓存 TTL |
| `app.body.retrieval.candidate-top-k` | 50 | 重排候选数 |
| `app.body.ingest.embed-batch-size` | 16 | 嵌入批大小 |
| `app.body.kafka.bootstrap` / `topic` / `consume-enabled` | `:9092` / `lifeform.sense.collected` / true | 感官事件 |
| `spring.datasource.url/username/password` | `jdbc:postgresql://127.0.0.1:5432/lifeform` / `agent` / `agent123` | 对齐 `docker-compose.yml` |
| `spring.data.redis.host/port` | `127.0.0.1` / 6379 | 热层 |
| `management.otlp.tracing.endpoint` | `http://localhost:4318/v1/traces` | **已补齐**（Phase 1/2 缺口） |

> 运行注意：本机（WorkBuddy 环境）注入的代理环境变量会被 Spring 的宽松绑定误解析为端口，
> 导致 Tomcat 落到随机端口。**启动时显式传 `--server.port=8083`** 可绕开。

## 7. 修改指引

| 需求 | 改动位置 |
|---|---|
| 调整分块策略 | `ChunkProcessor`（**同时改 Python `chunking.py`**，否则跨语言一致性用例失败） |
| 换嵌入模型（维度变化） | 只需改 nlp-service；`ensureCollection` 会自动重建集合并需**全量重索引** |
| 接入 LLM 生成 | `RagPipeline.compose()`（检索/引用/降级逻辑不变，闭合 DEBT-002） |
| 调整分层阈值 | `application.yml` 的 `app.body.tier.*`（已配置化，无需改码） |
| 支持大文档暂存回读 | `SenseCollectedConsumer`（当前跳过无正文事件，需接 MinIO 读 `staging_ref`） |
| 让租户头成为强制项 | 去掉 `@RequestHeader` 的 `defaultValue`（**需评估对现有调用影响**） |

## 8. 接口调用示例

```bash
# 入库
curl -X POST http://127.0.0.1:8083/api/body/knowledge \
  -H "Content-Type: application/json" -H "X-Tenant-Id: demo" \
  -d '{"doc_id":"doc-1","title":"架构说明","content":"# 躯体层\n热层用 Redis，温层用 Qdrant，冷层用 PostgreSQL。"}'
# → {"doc_id":"doc-1","chunk_count":1,"status":"INDEXED","vector_backend":"hash-ngram-768","degraded":true,"success":true}

# 语义检索
curl -X POST http://127.0.0.1:8083/api/body/retrieve \
  -H "Content-Type: application/json" -H "X-Tenant-Id: demo" \
  -d '{"query":"三级存储分别用什么？","top_k":5,"use_cache":true}'

# 躯体视图指标
curl http://127.0.0.1:8083/api/body/knowledge/stats -H "X-Tenant-Id: demo"
```

经网关访问时把主机换成 `http://127.0.0.1:8080`（路由 `/api/body/**`），
并且**必须带 `Authorization: Bearer <token>`** —— 网关会校验令牌并覆写 `X-Tenant-Id`。
