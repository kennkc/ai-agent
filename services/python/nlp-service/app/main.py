# DEBT-003: 意图识别已升级为「规则引擎 + L0 统计模型」双级级联（Phase 2）— 触发点: P4 升级 规则+LLM 混合识别
# DEBT-007: L0 模型采用字符 n-gram TF-IDF + 最近质心（非 BERT）— 触发点: P4 大脑期接入模型推理服务
"""
NLP 服务入口
Phase 0: 最小骨架
Phase 2: 感官期 —— 意图识别规则引擎 + L0 分类模型 + OCR 通道
"""
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from app.intent import CASCADE, EVAL_CORPUS, CORE_SCENARIOS
from app.ocr import OCR_SERVICE

app = FastAPI(
    title="agent-lifeform nlp-service",
    version="0.2.0",
    description="意图识别（规则+L0 双级级联） / 实体提取 / OCR",
)


@app.get("/healthz")
def healthz():
    return {"status": "ok", "service": "nlp-service", "version": "0.2.0",
            "intent_engine": "cascade(rule+l0)", "ocr": OCR_SERVICE.status()["engine"]}


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
