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

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.brain.llm_gateway import LlmGateway, LLM_GATEWAY, TemplateEngine
from app.brain.pipeline import RagPipeline
from app.brain.planner import PLANNER
from app.brain.retrieval import RETRIEVER
from app.brain.semantic_cache import SEMANTIC_CACHE
from app.chunking import chunk_text
from app.embedding import EMBEDDING_SERVICE
from app.intent import CASCADE, EVAL_CORPUS, CORE_SCENARIOS
from app.ocr import OCR_SERVICE
from app.reranker import RERANKER_SERVICE, RerankCandidate

app = FastAPI(
    title="agent-lifeform nlp-service",
    version="0.4.0",
    description="意图识别（规则+L0 双级级联） / 实体提取 / OCR / 嵌入 / 重排 / Phase4 大脑层（规划·RAG·语义缓存）",
)

# Phase 4 大脑层：LLM 路由 + RAG 管线（默认引擎为模板降级后端，见 llm_gateway）
BRAIN_PIPELINE = RagPipeline(gateway=LLM_GATEWAY, cache=SEMANTIC_CACHE)

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


@app.get("/healthz")
def healthz():
    return {"status": "ok", "service": "nlp-service", "version": "0.4.0",
            "intent_engine": "cascade(rule+l0)", "ocr": OCR_SERVICE.status()["engine"],
            "embedding": EMBEDDING_SERVICE.status()["backend"],
            "reranker": RERANKER_SERVICE.status()["backend"],
            "planner": "rule(SimplePlanner)", "llm": BRAIN_PIPELINE.gateway.health()["engines"],
            "semantic_cache": SEMANTIC_CACHE.backend}


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


@app.post("/api/nlp/brain/ask")
def brain_ask(req: BrainAskRequest):
    """R4-06 端到端问答：规划 → 检索 → 生成 → 来源标注（含缺口检测与语义缓存）。"""
    if not req.question.strip():
        raise HTTPException(status_code=400, detail="question must not be blank")
    # 未提供意图时用本服务意图级联补齐（与 Phase 2 同源）
    intent, confidence = req.intent, req.intent_confidence
    if not intent:
        try:
            recognized = CASCADE.recognize(req.question, req.session_id, req.tenant_id)
            intent = recognized.intent
            confidence = recognized.confidence
        except Exception:  # noqa: BLE001 - 意图失败不阻断问答，降级为未知意图
            intent, confidence = "", 0.0
    result = BRAIN_PIPELINE.run(
        question=req.question,
        session_id=req.session_id,
        tenant_id=req.tenant_id,
        intent=intent,
        intent_confidence=confidence,
        context=req.context,
        use_cache=req.use_cache,
    )
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
    return {
        "llm": BRAIN_PIPELINE.gateway.health(),
        "semantic_cache": SEMANTIC_CACHE.health(),
        "planner": {"name": "SimplePlanner", "engine": "rule"},
        "retrieval": {"base_url": RETRIEVER.base_url, "timeout": RETRIEVER.timeout},
    }


@app.get("/api/nlp/brain/cache/stats")
def brain_cache_stats():
    """R4-04 语义缓存命中统计（命中率 = hits / lookups）。"""
    return SEMANTIC_CACHE.health()


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
    """TC-04 留出集评测：≥10 场景 × 5 样本，输出准确率与延迟"""
    accuracy, summary = CASCADE.l0.accuracy(EVAL_CORPUS)
    summary["core_scenario_accuracy"] = {k: v for k, v in summary["per_scenario"].items() if k in CORE_SCENARIOS}
    summary["pass_accuracy"] = accuracy >= 85.0
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
    try:
        result = OCR_SERVICE.recognize_base64(req.image_base64)
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
    try:
        result = EMBEDDING_SERVICE.encode([text or "" for text in req.texts])
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
