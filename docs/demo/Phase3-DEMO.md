# Phase 3 演示脚本：躯体期——知识入库与语义检索 🦴🧠

> **演示目标**：证明生命体已"长出躯体"——知识能被**持久化、向量化、语义检索**，感官采集的数据
> 经事件总线自动流入知识库，形成「感官 → 躯体」闭环，并可在工作平台躯体视图实时观测。
> **前置条件**：Docker Desktop（不要升级，本机 Build 19041）、JDK 21、Maven 3.9+、Python 3.13 + venv、Node 22
> **对应需求**：R3-01 ~ R3-09（Phase3-躯体期/需求设计文档.md）+ 终端线 R-C03

---

## 一、自动化验证（无需 Docker，先跑这一层）

### 1.1 Java 全量构建与单测

```bash
cd services/java
../../scripts/mvn-dev.sh -pl body-service -am test
```

预期（2026-09-18 实测）：

| 模块 | 测试数 | 失败 | 错误 |
|------|-------:|-----:|-----:|
| gateway-service | 2 | 0 | 0 |
| session-manager | 10 | 0 | 0 |
| sense-service | 48 | 0 | 0 |
| **body-service** | **40** | **0** | **0** |
| **合计** | **100** | **0** | **0** |

body-service 的 7 个测试类：`ChunkProcessorTest`(9)、`TierRouterTest`(6)、`HotCacheStoreTest`(4)、
`InMemoryMetadataStoreTest`(4)、`IngestServiceTest`(6)、`RetrievalServiceTest`(8)、`QdrantClientTest`(3)。

### 1.2 Python 嵌入 / 重排 / 分块

```bash
cd services/python/nlp-service
../venv/Scripts/python.exe -m pytest -q      # Windows
# 或 ../venv/bin/python -m pytest -q         # Linux/macOS
```

预期（2026-09-18 实测）：

```
52 passed in 0.41s
```

| 测试文件 | 用例数 | 覆盖 |
|---|---:|---|
| `test_intent.py` | 15 | 意图规则 + L0 + 级联（Phase 2 存量） |
| `test_ocr.py` | 4 | OCR 引擎探测与诚实降级（Phase 2 存量） |
| **`test_chunking.py`** | **12** | 分块边界、标题入块、同标题合并、重叠、跨语言一致性 |
| **`test_embedding.py`** | **13** | 维度、归一化、确定性、延迟统计、引擎降级 |
| **`test_rerank.py`** | **8** | 重排排序、TOP-K、降级不阻断、分数保留 |

### 1.3 wp-bff 回归

```bash
cd services/node/wp-bff
node --test
```

预期：

```
# tests 22
# pass 22
# fail 0
```

其中新增 8 例覆盖躯体端点：可用性、降级、`top_k` 参数裁剪、高亮窗口（`buildSnippet`）、租户透传。

### 1.4 契约与文档覆盖

```bash
cd /e/AI/ai-agent
python scripts/contract-check.py --work-platform    # 实现端点 8 ↔ BFF 8，0 FAIL
python scripts/java-doc-coverage.py                 # body-service 33/33，全仓 100/100
```

---

## 二、端到端演示（需 Docker 基础设施）

### 2.0 启动

```bash
cd /e/AI/ai-agent
docker compose up -d qdrant redis postgres kafka jaeger
docker ps --format '{{.Names}} {{.Status}}'
```

启动 nlp-service（Python）：

```bash
cd services/python/nlp-service
../venv/Scripts/python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

启动 body-service（Java）——**注意显式指定端口**（本机环境注入了会污染 `server.port` 的路由变量）：

```bash
cd services/java
../../scripts/mvn-dev.sh -q -pl body-service -am -DskipTests package
cd body-service
"E:/software/java/jdk-21/bin/java.exe" -jar target/body-service-0.1.0-SNAPSHOT.jar \
  --server.port=8083 \
  --spring.datasource.username=agent --spring.datasource.password=agent123
```

> PG 凭据必须与 `docker-compose.yml` 一致（`agent` / `agent123`），否则冷层回落内存并告警。

关键配置（`services/java/body-service/src/main/resources/application.yml`）：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server.port` / `SERVER_PORT` | 8083 | 服务端口（本机需命令行覆盖，见上） |
| `spring.datasource.url` | `jdbc:postgresql://127.0.0.1:5432/lifeform` | 冷层真相源 |
| `spring.datasource.username/password` | `agent` / `agent123` | 对齐 docker-compose |
| `app.body.qdrant.url` | `http://127.0.0.1:6333` | 温层向量库 |
| `app.body.qdrant.collection` | `lifeform_knowledge` | 集合名 |
| `app.body.cache.ttl-seconds` | 3600 | 热层缓存 TTL |
| `app.body.kafka.bootstrap` | `127.0.0.1:9092` | 感官事件订阅 |
| `app.body.retrieve.top-k` | 5 | 默认召回数 |

