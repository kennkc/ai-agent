# DEBT-003: 意图识别已升级为「规则引擎 + L0 统计模型」双级级联（Phase 2）— 触发点: P4 升级 规则+LLM 混合识别
# DEBT-007: L0 模型采用字符 n-gram TF-IDF + 最近质心（非 BERT）— 触发点: P4 大脑期接入模型推理服务
# DEBT-010: 嵌入默认字符 n-gram 哈希后端（非 BGE-M3）— 触发点: 安装 BGE-M3 权重后切换
# DEBT-011: 重排默认词法打分（非 bge-reranker-v2-m3）— 触发点: 安装 reranker 权重后切换
"""
NLP 服务入口
Phase 0: 最小骨架
Phase 2: 感官期 —— 意图识别规则引擎 + L0 分类模型 + OCR 通道
Phase 3: 躯体期 —— 嵌入（BGE-M3/降级哈希）+ 重排（bge-reranker/降级词法）
"""
import logging
import time
from collections import deque

from app import model_config, token_usage
from app.brain.audit import AUDIT_LOG
from app.brain.llm_gateway import LLM_GATEWAY
from app.brain.memory_graph import MEMORY_GRAPH
from app.brain.pipeline import RagPipeline
from app.brain.planner import PLANNER
from app.brain.retrieval import RETRIEVER
from app.brain.semantic_cache import SEMANTIC_CACHE
from app.budget import (
    BRAIN_TOTAL_BUDGET_MS,
    EMBED_BUDGET_MS,
    OCR_BUDGET_MS,
    BudgetExceeded,
    budget_status,
    run_with_budget,
)
from app.chunking import chunk_text
from app.embedding import EMBEDDING_SERVICE
from app.intent import CASCADE, CORE_SCENARIOS, EVAL_CORPUS
from app.ocr import OCR_SERVICE
from app.reranker import RERANKER_SERVICE, RerankCandidate
from app.tools import FUNCTION_CALLING
from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
from pydantic import BaseModel, Field
from starlette.exceptions import HTTPException as StarletteHTTPException

app = FastAPI(
    title="agent-lifeform nlp-service",
    version="0.4.0",
    description="意图识别（规则+L0 双级级联） / 实体提取 / OCR / 嵌入 / 重排 / Phase4 大脑层（规划·RAG·语义缓存）",
)

# Phase 4 大脑层：LLM 路由 + RAG 管线（默认引擎为模板降级后端，见 llm_gateway）
BRAIN_PIPELINE = RagPipeline(gateway=LLM_GATEWAY, cache=SEMANTIC_CACHE)

# 问答耗时滑动窗口（DoD 要求 **P99 < 3s**，不能用单次耗时代言）
LATENCY_WINDOW: deque = deque(maxlen=500)

# ─────────── WB-10 角色引擎装载 ───────────
# 从 `llm_model_config` 表读出「哪个角色用哪个模型」并装配到网关。
# 设计取舍：
# * **不是"启动时一次性装载"** —— 服务与 PG 的启动顺序不保证，PG 晚于本服务就绪时
#   一次性装载会让整个进程在生命周期内都停在模板降级态。
# * **也不是"每次请求都查库"** —— PG 不可用时每次都要等连接超时，会把问答拖慢。
# * 取中间：懒加载 + 30s 退避重试。写配置后由端点 `force=True` 立即重载。
_role_engines: dict = {"loaded": False, "last_attempt": 0.0, "result": {}}
_ROLE_RETRY_INTERVAL_SECONDS = 30.0


def ensure_role_engines(force: bool = False) -> dict:
    """确保角色引擎已从配置装载；返回装载结果摘要（幂等，可安全重复调用）。"""
    now = time.time()
    if not force:
        if _role_engines["loaded"]:
            return _role_engines["result"]
        if now - float(_role_engines["last_attempt"] or 0) < _ROLE_RETRY_INTERVAL_SECONDS:
            return _role_engines["result"]
    _role_engines["last_attempt"] = now
    result = LLM_GATEWAY.reload_from_model_config()
    _role_engines["loaded"] = bool(result.get("ok"))
    _role_engines["result"] = result
    if result.get("ok"):
        logger.info("llm role engines loaded: %s", sorted(result.get("roles", {}).keys()))
    else:
        logger.warning("llm role engines not loaded: %s", result.get("error", ""))
    return result

