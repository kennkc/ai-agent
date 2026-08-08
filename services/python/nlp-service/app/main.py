# DEBT-003: 意图规则引擎（VS1/P2 简化版）— 触发点: P4 升级 规则+LLM 混合识别
"""
NLP 服务入口（Phase 0 最小骨架）
Phase 2: 意图识别规则引擎 + L0 分类模型
"""
from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI(
    title="agent-lifeform nlp-service",
    version="0.1.0",
    description="意图识别 / 实体提取 / 文本标准化",
)


@app.get("/healthz")
def healthz():
    return {"status": "ok", "service": "nlp-service", "version": "0.1.0"}


class IntentRequest(BaseModel):
    text: str
    session_id: str = ""
    tenant_id: str = "default"


class IntentResponse(BaseModel):
    intent: str
    confidence: float
    entities: dict = {}


# Phase 0: 规则占位，Phase 2 完善规则引擎
RULE_INTENTS = {
    "计算器": ["计算", "算一下", "多少乘以", "加", "减", "乘", "除"],
    "天气查询": ["天气", "气温", "会不会下雨", "温度"],
    "知识问答": ["什么是", "介绍一下", "如何", "为什么", "区别"],
    "汇总报告": ["汇总", "报告", "总结一下", "调研"],
}


@app.post("/api/nlp/intent", response_model=IntentResponse)
def recognize_intent(req: IntentRequest):
    """意图识别：规则匹配（Phase 2 升级 L0 模型）"""
    text = req.text
    for intent, keywords in RULE_INTENTS.items():
        if any(kw in text for kw in keywords):
            return IntentResponse(intent=intent, confidence=0.8)
    return IntentResponse(intent="闲聊", confidence=0.5)