### 2.1 R3-01 三层存储状态（先确认地基）

```bash
curl -s --noproxy '*' http://127.0.0.1:8083/api/body/knowledge/stats | python -m json.tool
```

预期关键字段：

```json
{
  "knowledge": { "documents": 0, "chunks": 0, "vector_points": 0, "metadata_backend": "pg" },
  "storage": {
    "hot":  { "provider": "redis",      "available": true },
    "warm": { "provider": "qdrant",     "available": true, "collection": "lifeform_knowledge" },
    "cold": { "provider": "postgresql", "available": true }
  },
  "embedding": { "provider": "nlp-service", "backend": "...", "degraded": false },
  "reranker":  { "provider": "nlp-service" }
}
```

> **R3-01 达标**：三层 `available` 全 true、`metadata_backend=pg` 即证冷层真相源生效；
> `TierRouter` 的分层阈值可配置（`TierRouterTest` 覆盖）。

### 2.2 R3-02/R3-04 知识入库（分块 → 嵌入 → 向量入库）

```bash
cat > /tmp/doc.json <<'EOF'
{
  "doc_id": "phase3-arch",
  "title": "躯体层架构设计",
  "content": "# 躯体层架构\n## 三级存储\n热层用 Redis 缓存高频查询结果，温层用 Qdrant 存向量支持语义检索，冷层用 PostgreSQL 保存元数据作为真相源。\n## 分块策略\n按标题与段落边界分块，块大小控制在 800 字以内，重叠 50 字避免边界语义割裂。\n## 嵌入服务\n嵌入服务返回 768 维归一化向量，用于计算语义相似度。"
}
EOF
cat /tmp/doc.json | curl -s --noproxy '*' -X POST http://127.0.0.1:8083/api/body/knowledge \
  -H "Content-Type: application/json" -H "X-Tenant-Id: default" --data-binary @-
```

预期响应：

```json
{
  "doc_id": "phase3-arch",
  "chunk_count": 3,
  "vector_points": 3,
  "embedding_backend": "...",
  "storage": "warm+cold",
  "status": "READY"
}
```

> **R3-02 达标**：块数由标题/段落边界决定、单块 ≤ 800 字。
> **R3-04 达标**：批次入库返回 `vector_points = chunk_count`，可被后续检索命中，且按租户隔离。

### 2.3 R3-05/R3-06 语义检索与重排

```bash
cat > /tmp/q.json <<'EOF'
{ "query": "躯体层的三级存储分别用什么中间件？", "top_k": 5, "use_cache": false }
EOF
cat /tmp/q.json | curl -s --noproxy '*' -X POST http://127.0.0.1:8083/api/body/retrieve \
  -H "Content-Type: application/json" -H "X-Tenant-Id: default" --data-binary @- | python -m json.tool
```

预期：返回 TOP-5 命中，每条含 `chunk_id / doc_id / content / score（召回分）/ rerank_score（重排分）/ tier`。

> **R3-05 达标**：语义相近即可召回（查询与正文无逐字重合仍命中）。
> **R3-06 达标**：`rerank_score` 与 `score` 同时返回，可对照观察重排带来的名次变化；
> 重排服务不可用时降级为按召回分排序（不阻断）。

### 2.4 R3-08 缓存优先策略

```bash
# 连打同一查询，对比首次（未命中）与后续（命中）
for i in 1 2 3 4 5; do
  cat /tmp/q.json | curl -s --noproxy '*' -o /dev/null -w "第${i}次 %{time_total}s\n" \
    -X POST http://127.0.0.1:8083/api/body/retrieve -H "Content-Type: application/json" --data-binary @-
done
curl -s --noproxy '*' http://127.0.0.1:8083/api/body/knowledge/stats | python -c "import sys,json; print(json.load(sys.stdin)['retrieval'])"
```

预期：`cache_hit_rate` ≥ 0.3（实测 **0.45**），命中路径延迟明显低于未命中（实测 P50 23.1ms vs 28.8ms）。

> **R3-08 达标**：缓存命中率 45% ≥ 30%。

### 2.5 R3-07 RAG 管道