logger = logging.getLogger("nlp-service")

# ─────────── 统一错误信封 ───────────
# 与 gateway-service 的 JwtAuthFilter.reject()、Java 三服务的 GlobalExceptionHandler、
# wp-bff 的 fail() **同一格式** `{code, message, details}`，客户端只需一套解析逻辑。
# 2026-09-18 之前本服务用 FastAPI 默认的 `{"detail": ...}`，是全平台最
# 后一处信封不一致点（见 docs/异常流程归纳.md §2.3）。
_HTTP_CODE_TO_AGENT = {
    400: "AGENT_BAD_REQUEST",
    401: "AGENT_UNAUTHORIZED",
    403: "AGENT_FORBIDDEN",
    404: "AGENT_NOT_FOUND",
    405: "AGENT_METHOD_NOT_ALLOWED",
    409: "AGENT_CONFLICT",
    415: "AGENT_BAD_REQUEST",
    422: "AGENT_BAD_REQUEST",
    502: "AGENT_UPSTREAM_UNAVAILABLE",
    503: "AGENT_BUS_UNAVAILABLE",
    504: "AGENT_TIMEOUT",
}


def _agent_code(status_code: int) -> str:
    if status_code in _HTTP_CODE_TO_AGENT:
        return _HTTP_CODE_TO_AGENT[status_code]
    return "AGENT_INTERNAL_ERROR" if status_code >= 500 else "AGENT_BAD_REQUEST"


def _envelope(code: str, message: str, details: dict | None = None) -> dict:
    return {"code": code, "message": message, "details": details or {}}


@app.exception_handler(StarletteHTTPException)
async def unified_http_exception_handler(request: Request, exc: StarletteHTTPException):
    """HTTP 层异常 → 统一信封。

    - 保留框架给出的响应头（405 的 `Allow` 由此透传，与 Java 侧 405 语义对齐）
    - 422 是「请求体不合法」而不是「资源不存在」，统一按 400 返回，
      使状态码语义与 Java/BFF 三处一致（路径不存在才 404、方法不支持才 405）
    """
    status = 400 if exc.status_code == 422 else exc.status_code
    headers = dict(getattr(exc, "headers", None) or {})
    return JSONResponse(status_code=status, headers=headers,
                        content=_envelope(_agent_code(status), str(exc.detail)))


@app.exception_handler(RequestValidationError)
async def unified_validation_exception_handler(request: Request, exc: RequestValidationError):
    """请求体校验失败 → 400 AGENT_BAD_REQUEST，details 给字段级原因。"""
    fields: dict[str, str] = {}
    for err in exc.errors():
        loc = ".".join(str(part) for part in err.get("loc", ())
                       if part not in ("body", "query", "path")) or "body"
        fields[loc] = str(err.get("msg", "invalid"))
    return JSONResponse(status_code=400,
                        content=_envelope("AGENT_BAD_REQUEST", "请求参数不合法", fields))


@app.exception_handler(Exception)
async def unified_unhandled_exception_handler(request: Request, exc: Exception):
    """兜底：只记日志，不把内部异常消息返回给客户端（避免泄露实现细节）。"""
    logger.exception("Unhandled exception on %s %s", request.method, request.url.path)
    return JSONResponse(status_code=500,
                        content=_envelope("AGENT_INTERNAL_ERROR", "服务内部错误"))


@app.get("/metrics", include_in_schema=False)
def metrics():
    """Prometheus scrape endpoint (process/runtime metrics)."""
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)


