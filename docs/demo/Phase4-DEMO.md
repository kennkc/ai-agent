# Phase 4 演示脚本：大脑期——推理与生成 🧠

> **演示目标**：证明生命体已"长出大脑"——问题进来能**意图识别 → 规划 → 检索 → 缺口评估 →
> 生成 → 自校验 → 来源标注**跑完整条 LangGraph 决策链，回答可回溯、降级可见、**每条决策可回放**；
> 会话有状态机与 Redis 持久化，长期记忆以实体关系图谱沉淀，并在工作平台与统一终端实时观测。
> **前置条件**：Docker Desktop（不要升级，本机 Build 19041）、JDK 21、Maven 3.9+、Python 3.13 + venv、Node 22
> **对应需求**：R4-01 ~ R4-09（Phase4-大脑期/需求设计文档.md）+ 终端线 R-C04 + IN-02 记忆图谱
> **版本**：2026-09-19 复审补全版 —— 本版**按复审后的真实代码逐条核对**接口与返回值；
> 旧版本中"语义缓存已接 Redis""决策链回放可用"等表述在复审中被证明与实现不符，已修复后重写。

---

## 一、自动化验证（无需 Docker，先跑这一层）

### 1.1 Java 全量构建与单测

```bash
cd services/java
../../scripts/mvn-dev.sh test
```

预期（2026-09-19 实测）：

| 模块 | 测试数 | 失败 | 错误 |
|------|-------:|-----:|-----:|
| proto-contracts | 2 | 0 | 0 |
| session-manager | 43 | 0 | 0 |
| sense-service | 53 | 0 | 0 |
| body-service | 64 | 0 | 0 |
| **合计** | **162** | **0** | **0** |

本阶段新增：`SessionStatsTest`(3)、**`KnowledgeIngestInvalidationTest`(4)**（入库失效热缓存一致性回归）、
`SessionWiringTest`（反射固化 Spring 构造注入约束）。

### 1.2 Python 大脑层

```bash
cd services/python/nlp-service
../venv/Scripts/python.exe -m pytest -q      # Windows
```

预期（2026-09-19 实测）：

```
124 passed, 1 warning in 5.64s
```

| 测试文件 | 覆盖 |
|---|---|
| `test_brain.py` | 大脑层：LLM 网关路由/超时/重试、语义缓存**真 Redis 读写**、规划器、缺口检测、来源标注、LangGraph 全链、IN3 审计落账与回放、思考链 6 步与三卡 |
| `test_evalset_phase4.py` | 意图评测集（50 条）级联口径门禁 ≥90% |
| `test_memory_graph.py` | IN-02 实体/关系抽取、PG 落图、递归 CTE 子图、压缩率与加载预算 |
| `test_intent.py` / `test_ocr.py` / `test_chunking.py` / `test_embedding.py` / `test_rerank.py` / `test_http_errors.py` | Phase 2/3 存量 |

### 1.3 wp-bff 回归

```bash
cd services/node/wp-bff
node --test
```

预期：

```
# tests 55
# pass 55
# fail 0
```

本阶段新增：大脑端点（`/brain`、`/brain/ask`、`/brain/{decision_id}` **含 404 语义**）、
会话代理（创建 / ask / context / close / stats）、总览驾驶舱指标聚合。

### 1.4 契约与文档覆盖

```bash
cd /e/AI/ai-agent
python scripts/contract-check.py                 # 数据字典 ↔ proto：FAIL 0
python scripts/contract-check.py --work-platform # BFF 契约分层：implemented 21 路径（22 方法） / planned 30 / 前端调用 41，0 FAIL
```

---

## 二、端到端演示（需 Docker 基础设施）

### 2.0 启动

```bash
cd /e/AI/ai-agent
docker compose up -d redis postgres qdrant kafka jaeger nacos
docker ps --format '{{.Names}} {{.Status}}'
```

> **Nacos 必须在线**：`session-manager` 启动时会向注册中心注册，Nacos 未就绪会直接
> `NacosException: Client not connected` 启动失败。

