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
import time

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from app.chunking import chunk_text
from app.embedding import EMBEDDING_SERVICE
from app.intent import CASCADE, EVAL_CORPUS, CORE_SCENARIOS
from app.ocr import OCR_SERVICE
from app.reranker import RERANKER_SERVICE, RerankCandidate

app = FastAPI(
    title="agent-lifeform nlp-service",
    version="0.3.0",
    description="意图识别（规则+L0 双级级联） / 实体提取 / OCR / 嵌入 / 重排",
)


@app.get("/healthz")
def healthz():
    return {"status": "ok", "service": "nlp-service", "version": "0.3.0",
            "intent_engine": "cascade(rule+l0)", "ocr": OCR_SERVICE.status()["engine"],
            "embedding": EMBEDDING_SERVICE.status()["backend"],
            "reranker": RERANKER_SERVICE.status()["backend"]}


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