@app.get("/healthz")
def healthz():
    return {"status": "ok", "service": "nlp-service", "version": "0.4.0",
            "intent_engine": "cascade(rule+l0)", "ocr": OCR_SERVICE.status()["engine"],
            "embedding": EMBEDDING_SERVICE.status()["backend"],
            "reranker": RERANKER_SERVICE.status()["backend"],
            "planner": "rule(SimplePlanner)", "llm": BRAIN_PIPELINE.gateway.health()["engines"],
            # 前台配置的角色引擎：哪些角色配了模型（空 = 全部走层级级联/模板降级）
            "llm_roles": BRAIN_PIPELINE.gateway.health()["role_configured"],
            "model_config": model_config.status(),
            "semantic_cache": SEMANTIC_CACHE.backend,
            # 具名预算口径（REC-01）：运维一眼看到「这次请求最多能跑多久」
            "budget": budget_status()}


# ─────────── Phase 4 大脑层 ───────────
class BrainAskRequest(BaseModel):
    question: str
    session_id: str = ""
    tenant_id: str = "default"
    intent: str = ""
    intent_confidence: float = 1.0
    context: list[dict] = Field(default_factory=list)   # 最近 K 轮上下文（由 session-manager 传入）
    use_cache: bool = True


class BrainPlanRequest(BaseModel):
    question: str
    intent: str = ""
    intent_confidence: float = 1.0


def resolve_tenant(request: Request, body_tenant: str = "") -> str:
    """租户标识的**唯一解析口径**。

    约定：请求头 `X-Tenant-Id` 优先（传输层身份），其次请求体 `tenant_id`，最后回退 `default`。
    本服务所有「写入 + 查询」必须走同一来源 —— 否则会出现
    「按 A 租户落库、按 B 租户回放」的 404 假象，把真实缺陷伪装成"数据不存在"。
    """
    header_tenant = (request.headers.get("x-tenant-id") or "").strip()
    if header_tenant:
        return header_tenant
    body_value = (body_tenant or "").strip()
    return body_value or "default"


@app.post("/api/nlp/brain/ask")
def brain_ask(req: BrainAskRequest, request: Request):
    """R4-06 端到端问答：规划 → 检索 → 生成 → 来源标注（含缺口检测与语义缓存）。"""
    if not req.question.strip():
        raise HTTPException(status_code=400, detail="question must not be blank")
    tenant_id = resolve_tenant(request, req.tenant_id)
    # 未提供意图时用本服务意图级联补齐（与 Phase 2 同源）
    intent, confidence = req.intent, req.intent_confidence
    if not intent:
        try:
            recognized = CASCADE.recognize(req.question, req.session_id, tenant_id)
            intent = recognized.intent
            confidence = recognized.confidence
        except Exception:  # noqa: BLE001 - 意图失败不阻断问答，降级为未知意图
            intent, confidence = "", 0.0
    result = BRAIN_PIPELINE.run(
        question=req.question,
        session_id=req.session_id,
        tenant_id=tenant_id,
        intent=intent,
        intent_confidence=confidence,
        context=req.context,
        use_cache=req.use_cache,
        deadline_ms=BRAIN_TOTAL_BUDGET_MS,
    )
    LATENCY_WINDOW.append(float(result.latency_ms))
    return result.to_dict()


@app.post("/api/nlp/brain/plan")
def brain_plan(req: BrainPlanRequest):
    """R4-05 任务规划（只规划不执行）：意图 → 任务模板 + 槽位。"""
    if not req.question.strip():
        raise HTTPException(status_code=400, detail="question must not be blank")
    return PLANNER.plan(req.question, req.intent, req.intent_confidence).to_dict()


@app.get("/api/nlp/brain/health")
def brain_health():
    """大脑层健康：LLM 路由 / 语义缓存 / 规划器（降级状态必须可见）。"""
    ensure_role_engines()
    return {
        "llm": BRAIN_PIPELINE.gateway.health(),
        "model_config": model_config.status(),
        "semantic_cache": SEMANTIC_CACHE.health(),
        "planner": {"name": "SimplePlanner", "engine": "rule"},
        "retrieval": {"base_url": RETRIEVER.base_url, "timeout": RETRIEVER.timeout},
        "audit": {"storage": AUDIT_LOG.storage(), "stats": AUDIT_LOG.stats()},
        "latency": latency_percentiles(),
    }