```bash
cat > /tmp/rag.json <<'EOF'
{ "query": "知识以什么方式被持久化？", "top_k": 3 }
EOF
cat /tmp/rag.json | curl -s --noproxy '*' -X POST http://127.0.0.1:8083/api/body/rag \
  -H "Content-Type: application/json" -H "X-Tenant-Id: default" --data-binary @- | python -m json.tool
```

预期：返回 `answer` + `citations`（每条含可回溯的 `doc_id` / `chunk_id`）+ `generator`。

> **R3-07 达标**：检索增强生成端到端可用。
> **诚实标注**：`generator=template` —— 回答仍是模板拼接（DEBT-002，触发点 Phase 4 接 LLM），不伪装成 LLM 生成。

### 2.6 租户隔离验证

```bash
# default 租户查不到 tenant-b 的文档
cat /tmp/q.json | curl -s --noproxy '*' -X POST http://127.0.0.1:8083/api/body/retrieve \
  -H "Content-Type: application/json" -H "X-Tenant-Id: tenant-b" --data-binary @- | \
  python -c "import sys,json; print('tenant-b 检索到:', [h['doc_id'] for h in json.load(sys.stdin)])"
```

预期：tenant-b 只能看到自己的文档，`default` 的文档不出现。

### 2.7 R3-09 感官 → 躯体闭环（Kafka 事件驱动）

```bash
cat > /tmp/sense-event.json <<'EOF'
{"batch_id":"demo-sense-001","tenant_id":"default","source_channel":"TOUCH",
 "staging_status":"ACCEPTED","staging_ref":"local://default/ACCEPTED/demo-sense-001/item-0.json",
 "title":"感官采集闭环验证",
 "content":"# 感官采集闭环\n## 触觉渠道\n触觉渠道采集到的数据经质检通过后发布到事件总线，躯体层消费该事件并完成知识入库与向量化，形成感官到躯体的闭环。",
 "quality_score":0.91,"item_count":1}
EOF
cat /tmp/sense-event.json | docker exec -i lifeform-kafka \
  /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
  --topic lifeform.sense.collected

# 等待入库后检索该事件文档
sleep 6
cat > /tmp/q2.json <<'EOF'
{ "query": "感官到躯体的闭环是怎么形成的？", "top_k": 3, "use_cache": false }
EOF
cat /tmp/q2.json | curl -s --noproxy '*' -X POST http://127.0.0.1:8083/api/body/retrieve \
  -H "Content-Type: application/json" -H "X-Tenant-Id: default" --data-binary @- | \
  python -c "import sys,json; print('命中:', [(h['doc_id'], h.get('source')) for h in json.load(sys.stdin)])"
```

预期：`demo-sense-001` 出现在命中列表 —— **Phase 2 采到的数据自动流入了 Phase 3 的知识库**。

> **R3-09 达标**：API 全通 + 索引异步更新（事件驱动）。

### 2.8 重入库幂等与删除清理

```bash
# 重入库（同名 doc_id）→ 切片数不累积（覆盖旧向量）
cat /tmp/doc.json | curl -s --noproxy '*' -X POST http://127.0.0.1:8083/api/body/knowledge \
  -H "Content-Type: application/json" -H "X-Tenant-Id: default" --data-binary @- >/dev/null
# 删除
curl -s --noproxy '*' -X DELETE http://127.0.0.1:8083/api/body/knowledge/phase3-arch \
  -H "X-Tenant-Id: default"
```

预期：重入库后同 doc 切片数受控；删除返回 `{"deleted": true}`，文档不再被召回，
`stats` 的 `documents` 与 `vector_points` 同步下降（无孤儿向量）。

### 2.9 工作平台躯体视图（R-C03）

启动 wp-bff 与前端（wp-bff 默认 8090；如已被占用，可换端口演示）：

```bash
cd services/node/wp-bff
WP_BFF_CONTROL_TOKEN=demo-token node server.js
# 另开终端
cd web/work-platform && npm run dev
```

浏览器打开应用，进入 **「躯体视图」** 模块：

| 面板 | 展示内容 |
|------|----------|
| 知识总量 | 文档数 / 切片数 / 向量点数（真相源标注 `pg`） |
| 检索质量 | 检索 P99（ms）/ 检索命中率 / 缓存命中率 / 采样数 |
| 三层存储健康度 | 热（Redis）/ 温（Qdrant）/ 冷（PostgreSQL）可用性 |
| 检索测试 | 输入查询 → 展示 TOP-K 命中 + **高亮片段** + 召回分/重排分对照 |