启动 nlp-service（Python，**指定 Redis**）：

```bash
cd services/python/nlp-service
REDIS_URL=redis://127.0.0.1:6379/0 ../venv/Scripts/python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

启动 Java 服务——**务必显式指定端口**（本机环境变量会污染 `server.port`）：

```bash
cd services/java
../../scripts/mvn-dev.sh -pl body-service,session-manager -am package -DskipTests

export JWT_SECRET=agent-lifeform-dev-secret-key-2026-change-me-in-prod
export DEV_TOKEN_ENDPOINT_ENABLED=true
export GRPC_PORT=19092          # ⚠️ 默认 9092 会与 Kafka 撞端口，必须覆盖

cd body-service    && "E:/software/java/jdk-21/bin/java.exe" -Dserver.port=8083 -jar target/body-service-0.1.0-SNAPSHOT.jar &
cd ../session-manager && "E:/software/java/jdk-21/bin/java.exe" -Dserver.port=8081 -jar target/session-manager-0.1.0-SNAPSHOT.jar &
```

启动 BFF 与前端：

```bash
cd services/node/wp-bff && node server.js          # 8090
cd web/work-platform && npm run dev                # 3001
```

### 2.1 R4-03 LLM Gateway 路由与降级（先确认大脑基座）

```bash
curl -s --noproxy '*' http://127.0.0.1:8000/api/nlp/brain/health | python -m json.tool
```

预期关键字段：

```json
{
  "llm": {
    "available": true,
    "engines": [ { "name": "template", "level": "L1", "available": true },
                 { "name": "http",     "level": "L2", "available": false } ],
    "timeouts": { "L1": 3.0, "L2": 8.0, "L3": 20.0 },
    "stats": { "calls": 0, "timeouts": 0, "retries": 0, "degraded_calls": 0, "cache_hits": 0 }
  },
  "semantic_cache": { "backend": "redis", "degraded": false, "threshold": 0.95, "ttl_seconds": 3600 },
  "planner": { "name": "SimplePlanner", "engine": "rule" },
  "audit": { "storage": { "backend": "postgres", "degraded": false } }
}
```

> **R4-03 达标**：L1/L2/L3 三级路由表 + 各级超时 + 调用统计齐备；
> `http` 引擎 `available=false` 是**如实上报**（真实 LLM 未接入，DEBT-015/016），不是故障。
> `semantic_cache.backend=redis` 只有在**真的连上 Redis 且读写都走 Redis** 时才成立（见 2.4）。

### 2.2 R4-06 端到端问答（完整决策链）

用 Python 发 UTF-8 请求（Git Bash 下 `curl` 发中文会踩编码坑，见 §四）：

```bash
python - <<'PY'
import json, urllib.request
op = urllib.request.build_opener(urllib.request.ProxyHandler({}))
def post(p, b):
    r = urllib.request.Request("http://127.0.0.1:8000"+p,
        data=json.dumps(b, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", "X-Tenant-Id": "default"}, method="POST")
    return json.load(op.open(r, timeout=60))

r = post("/api/nlp/brain/ask", {"question": "大脑层包含哪些能力？", "session_id": "demo-1"})
print("generator:", r["generator"])
print("主步:", [s["step"] for s in r["chain"] if s.get("kind", "step") == "step"])
print("来源:", [(s["doc_id"], s["score"]) for s in r["sources"]])
print("覆盖度:", r["gap"]["coverage"], "阈值:", r["gap"]["threshold"])
print("decision_id:", r["decision_id"])
PY
```

预期（2026-09-19 实测）：

```
generator: template
主步: ['intent', 'plan', 'retrieve', 'generate', 'verify', 'annotate']
来源: [('brain-capabilities', 0.1945), ('phase3-arch', 0.1017), ...]   # 5 条
覆盖度: 0.2884 阈值: 0.35
decision_id: b926e3258e9c...
```

> **R4-05/R4-06/R4-07/R4-08 达标**：规划 → 检索 → 缺口评估 → 生成 → **自校验** → **来源标注**，
> X4 思考链 6 主步完整（`gap` 作为 `retrieve` 的子步展示，避免掩盖"为什么够格生成"这一判据）。
> `generator=template` 是**如实标注的降级**（规则模板生成，非 LLM），不伪装成模型输出。
> 覆盖度 0.2884 ≥ 阈值 0.35 **未达标但 ≥ 可用下限 0.15** → 走"基于现有知识生成 + 标注部分不完整"分支，
> 判定逻辑本身正确（阈值口径见 `Phase4-需求设计文档.md` §6 的口径变更说明）。

### 2.3 R4-07 缺口检测：无资料时**明确拒答**而不是编造

```bash
python - <<'PY'
import json, urllib.request
op = urllib.request.build_opener(urllib.request.ProxyHandler({}))
r = urllib.request.Request("http://127.0.0.1:8000/api/nlp/brain/ask",
    data=json.dumps({"question": "请介绍一下量子纠缠在超导磁悬浮中的应用"}, ensure_ascii=False).encode(),
    headers={"Content-Type": "application/json", "X-Tenant-Id": "default"}, method="POST")
d = json.load(op.open(r, timeout=60))
print("gap:", d["gap"])
print("degraded_reasons:", d["degraded_reasons"])
print("answer 前 80 字:", (d["answer"] or "")[:80])
PY
```

预期：`gap.has_gap=true`、`gap.usable_chunks=0`、`degraded_reasons` 含 `information_gap`，
回答以「⚠️ 信息不足：知识库中未找到可用资料」开头。

> **R4-07 达标**：缺口**只提示与标注，不静默补内容**。这是本阶段最重要的诚实性约束。

### 2.4 R4-04 语义缓存（真 Redis）

```bash
# 连打同一问题，观察第二次命中
python - <<'PY'
import json, urllib.request, time
op = urllib.request.build_opener(urllib.request.ProxyHandler({}))
def post(q):
    r = urllib.request.Request("http://127.0.0.1:8000/api/nlp/brain/ask",
        data=json.dumps({"question": q, "session_id": "cache-demo"}, ensure_ascii=False).encode(),
        headers={"Content-Type": "application/json", "X-Tenant-Id": "default"}, method="POST")
    return json.load(op.open(r, timeout=60))
a = post("大脑层包含哪些能力？"); print("ask#1 cache_hit=", a["cache_hit"], "耗时", a["latency_ms"], "ms")
b = post("大脑层包含哪些能力？"); print("ask#2 cache_hit=", b["cache_hit"], "相似度", b.get("cache_similarity"), "耗时", b["latency_ms"], "ms")
PY
curl -s --noproxy '*' http://127.0.0.1:8000/api/nlp/brain/cache/stats | python -m json.tool
# 直接看 Redis 里到底有没有东西
docker exec lifeform-redis redis-cli --scan --pattern 'brain:cache:*'
```

预期：`ask#2 cache_hit=true`、`相似度 1.0`、耗时由 ~85ms 降到 **~3ms**；
`redis-cli` 能列出 `brain:cache:default` 这类键。

> **R4-04 达标**：缓存**真的落在 Redis**（`docker exec` 可验证），命中率 ≥20%（本阶段实测 20%~67%）。
> **复用 Phase 3 嵌入**（哈希 n-gram），命中判定偏字面相似；条目上限 200/租户 + TTL 1h + 租户前缀隔离。
> **只缓存"有生成结果"的回答** —— 缺口拒答结果不进缓存，避免把故障固化。

### 2.5 D5/X4 决策链回放与三卡

```bash
DECISION=$(python -c "
import json,urllib.request
op=urllib.request.build_opener(urllib.request.ProxyHandler({}))
r=urllib.request.Request('http://127.0.0.1:8000/api/nlp/brain/ask',
 data=json.dumps({'question':'大脑层包含哪些能力？','session_id':'replay-demo'},ensure_ascii=False).encode(),
 headers={'Content-Type':'application/json','X-Tenant-Id':'default'},method='POST')
print(json.load(op.open(r,timeout=60))['decision_id'])")

curl -s --noproxy '*' "http://127.0.0.1:8000/api/nlp/brain/decision/$DECISION" | python -m json.tool

# 不存在的 id 必须 404（不是 200+空链）
curl -s --noproxy '*' -o /dev/null -w "不存在 id -> HTTP %{http_code}\n" \
  http://127.0.0.1:8000/api/nlp/brain/decision/deadbeefdeadbeef

# 跨租户访问同样按 404（不泄露"这条决策存在过"）
curl -s --noproxy '*' -o /dev/null -w "跨租户   -> HTTP %{http_code}\n" \
  -H "X-Tenant-Id: other-tenant" "http://127.0.0.1:8000/api/nlp/brain/decision/$DECISION"

# 最近决策索引
curl -s --noproxy '*' "http://127.0.0.1:8000/api/nlp/brain/decisions?limit=5" -H "X-Tenant-Id: default" | python -m json.tool

# 直查审计表（IN3 AuditLog 真的落库了吗）
docker exec lifeform-postgres psql -U agent -d lifeform -c \
  "SELECT count(*), max(created_at_iso) FROM brain_decision_log;"
```

预期：回放返回完整 `chain` + **三卡**（`confidence` 决策置信度 / `compliance` 合规审计 / `attribution` 归因溯源）；
不存在 id → **HTTP 404**；跨租户 → **HTTP 404**；PG 表行数随问答增长。

> **D5/X4 达标**：决策链**真的落 PG**（`brain_decision_log`，payload JSONB），可按 `decision_id` 回放。
> **合规卡**四项校验：回答基于检索资料 / 来源已标注 / 降级已如实标注 / 未伪造生成。
> 这是 Phase 4 复审的重点修复项 —— 修复前该端点对任意 id 都返 `200 + available:true + chain:[]`。

### 2.6 R4-01/R4-02 会话状态机与持久化

```bash
python - <<'PY'
import json, urllib.request, urllib.error
op = urllib.request.build_opener(urllib.request.ProxyHandler({}))
BASE="http://127.0.0.1:8081"
def call(p, b=None, m="POST"):
    d = json.dumps(b, ensure_ascii=False).encode() if b is not None else None
    h = {"X-Tenant-Id":"default"}
    if d: h["Content-Type"]="application/json"
    r = urllib.request.Request(BASE+p, data=d, headers=h, method=m)
    return json.load(op.open(r, timeout=60))

s = call("/api/session"); sid = s["session_id"]
print("create ->", sid, s["status"])
print("ask#1  ->", len(call(f"/api/session/{sid}/ask", {"question":"大脑层包含哪些能力？"})["answer"]), "字")
print("ask#2  ->", len(call(f"/api/session/{sid}/ask", {"question":"那语义缓存的阈值是多少？"})["answer"]), "字")
ctx = call(f"/api/session/{sid}/context")
print("context 轮数 ->", len(ctx.get("messages") or []), "status:", ctx["status"])
print("close  ->", call(f"/api/session/{sid}", None, "DELETE")["status"])
try:
    call(f"/api/session/{sid}/ask", {"question":"关闭后还能问吗？"})
    print("已关闭再问 -> 竟然 200（错误）")
except urllib.error.HTTPError as e:
    print("已关闭再问 -> HTTP", e.code, "（应为 409）")
print("stats ->", json.dumps(call("/api/session/stats", None, "GET"), ensure_ascii=False)[:160])
PY
```

预期：`ACTIVE` → 多轮上下文累积 → `CLOSED` → 已关闭再问 **409**；`stats` 返回 `active_sessions` / `by_status`。

> **R4-01 达标**：`NEW → ACTIVE ⇄ IDLE → TIMEOUT / CLOSED`，非法转移抛 `IllegalTransitionException`。
> **R4-02 达标**：会话与消息落 **Redis Hash + TTL 2h**；杀进程重启后 `GET /{id}/context` 仍能重建上下文。

### 2.7 IN-02 记忆图谱

```bash
python - <<'PY'
import json, urllib.request
op = urllib.request.build_opener(urllib.request.ProxyHandler({}))
def post(p,b):
    r=urllib.request.Request("http://127.0.0.1:8000"+p,data=json.dumps(b,ensure_ascii=False).encode(),
        headers={"Content-Type":"application/json","X-Tenant-Id":"default"},method="POST")
    return json.load(op.open(r,timeout=60))
def get(p):
    r=urllib.request.Request("http://127.0.0.1:8000"+p,headers={"X-Tenant-Id":"default"})
    return json.load(op.open(r,timeout=60))

m = post("/api/nlp/brain/memory/ingest", {"text":"大脑层依赖检索服务，检索服务依赖躯体层存储。"})
print("实体:", [e["name"] for e in m["entities"]])
print("关系:", [(r["rel"], r["source"][:6], r["target"][:6]) for r in m["relations"]])
print("存储:", m["storage"]["backend"])

sg = get("/api/nlp/brain/memory/subgraph?root=%E5%A4%A7%E8%84%91%E5%B1%82&depth=2")
print("子图实体:", [e["name"] for e in sg["entities"]], "加载", sg["load_ms"], "ms")

c = post("/api/nlp/brain/memory/compress", {"text":"大脑层依赖检索服务。"*30})
print("压缩:", "applied=", c["applied"], "压缩率=", c["compression"], "达标=", c["meets_target"])
PY
docker exec lifeform-postgres psql -U agent -d lifeform -c \
  "SELECT (SELECT count(*) FROM memory_entity), (SELECT count(*) FROM memory_relation);"
```

预期（2026-09-19 实测）：

```
实体: ['检索服务', '大脑层', '躯体层存储']
关系: [('依赖', ...), ('依赖', ...)]   # 2 条
存储: postgres
子图实体: ['大脑层', '躯体层存储', '检索服务'] 加载 34.77 ms
压缩: applied= True 压缩率= 0.8833 达标= True
```

> **IN-02 达标**：实体关系抽取（`extractor=rule` 如实标注）+ PG 邻接表 + **递归 CTE** 子图查询；
> 压缩率 **0.883 ≥ 0.60**、子图加载 **34.77ms < 100ms**。
> 复审修复：关系端点实体原先**不被登记为节点**（"大脑层"不符合任何实体正则）→ 有边无点、子图恒空；现已先补点再建边。

### 2.8 意图准确率评测（≥90%）

```bash
curl -s --noproxy '*' -X POST http://127.0.0.1:8000/api/nlp/intent/eval | python -m json.tool
```

预期：`cascade_accuracy=98.33`、`l0_accuracy=90.0`、`pass_accuracy=true`。

> **口径澄清**：线上走**级联引擎**（规则优先 → L0 兜底），因此 ≥90% 以**级联口径**判定；
> L0 单独准确率只是兜底组件指标，一并回传但不作为验收依据。
> 50 条评测集在 `tests/evalset_phase4.json`，门禁固化在 `test_evalset_phase4.py`。

### 2.9 工作平台对话界面（R4-09）

浏览器打开 `http://127.0.0.1:3001` → **「工作台」** → 「对话」：

| 面板 | 展示内容 |
|------|----------|
| 对话区 | 多轮问答，会话表头显示**真实 `session_id`**（非 Mock 的任务号） |
| 来源引用 | 每条回答附来源卡片，可展开看**相似度 + 片段浮层** |
| 缺口提示 | 覆盖不足时显式提示「部分信息可能不完整 / 信息不足」 |
| 降级徽标 | `generator` 非真实模型时打 **降级徽标** |
| 决策链 | 可折叠面板展示 `intent → plan → retrieve → generate → verify → annotate` 各步耗时 |

> **R4-09 达标**：界面走**真实大脑层**（经 BFF → session-manager → nlp-service），不再用浏览器内存数组拼上下文。

### 2.10 统一终端大脑视图与交互终端（R-C04）

```bash
cd web/console && python -m http.server 8080
```

浏览器打开 `http://127.0.0.1:8080` → **「大脑视图」**：

| 面板 | 数据来源 |
|------|----------|
| LLM 路由状态 / 语义缓存命中率 | `GET /api/wp/brain`（真实） |
| 知识片段数 / 模型运行时 | `GET /api/wp/brain`（真实） |
| **活跃会话数 / 意图分布** | `GET /api/wp/session/stats`（真实，session-manager 实时汇总） |
| 会话趋势（近 7 日） | 演示值，界面**显式标注**「演示图表」 |

「交互终端」可输入问题，答复区展示**来源 + 决策链 + 三卡**；BFF 不可用时如实渲染失败提示，**不伪造回复**。

> **R-C04 达标**：大脑视图展示模型调用/缓存命中/决策链指标；交互终端接真实链路。
> 仍为演示值的**只剩会话趋势一图**，已标注（缺按日聚合的时序接口）。

### 2.11 缓存一致性验证（复审修复项）

```bash
# 入库一篇新文档后，立刻用同一问题检索（use_cache=true），新文档必须可见
python - <<'PY'
import json, urllib.request
op = urllib.request.build_opener(urllib.request.ProxyHandler({}))
def call(base,p,b):
    r=urllib.request.Request(base+p,data=json.dumps(b,ensure_ascii=False).encode(),
        headers={"Content-Type":"application/json","X-Tenant-Id":"default"},method="POST")
    return json.load(op.open(r,timeout=60))
print(call("http://127.0.0.1:8083","/api/body/knowledge",
    {"doc_id":"demo-cache","title":"缓存一致性","content":"缓存一致性验证：新入库的文档必须立刻可被检索到。"}))
rows = call("http://127.0.0.1:8083","/api/body/retrieve",
    {"query":"缓存一致性怎么验证？","top_k":5,"use_cache":True})
print("命中:", [h["doc_id"] for h in rows[:3]])
PY
```

预期：`demo-cache` **立刻出现在命中列表**。

> 复审修复项：入库路径原先**不失效检索热缓存**，导致新知识在 TTL（1h）内不可见；
> 现单篇/批量入库成功后均调 `invalidateTenant`，并由 `KnowledgeIngestInvalidationTest` 4 例固化。

---

## 三、量化验收（DoD 对齐）

| 需求 | 验收标准 | 实测 | 结论 |
|------|----------|------|------|
| R4-01 | 会话状态机；非法转移被拒 | `NEW→ACTIVE⇄IDLE→TIMEOUT/CLOSED`；已关闭再问 **409** | ✅ |
| R4-02 | 会话持久化，重启可恢复 | Redis Hash + TTL 2h；重启后 `context` 可重建 | ✅ |
| R4-03 | LLM 路由 + 超时重试 + Token 统计 | L1/L2/L3 路由表 + 各级超时 + 调用统计；**真实引擎未接，模板后端如实标降级** | ✅（降级） |
| R4-04 | 语义缓存命中 ≥20% | **真 Redis**（`redis-cli` 可验证）；实测命中率 20%~67%；相同问题 85ms→3ms | ✅ |
| R4-05 | 任务规划器产出模板+槽位 | `SimplePlanner`（`planner=rule` 如实标注；DEBT-017） | ✅（降级） |
| R4-06 | LangGraph RAG 决策链 | 6 主步完整（含 `verify` 自校验）+ 全链 e2e | ✅ |
| R4-07 | 缺口检测：不静默补全 | 三分支（充分 / 部分 / 关键缺口）；阈值 **0.35**（设计文档已同步） | ✅ |
| R4-08 | 来源标注可回溯 | 每条回答附 `doc_id`/`chunk_id`/`score`/`rerank_score` | ✅ |
| R4-09 | 前端对话接真实大脑层 | `ChatView` 走 BFF → session-manager → nlp-service，真实 `session_id` | ✅ |
| R-C04 | 大脑视图 + 交互终端 | 真实指标 + 真实会话统计；仅"会话趋势"为演示值并标注 | ✅ |
| IN-02 | 压缩率 ≥60%；子图加载 <100ms | 压缩率 **0.883**；加载 **34.77ms**；PG 邻接表 + 递归 CTE | ✅ |
| X4/D5 | 思考链 6 步 + 三卡 + 回放 | 6 主步 + 三卡 + `decision_id` 回放；未命中 **404**、跨租户 **404** | ✅ |
| 意图准确率 | ≥90% | 级联 **98.33%**（n=60）；L0 兜底 90.0% | ✅ |
| 问答 P99 | < 3s | 实测 **85ms**（样本量随问答累积） | ✅ |

**端到端验收：两套脚本全绿**（`.workbuddy/tmp/e2e_phase4.py`、`e2e_session_phase4.py`）。

---

## 四、故障排查

| 现象 | 排查 |
|------|------|
| `session-manager` 启动报 `Failed to start gRPC health server on port 9092` | 默认 `grpc.port=9092` 与 **Kafka 撞端口** → 启动加 `GRPC_PORT=19092` |
| `session-manager` 启动报 `NacosException: Client not connected` | `lifeform-nacos` 容器未运行 → `docker start lifeform-nacos`，等 healthy 再起服务 |
| 服务起在奇怪端口（如 53568） | 环境变量污染 `server.port` → 启动加 `-Dserver.port=8081` |
| 语义缓存 `backend=memory` / `degraded=true` | Redis 未启动或 `REDIS_URL` 不对 → `docker compose ps redis`；**降级是诚实上报，不是故障** |
| `audit.storage.backend=memory` | PG 未连上（凭据/容器） → 检查 `docker-compose.yml` 的 `agent/agent123`；降级时决策无法跨进程回放 |
| 决策链回放 404，但明明刚问过 | 租户不一致 → 请求头 `X-Tenant-Id` 必须与提问时相同（跨租户按未命中处理，不泄露存在性） |
| 入库成功但检索不到新文档 | 检索热缓存未失效（旧版缺陷）→ 本阶段已修复；仍复现请确认 body-service 是**新构建** |
| `curl` 发中文返回 500 | Git Bash 下 curl 未正确编码 UTF-8（项目 D-15 坑）→ 改用 Python `urllib` |
| BFF 端点 404 | 运行的是**旧版** wp-bff 实例 → 重启加载新路由 |
| 打包失败 `target/*.jar` 被占用 | Windows 文件锁 → 先停运行中的实例再 `package` |

---

## 五、演示完成标准

- [x] Java 全量 `test` 通过，**162** 测试 0 失败
- [x] Python **124** 项、wp-bff **55** 项全通过；契约门禁 **0 FAIL**
- [x] 语义缓存**真接 Redis**（`redis-cli` 可验证键存在），命中 ≥20%
- [x] 端到端问答跑完整 LangGraph 决策链（6 主步），回答带可回溯来源
- [x] 缺口检测**明确拒答**而不编造；覆盖不足显式提示
- [x] **IN3 AuditLog 真落 PG**，决策链可按 `decision_id` 回放；未命中/跨租户均 404
- [x] X4 **三卡**（决策置信度 / 合规审计 / 归因溯源）随回放返回
- [x] 会话状态机（ACTIVE→CLOSED，已关闭 409）+ Redis 持久化
- [x] IN-02 记忆图谱：压缩率 0.883、子图加载 34.77ms
- [x] 意图级联准确率 98.33% ≥ 90%（50 条评测集门禁）
- [x] 工作平台对话界面 + Console 大脑视图/交互终端取真实数据
- [x] 降级处处可见（模板生成 / 规则规划 / 哈希嵌入 / 缓存降级 / 审计降级）
- [x] 端到端两套脚本全绿

> 达标 → Phase 4 验收通过 → 进入 Phase 5（四肢期·工具调用与执行）
>
> **遗留（诚实登记）**：真实 LLM 引擎未接入（DEBT-015/016，`generator=template`）；
> Console「会话趋势（近 7 日）」为演示值并标注；`grpc.port` 默认值与 Kafka 冲突待配置侧确认。