@app.get("/api/nlp/brain/cache/stats")
def brain_cache_stats():
    """R4-04 语义缓存命中统计（命中率 = hits / lookups）。"""
    return SEMANTIC_CACHE.health()


@app.get("/api/nlp/brain/decision/{decision_id}")
def brain_decision(decision_id: str, request: Request):
    """D5/X4 思考链回放：读 **IN3 AuditLog** 真实记录。

    口径：**查不到就是 404**（未找到 ≠ 空链），不存在「200 + 空数据」的伪成功；
    跨租户访问与"不存在"同等处理，不泄露决策是否曾发生。
    """
    tenant_id = resolve_tenant(request)
    record = AUDIT_LOG.replay(decision_id, tenant_id)
    if not record:
        raise HTTPException(status_code=404, detail="decision not found: " + decision_id)
    return record


@app.get("/api/nlp/brain/decisions")
def brain_decisions(request: Request, limit: int = 10):
    """最近决策索引（供决策沙盘选择与大脑视图展示）。"""
    tenant_id = resolve_tenant(request)
    return {
        "tenant_id": tenant_id,
        "items": AUDIT_LOG.recent(tenant_id, max(1, min(int(limit), 100))),
        "storage": AUDIT_LOG.storage(),
    }


# ─────────── IN-02 记忆图谱 ───────────
class MemoryIngestRequest(BaseModel):
    text: str
    tenant_id: str = "default"


class MemoryCompressRequest(BaseModel):
    text: str
    tenant_id: str = "default"
    depth: int = 2


@app.post("/api/nlp/brain/memory/ingest")
def brain_memory_ingest(req: MemoryIngestRequest, request: Request):
    """IN-02：会话/文档文本 → 实体关系抽取并落图（规则抽取，如实标 extractor=rule）。"""
    if not req.text.strip():
        raise HTTPException(status_code=400, detail="text must not be blank")
    return MEMORY_GRAPH.ingest(req.text, resolve_tenant(request, req.tenant_id))


@app.get("/api/nlp/brain/memory/subgraph")
def brain_memory_subgraph(root: str, request: Request, tenant_id: str = "default", depth: int = 2):
    """IN-02：按根实体加载子图（递归 CTE；实体不存在时如实说明，不返回空成功）。"""
    graph = MEMORY_GRAPH.subgraph(root, resolve_tenant(request, tenant_id), depth)
    payload = graph.to_dict()
    payload["within_load_budget"] = graph.load_ms < 100
    payload["load_budget_ms"] = 100
    return payload


@app.post("/api/nlp/brain/memory/compress")
def brain_memory_compress(req: MemoryCompressRequest, request: Request):
    """IN-02：上下文压缩（实测压缩率，不达阈值如实标 meets_target=false）。"""
    if not req.text.strip():
        raise HTTPException(status_code=400, detail="text must not be blank")
    return MEMORY_GRAPH.compress(req.text, resolve_tenant(request, req.tenant_id), req.depth)


@app.get("/api/nlp/brain/memory/stats")
def brain_memory_stats(request: Request, tenant_id: str = "default"):
    """IN-02：图谱规模与存储后端（PG 不可用时降级可见）。"""
    return MEMORY_GRAPH.stats(resolve_tenant(request, tenant_id))


def latency_percentiles() -> dict:
    """问答耗时分位（P50/P95/P99），样本不足时如实标注 sample_size。"""
    samples = sorted(LATENCY_WINDOW)
    if not samples:
        return {"sample_size": 0, "p50_ms": 0, "p95_ms": 0, "p99_ms": 0, "max_ms": 0, "basis": "no_sample"}
    def at(q: float) -> float:
        index = min(len(samples) - 1, round((len(samples) - 1) * q))
        return float(samples[index])
    return {
        "sample_size": len(samples),
        "p50_ms": round(at(0.50), 1),
        "p95_ms": round(at(0.95), 1),
        "p99_ms": round(at(0.99), 1),
        "max_ms": round(float(samples[-1]), 1),
        "basis": "recent_window_500",
    }


