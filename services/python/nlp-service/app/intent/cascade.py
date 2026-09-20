"""意图识别双级级联（R2-07 + R2-08）

输入文本 → 规则引擎命中?
  ├─ 是 → 返回（高置信 0.80-0.95，engine=RULE）
  └─ 否 → L0 模型分类
       ├─ 置信 ≥ 0.45 → 返回（中置信，engine=L0）
       └─ 否则 → 默认「闲聊」（0.50，engine=FALLBACK）
"""
from __future__ import annotations

import time
from dataclasses import dataclass, field

from .corpus import SCENARIO_LABELS
from .l0_model import L0_MODEL, UNKNOWN_CONFIDENCE, UNKNOWN_INTENT, L0IntentClassifier
from .rules import RuleIntentEngine


@dataclass
class IntentOutcome:
    intent: str
    confidence: float
    engine: str
    intent_tag: str = ""
    matched: list[str] = field(default_factory=list)
    latency_ms: float = 0.0
    route: str = ""

    def to_dict(self) -> dict[str, object]:
        return {
            "intent": self.intent,
            "confidence": round(self.confidence, 4),
            "engine": self.engine,
            "intent_tag": self.intent_tag,
            "matched": self.matched,
            "latency_ms": round(self.latency_ms, 3),
            "route": self.route,
        }


class IntentCascade:

    def __init__(self, rules: RuleIntentEngine | None = None, l0: L0IntentClassifier | None = None) -> None:
        self.rules = rules or RuleIntentEngine()
        self.l0 = l0 or L0_MODEL

    def recognize(self, text: str) -> IntentOutcome:
        started = time.perf_counter()
        hit = self.rules.recognize(text)
        if hit is not None:
            return IntentOutcome(
                intent=hit.intent, confidence=hit.confidence, engine="RULE",
                intent_tag=SCENARIO_LABELS.get(hit.intent, "unknown"),
                matched=hit.matched, latency_ms=(time.perf_counter() - started) * 1000,
                route="rule_first")

        prediction = self.l0.predict(text)
        engine = "L0" if prediction.intent != UNKNOWN_INTENT else "FALLBACK"
        intent = prediction.intent if engine == "L0" else UNKNOWN_INTENT
        confidence = prediction.confidence if engine == "L0" else max(UNKNOWN_CONFIDENCE, prediction.confidence)
        return IntentOutcome(
            intent=intent, confidence=confidence, engine=engine,
            intent_tag=SCENARIO_LABELS.get(intent, "smalltalk"),
            matched=[prediction.runner_up] if prediction.runner_up else [],
            latency_ms=(time.perf_counter() - started) * 1000,
            route="model_fallback")

    def recognize_batch(self, texts: list[str]) -> list[IntentOutcome]:
        return [self.recognize(text) for text in texts]

    def stats(self) -> dict[str, object]:
        return {
            "rule_scenarios": self.rules.scenario_count,
            "rule_keywords": self.rules.keyword_count,
            "l0": self.l0.stats(),
            "cascade": "rules(>=0.80) -> l0(>=0.45) -> fallback(闲聊)",
        }


CASCADE = IntentCascade()