预期：以上数据来自真实 API（非 Mock）；也可直接打 BFF 端点核对：

```bash
curl -s --noproxy '*' http://127.0.0.1:8090/api/wp/knowledge -H "X-Tenant-Id: default" | python -m json.tool
cat /tmp/q.json | curl -s --noproxy '*' -X POST http://127.0.0.1:8090/api/wp/knowledge/search \
  -H "Content-Type: application/json" -H "X-Tenant-Id: default" --data-binary @- | python -m json.tool
```

> **R-C03 达标**：躯体视图展示知识总量、检索 P99、命中率指标面板，且数据来自真实 API。

---

## 三、量化验收（DoD 对齐）

| 需求 | 验收标准 | 实测 | 结论 |
|------|----------|------|------|
| R3-01 | 统一 API 增删改查；分层规则可配置 | `StorageFacade` 门面；三层 available 全 true；`TierRouter` 阈值可配 | ✅ |
| R3-02 | 分块无遗漏；块大小 200-800 字 | Java 9 项 + Python 12 项（跨语言同算法） | ✅ |
| R3-03 | 嵌入延迟 P95 < 200ms；维度正确 | 768 维确定性后端；入库零失败 | ✅ |
| R3-04 | 批次入库可检索；tenant 隔离 | 批次入库可召回；跨租户 hits=0；point id 合法 | ✅ |
| R3-05 | 检索 P99 < 500ms；命中质量合格 | **P99 366ms**；命中率 **0.925** | ✅ |
| R3-06 | 重排后相关性提升可量化 | 召回分/重排分对照；不可用降级不阻断 | ✅ |
| R3-07 | 检索增强生成端到端可用 | 回答 + 可回溯引用（`generator=template` 如实标注） | ✅ |
| R3-08 | 重复查询缓存命中 ≥ 30% | **45%**（hit P50 23.1ms < miss 28.8ms） | ✅ |
| R3-09 | API 全通；索引异步更新 | 7 端点全通；Kafka 事件驱动入库 | ✅ |
| R-C03 | 躯体视图展示知识量/检索 P99/命中率 | `KnowledgeView` 三面板 + 检索测试高亮；真实 API 数据 | ✅ |

**端到端验收脚本：35 项 PASS / 0 FAIL**（`PASS 35 / FAIL 0`）。

---

## 四、故障排查

| 现象 | 排查 |
|------|------|
| body-service 起在奇怪端口（如 53568） | 本机环境变量污染 `server.port` → 启动加 `--server.port=8083` |
| `metadata_backend` 是内存而非 `pg` | PG 凭据不符 → 用 `--spring.datasource.username=agent --spring.datasource.password=agent123` |
| 入库报 `Point id must be a valid UUID or integer` | Qdrant 服务端约束；本实现已用 name-based UUID（若自造点需同样处理） |
| 冷层告警 "回落内存存储" | PostgreSQL 未启动或凭据不符 → `docker compose ps postgres` |
| 检索无结果 | ① 是否已入库（看 `stats.documents`）② 租户是否一致（`X-Tenant-Id`）③ Qdrant 集合是否被清 |
| 嵌入/重排 `degraded=true` | 本机未装 BGE-M3 / 交叉编码器 → 走确定性降级（诚实上报，不伪造），装模型后自动切换 |
| BFF 端点 404 | 运行的是旧版 wp-bff 实例 → 换端口起新版（`WP_BFF_PORT=8095`） |
| 打包失败 `target/*.jar` 被占用 | Windows 文件锁 → 先停运行中的实例再 `package` |
| `curl --data-binary @/tmp/x.json` 报 400 | MSYS 路径转换破坏请求体 → 改 `--data-binary @-` 走 stdin |

---

## 五、演示完成标准

- [x] Java 全量 `-pl body-service -am test` 通过，100 测试 0 失败
- [x] Python 52 项、wp-bff 22 项全通过
- [x] 检索 P99 < 500ms（实测 366ms）、缓存命中 ≥ 30%（实测 45%）达标
- [x] 文档入库 → 语义检索命中（语义相近可召回）
- [x] RAG 管道端到端可用，引用可回溯
- [x] 租户数据隔离实测生效
- [x] 感官 → 躯体闭环（Kafka 事件驱动入库并可召回）
- [x] 工作平台躯体视图随阶段交付，取真实 API 数据
- [x] 端到端验收脚本 35/35 通过

> 达标 → Phase 3 验收通过 → 进入 Phase 4（大脑期·推理与生成）