# ─────────── 四肢层：Function Calling（R5-06）───────────
class ToolPlanRequest(BaseModel):
    question: str
    tenant_id: str = "default"


class ToolRunRequest(BaseModel):
    question: str
    tenant_id: str = "default"


@app.get("/api/nlp/tools/specs")
def tool_specs():
    """工具描述（**运行时从 tool-executor 注册表拉取**，Python 侧不另存一份工具真相）。"""
    return FUNCTION_CALLING.specs()


@app.get("/api/nlp/tools/health")
def tool_health():
    """四肢层连通性（含沙箱后端与是否降级），供工作平台执行视图探测。"""
    return FUNCTION_CALLING.health()


@app.post("/api/nlp/tools/plan")
def tool_plan(req: ToolPlanRequest):
    """工具决策（R5-06 前半段）。**决策器为规则实现，如实返回 decider=rule**。"""
    if not (req.question or "").strip():
        raise HTTPException(status_code=400, detail="question must not be blank")
    return FUNCTION_CALLING.decide(req.question).to_dict()


@app.post("/api/nlp/tools/run")
def tool_run(req: ToolRunRequest):
    """组合任务（R5-06 验收："计算+查询"）——决策 → 执行 → 回填 → 回答。

    工具失败时**如实回填失败原因**，绝不编造一个看起来成功的答案。
    """
    if not (req.question or "").strip():
        raise HTTPException(status_code=400, detail="question must not be blank")
    outcome = FUNCTION_CALLING.run(req.question)
    LATENCY_WINDOW.append(outcome.get("tool_count", 0) * 10 + 5)
    return outcome


# ─────────── 意图识别 ───────────
class IntentRequest(BaseModel):
    text: str
    session_id: str = ""
    tenant_id: str = "default"


class IntentResponse(BaseModel):
    intent: str
    confidence: float
    entities: dict = {}
    engine: str = ""
    intent_tag: str = ""
    matched: list[str] = Field(default_factory=list)
    latency_ms: float = 0.0


class BatchIntentRequest(BaseModel):
    texts: list[str]
    tenant_id: str = "default"


@app.post("/api/nlp/intent", response_model=IntentResponse)
def recognize_intent(req: IntentRequest):
    """意图识别（R2-07 规则优先 → R2-08 L0 模型兜底）"""
    if req.text is None or not req.text.strip():
        raise HTTPException(status_code=400, detail="text must not be blank")
    outcome = CASCADE.recognize(req.text)
    return IntentResponse(**outcome.to_dict())


@app.post("/api/nlp/intent/batch")
def recognize_intent_batch(req: BatchIntentRequest):
    if not req.texts:
        raise HTTPException(status_code=400, detail="texts must not be empty")
    outcomes = CASCADE.recognize_batch(req.texts)
    results = [outcome.to_dict() for outcome in outcomes]
    engine_counts: dict[str, int] = {}
    for outcome in outcomes:
        engine_counts[outcome.engine] = engine_counts.get(outcome.engine, 0) + 1
    return {"count": len(results), "engine_distribution": engine_counts, "results": results}


@app.get("/api/nlp/intent/stats")
def intent_stats():
    """引擎画像：规则场景数 / 关键词数 / L0 训练规模"""
    return CASCADE.stats()


