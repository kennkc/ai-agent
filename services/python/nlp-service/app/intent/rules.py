"""规则意图引擎（R2-07）

关键词 + 正则 + 场景映射三路打分：
  - 关键词：命中权重累加（1.0 强特征 / 0.6 中 / 0.3 弱）
  - 正则：场景定位强特征（如算式、状态码）
  - 场景映射：命中后归一化为 [0,1) 置信分，阈值以上才判命中
多场景同时命中时取最高分，避免"算一下这批数据汇总"被误判为计算器。
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

from .corpus import (
    EXPLAIN_PREFIXES,
    OPERATION_DAMPING,
    OPERATIONAL_INTENTS,
    RULE_KEYWORDS,
    RULE_PATTERNS,
)

NORMALIZE_RE = re.compile(r"[\s,，。！!？?、；;：:\"'“”‘’（）()【】\[\]…~]+")
HIT_THRESHOLD = 0.35


@dataclass
class RuleHit:
    intent: str
    confidence: float
    matched: list[str] = field(default_factory=list)


class RuleIntentEngine:
    """确定性规则引擎：可解释、零依赖、亚毫秒级"""

    def __init__(self) -> None:
        self._compiled: dict[str, list[tuple[re.Pattern, float]]] = {}
        for intent, patterns in RULE_PATTERNS.items():
            self._compiled[intent] = [(re.compile(p, re.IGNORECASE), 1.0) for p in patterns]

    @staticmethod
    def normalize(text: str) -> str:
        return NORMALIZE_RE.sub("", (text or "").strip().lower())

    def score(self, text: str) -> dict[str, tuple[float, list[str]]]:
        """返回每个场景的 (综合得分, 命中特征)

        综合得分 = 0.7 × 特征强度 + 0.3 × 位置权重
        —— 中文意图核心常落在句尾动宾结构上，位置权重用于消解
        「算一下……汇总」这类多场景并列的歧义（汇总在句尾 → 判汇总报告）。

        位置权重取 `(0.5 + 0.5 × 句尾比例)` 而非裸比例：
        它只应让句尾特征**略微占优**（并列歧义的差值保持不变），
        而不能把句首强特征压到命中阈值以下——「定义一下……」命中的是句首特征，
        按裸比例算只有 0.2，会被误判为「未命中」而落到 L0 兜底成闲聊。
        """
        plain = self.normalize(text)
        raw_text = text or ""
        raw_len = max(1, len(raw_text))
        plain_len = max(1, len(plain))
        # 概念解释问句（「如何理解…」「什么是…」）里出现的操作类关键词属于**被解释的对象**，
        # 不是要执行的动作 —— 对操作类场景打折，避免「…的子图查询」被判成「数据查询」。
        explains_concept = any(prefix in plain for prefix in EXPLAIN_PREFIXES)
        scores: dict[str, tuple[float, list[str]]] = {}
        for intent, keywords in RULE_KEYWORDS.items():
            hit_weight = 0.0
            matched: list[str] = []
            last_end = 0
            for keyword, weight in keywords.items():
                if not keyword:
                    continue
                norm_kw = self.normalize(keyword)
                idx = plain.find(norm_kw) if norm_kw else -1
                if idx >= 0:
                    hit_weight += weight
                    matched.append(keyword)
                    last_end = max(last_end, idx + len(norm_kw))
            for pattern, weight in self._compiled.get(intent, []):
                m = pattern.search(raw_text)
                if m:
                    hit_weight += weight
                    matched.append(f"/{pattern.pattern}/")
                    # 正则命中位置换算到归一化文本坐标系，保证与关键词可比
                    last_end = max(last_end, int(m.end() / raw_len * plain_len))
            if hit_weight <= 0:
                continue
            normalized = hit_weight / (hit_weight + 1.0)          # 饱和函数 → [0,1)
            tail_ratio = min(1.0, last_end / plain_len)            # 句尾位置权重
            combined = 0.7 * normalized + 0.3 * (0.5 + 0.5 * tail_ratio)
            if explains_concept and intent in OPERATIONAL_INTENTS:
                combined *= OPERATION_DAMPING
            scores[intent] = (round(combined, 4), matched)
        return scores

    def recognize(self, text: str) -> RuleHit | None:
        scores = self.score(text)
        if not scores:
            return None
        intent, (combined, matched) = max(scores.items(), key=lambda kv: kv[1][0])
        if combined < HIT_THRESHOLD:
            return None
        # 置信度仍以特征强度为主（0.80 - 0.95）
        hit_keywords = RULE_KEYWORDS.get(intent, {})
        strength = sum(w for kw, w in hit_keywords.items() if kw and self.normalize(kw) in self.normalize(text))
        normalized = strength / (strength + 1.0) if strength > 0 else combined
        confidence = min(0.95, 0.80 + 0.15 * normalized)
        return RuleHit(intent=intent, confidence=round(confidence, 4), matched=matched[:5])

    @property
    def scenario_count(self) -> int:
        return len(RULE_KEYWORDS)

    @property
    def keyword_count(self) -> int:
        return sum(len(v) for v in RULE_KEYWORDS.values())