@app.post("/api/nlp/intent/eval")
def intent_eval():
    """TC-04 留出集评测 + Phase 4 需求口径（意图准确率 ≥90%）。

    **口径澄清（重要）**：线上走的是**级联引擎**（规则 → L0 兜底），
    因此需求 §DoD 的「意图准确率 ≥90%」必须以级联口径判定；
    L0 单独准确率只是兜底组件指标，一并回传但**不作为验收依据**。
    """
    l0_accuracy, summary = CASCADE.l0.accuracy(EVAL_CORPUS)
    hit = total = 0
    per_scenario: dict[str, list[int]] = {}
    for label, texts in EVAL_CORPUS.items():
        for text in texts:
            total += 1
            ok = CASCADE.recognize(text).intent == label
            hit += 1 if ok else 0
            bucket = per_scenario.setdefault(label, [0, 0])
            bucket[1] += 1
            bucket[0] += 1 if ok else 0
    cascade_accuracy = round(100.0 * hit / total, 2) if total else 0.0
    summary["basis"] = "cascade(rule->L0)"
    summary["cascade_accuracy"] = cascade_accuracy
    summary["cascade_samples"] = total
    summary["cascade_per_scenario"] = {k: round(100.0 * v[0] / v[1], 2) for k, v in per_scenario.items()}
    summary["l0_accuracy"] = l0_accuracy
    summary["core_scenario_accuracy"] = {k: v for k, v in summary["per_scenario"].items() if k in CORE_SCENARIOS}
    # 验收门槛按级联口径；延迟门槛仍按意图识别的亚毫秒目标
    summary["pass_accuracy"] = cascade_accuracy >= 90.0
    summary["pass_latency"] = summary["latency_p99_ms"] < 50.0
    return summary


# ─────────── OCR 通道（R2-03 视觉渠道）───────────
class OcrRequest(BaseModel):
    image_base64: str
    source: str = ""


class OcrResponseModel(BaseModel):
    text: str
    confidence: float
    engine: str
    latency_ms: float


@app.get("/api/nlp/ocr/health")
def ocr_health():
    return OCR_SERVICE.status()


@app.post("/api/nlp/ocr", response_model=OcrResponseModel)
def ocr(req: OcrRequest):
    """图片识别。**有独立预算**（GAP-04）：OCR 引擎卡住时按 504 如实超时，
    不再无穷等待后由调用方的读超时兜底 —— 那会把「慢」显示成「感官层不可用」。"""
    try:
        result = run_with_budget(
            lambda: OCR_SERVICE.recognize_base64(req.image_base64),
            OCR_BUDGET_MS, operation="nlp.ocr",
        )
    except BudgetExceeded as exc:
        raise HTTPException(status_code=504, detail=f"OCR 超出预算 {exc.budget_ms}ms") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return OcrResponseModel(text=result.text, confidence=result.confidence,
                            engine=result.engine, latency_ms=round(result.latency_ms, 3))


# ─────────── 嵌入通道（R3-03 躯体期）───────────
class EmbedRequest(BaseModel):
    texts: list[str] = Field(default_factory=list)


class EmbedResponseModel(BaseModel):
    vectors: list[list[float]]
    dim: int
    backend: str
    degraded: bool
    count: int
    latency_ms: float
    ms_per_text: float


@app.get("/api/nlp/embed/health")
def embed_health():
    """嵌入后端健康（body-service 据此决定集合维度并标注降级）"""
    return EMBEDDING_SERVICE.status()


@app.post("/api/nlp/embed", response_model=EmbedResponseModel)
def embed(req: EmbedRequest):
    """向量化。**有独立预算**（GAP-03）：原实现无超时，挂起时只能靠 body-service 的
    30s 读超时兜底，体层入库/检索会被一起拖死。超时按 504 如实返回。"""
    try:
        result = run_with_budget(
            lambda: EMBEDDING_SERVICE.encode([text or "" for text in req.texts]),
            EMBED_BUDGET_MS, operation="nlp.embed",
        )
    except BudgetExceeded as exc:
        raise HTTPException(status_code=504, detail=f"嵌入超出预算 {exc.budget_ms}ms") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return EmbedResponseModel(vectors=result.vectors, dim=result.dim, backend=result.backend,
                              degraded=result.degraded, count=len(result.vectors),
                              latency_ms=result.latency_ms, ms_per_text=result.ms_per_text)


# ─────────── 重排通道（R3-06 躯体期 · Should）───────────
class RerankCandidateModel(BaseModel):
    id: str
    text: str
    score: float = 0.0


class RerankRequest(BaseModel):
    query: str
    candidates: list[RerankCandidateModel] = Field(default_factory=list)
    top_k: int = 5


class RerankResponseModel(BaseModel):
    results: list[dict]
    backend: str
    degraded: bool
    count_in: int
    count_out: int
    latency_ms: float


@app.get("/api/nlp/rerank/health")
def rerank_health():
    return RERANKER_SERVICE.status()


@app.post("/api/nlp/rerank", response_model=RerankResponseModel)
def rerank(req: RerankRequest):
    began = time.perf_counter()
    try:
        results = RERANKER_SERVICE.rerank(
            req.query,
            [RerankCandidate(id=c.id, text=c.text, score=c.score) for c in req.candidates],
            top_k=req.top_k,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    elapsed_ms = (time.perf_counter() - began) * 1000
    status = RERANKER_SERVICE.status()
    return RerankResponseModel(
        results=[{"id": r.id, "text": r.text, "score": r.score, "rerank_score": r.rerank_score,
                  "rank_before": r.rank_before, "rank_after": r.rank_after} for r in results],
        backend=status["backend"], degraded=status["degraded"],
        count_in=len(req.candidates), count_out=len(results), latency_ms=round(elapsed_ms, 3),
    )


# ─────────── 分块器（R3-02 躯体期 · Python 侧口径）───────────
class ChunkRequest(BaseModel):
    content: str
    doc_id: str = ""
    max_chars: int = 800
    min_chars: int = 200
    overlap: int = 50


@app.post("/api/nlp/chunk")
def chunk(req: ChunkRequest):
    """与 Java 侧 ChunkProcessor 同算法（标题/段落边界 + 800/50），供跨语言一致性校验"""
    try:
        chunks = chunk_text(req.content, max_chars=req.max_chars, min_chars=req.min_chars,
                            overlap=req.overlap)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return {
        "doc_id": req.doc_id,
        "count": len(chunks),
        "chunks": [{"index": c.index, "heading": c.heading, "content": c.content,
                    "chars": len(c.content)} for c in chunks],
    }


# ─────────── WB-10 模型接入配置（前台可配置的大模型接口）───────────
#
# 口径（与全局约定一致）：
# * **唯一真相源**是 `llm_model_config` 表（见 `app/model_config.py`）；
# * 响应**只回 `api_key_hint`**（`sk-***last4`），明文与密文都不出接口；
# * 任何写操作后**立即 reload 角色引擎** —— 否则"前台配好了但不生效"，
#   排障方向会被误导到模型侧，而真正的原因是没重载。
class ModelConfigUpsert(BaseModel):
    """创建/更新模型配置的请求体（字段命名 snake_case，对齐 D5-2）。"""

    config_key: str = Field("", description="功能角色：intent/embed/rerank/generate/plan/code")
    name: str = Field("", description="同一角色下的显示名，角色内唯一")
    provider: str = "custom"
    base_url: str = ""
    model: str = ""
    api_key: str | None = Field(None, description="留空/省略 = 不改动已有凭据；空串 = 清空")
    tier: str = ""
    max_tokens: int | None = None
    temperature: float | None = None
    timeout_ms: int | None = None
    routing_weight: int | None = None
    enabled: bool = False
    extra: dict = Field(default_factory=dict)


def _config_error(exc: "model_config.ModelConfigError") -> JSONResponse:
    """配置层错误 → 统一信封（**保留配置层的原始 code**）。

    若走 `HTTPException`，会被全局处理器按状态码反查成 `AGENT_BUS_UNAVAILABLE`
    之类的位置相近但语义不符的码 —— 排查时会指向错误的子系统。
    """
    return JSONResponse(status_code=exc.status,
                        content=_envelope(exc.code, exc.message, exc.details))


def _reload_brief(result: dict) -> dict:
    return {
        "ok": bool(result.get("ok")),
        "roles": sorted((result.get("roles") or {}).keys()),
        "count": int(result.get("count") or 0),
        "error": str(result.get("error") or ""),
    }


@app.get("/api/nlp/models")
def list_models(request: Request):
    """模型配置列表（按功能角色分组）。密钥仅以脱敏形式出现。"""
    ensure_role_engines()
    try:
        payload = model_config.list_configs(resolve_tenant(request))
    except model_config.ModelConfigError as exc:
        return _config_error(exc)
    payload["llm"] = {
        "role_configured": sorted(BRAIN_PIPELINE.gateway.role_engines.keys()),
        "engines": BRAIN_PIPELINE.gateway.role_summary(),
    }
    return payload


@app.get("/api/nlp/models/roles")
def list_model_roles():
    """功能角色 / 供应商 / 层级字典（前台表单预填用，避免把字典硬编码在前端）。"""
    return {
        "roles": [
            {"key": role.key, "label": role.label, "default_tier": role.default_tier,
             "default_timeout_ms": role.default_timeout_ms, "description": role.description}
            for role in model_config.ROLES
        ],
        "providers": [
            {"key": key, "default_base_url": model_config.PROVIDER_DEFAULT_BASE_URL.get(key, "")}
            for key in model_config.PROVIDERS
        ],
        "tiers": list(model_config.TIERS),
        "storage": model_config.status(),
    }


@app.post("/api/nlp/models/reload")
def reload_models():
    """把库里的配置重新装配成角色引擎（**不重启进程**）。"""
    result = ensure_role_engines(force=True)
    return {"reload": _reload_brief(result), "llm": BRAIN_PIPELINE.gateway.health()["roles"]}


@app.get("/api/nlp/models/usage")
def model_usage(request: Request, days: int = 7):
    """Token 用量看板数据源（WB-10 后半句）。

    **口径必须与页面上的数字同源**：token 数按 `token_source` 分开累计，
    `provider` 是供应商自报的真实值、`estimated` 是字符估算（DEBT-016）。
    `estimated_share` 一览估算占比 —— 不为 0 时这些 token 只能当趋势看，不能当账单看。
    """
    ensure_role_engines()
    return token_usage.usage_summary(resolve_tenant(request), days=days)


@app.post("/api/nlp/models")
def create_model(request: Request, payload: ModelConfigUpsert):
    tenant = resolve_tenant(request, payload.extra.get("tenant_id", "") if isinstance(payload.extra, dict) else "")
    try:
        created = model_config.create_config(tenant, payload.model_dump(), actor=_actor(request))
    except model_config.ModelConfigError as exc:
        return _config_error(exc)
    return {"item": created, "reload": _reload_brief(ensure_role_engines(force=True))}


@app.patch("/api/nlp/models/{config_id:int}")
def update_model(request: Request, config_id: int, payload: ModelConfigUpsert):
    """局部更新：**只处理请求里显式出现的字段**（`exclude_unset`）。

    若把未提供的字段按默认值一并写入，会把 `base_url` 之类的既有值清成空串 ——
    前端"只改一个开关"的请求会顺手拆掉整条配置。
    """
    tenant = resolve_tenant(request)
    try:
        updated = model_config.update_config(tenant, config_id, payload.model_dump(exclude_unset=True),
                                            actor=_actor(request))
    except model_config.ModelConfigError as exc:
        return _config_error(exc)
    return {"item": updated, "reload": _reload_brief(ensure_role_engines(force=True))}


@app.delete("/api/nlp/models/{config_id:int}")
def delete_model(request: Request, config_id: int):
    tenant = resolve_tenant(request)
    try:
        removed = model_config.delete_config(tenant, config_id)
    except model_config.ModelConfigError as exc:
        return _config_error(exc)
    removed["reload"] = _reload_brief(ensure_role_engines(force=True))
    return removed


@app.post("/api/nlp/models/{config_id:int}/test")
def test_model(request: Request, config_id: int, timeout_ms: int = 5000):
    """连通性探测（`GET {base_url}/models`，不消耗 token）。见 model_config.probe_config 的口径说明。"""
    tenant = resolve_tenant(request)
    try:
        return model_config.probe_config(tenant, config_id, timeout_ms=max(1000, min(timeout_ms, 30000)))
    except model_config.ModelConfigError as exc:
        return _config_error(exc)


def _actor(request: Request) -> str:
    return str(request.headers.get("x-actor") or "wp-platform")[:64]


# 进程启动即尝试装载（PG 尚未就绪时会退避重试，见 ensure_role_engines）
ensure_role_engines()
