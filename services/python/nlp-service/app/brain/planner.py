"""R4-05 任务规划器（简单版）—— 意图 → 任务模板 + 参数槽位。

设计取舍：需求文档要求「意图→任务模板映射（问答/总结/检索 3 类）」+「参数槽位提取」。
本阶段为 **规则驱动模板映射**（无 LLM 参与规划），与 Phase 2 意图级联同源风格；
到 P5/P6 再升级为模型规划。**规则规划不是伪实现** —— 它会如实返回
`planner=rule`，调用方据此判断规划可信度。

DEBT-017: SimplePlanner 为规则模板映射（planner=rule），非模型规划
  触发点: P5 多模型策略阶段升级为 LLM 规划
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

# 任务模板：qa=检索增强问答 / summarize=汇总 / retrieve=纯检索 / chat=直答（不走检索）
QA = "qa"
SUMMARIZE = "summarize"
RETRIEVE = "retrieve"
CHAT = "chat"

TEMPLATE_LABELS = {
    QA: "检索增强问答",
    SUMMARIZE: "汇总归纳",
    RETRIEVE: "知识检索",
    CHAT: "直接对话",
}

# 意图 → 模板（intent 标签来自 Phase 2 意图级联）
_INTENT_TO_TEMPLATE = {
    "知识问答": QA,
    "故障排查": QA,
    "数据查询": QA,
    "代码助手": QA,
    "汇总报告": SUMMARIZE,
    "情报采集": SUMMARIZE,
    "文档写作": CHAT,
    "任务创建": CHAT,
    "日程提醒": CHAT,
    "天气查询": CHAT,
    "计算器": CHAT,
    "情感闲聊": CHAT,
    "闲聊": CHAT,
}

# 需要检索的模板（是否需要访问 Phase 3 知识库）
NEEDS_RETRIEVAL = {QA, SUMMARIZE, RETRIEVE}

_DEFAULT_TEMPLATE = QA

_TOP_K_BY_TEMPLATE = {QA: 5, SUMMARIZE: 8, RETRIEVE: 10, CHAT: 0}

_QUOTED = re.compile(r"[《「『\"']([^》」』\"']{2,40})[》」』\"']")
_TIME_RANGE = re.compile(r"(最近|近)\s*(\d+)\s*(天|周|月|年|小时)")
_COUNT = re.compile(r"(前|top|TOP)\s*(\d{1,2})\s*(条|个|篇)?")


@dataclass
class Plan:
    template: str
    label: str
    intent: str
    needs_retrieval: bool
    top_k: int
    slots: dict[str, Any] = field(default_factory=dict)
    confidence: float = 1.0
    planner: str = "rule"
    reason: str = ""

    def to_dict(self) -> dict:
        return {
            "template": self.template,
            "label": self.label,
            "intent": self.intent,
            "needs_retrieval": self.needs_retrieval,
            "top_k": self.top_k,
            "slots": dict(self.slots),
            "confidence": self.confidence,
            "planner": self.planner,
            "reason": self.reason,
        }


class SimplePlanner:
    """意图 → 任务模板 + 槽位提取（规则版）。"""

    def plan(self, question: str, intent: str = "", intent_confidence: float = 1.0) -> Plan:
        text = (question or "").strip()
        resolved_intent = intent or _DEFAULT_INTENT_GUESS(text)
        template = _INTENT_TO_TEMPLATE.get(resolved_intent)
        reason = "意图命中模板映射"
        if template is None:
            template = _guess_template(text)
            reason = "意图未登记，按问题措辞推断模板"
        slots = self.extract_slots(text)
        top_k = _TOP_K_BY_TEMPLATE.get(template, 5)
        if "top_k" in slots:
            top_k = max(1, min(int(slots["top_k"]), 50))
        return Plan(
            template=template,
            label=TEMPLATE_LABELS[template],
            intent=resolved_intent,
            needs_retrieval=template in NEEDS_RETRIEVAL,
            top_k=top_k,
            slots=slots,
            confidence=round(min(1.0, max(0.0, intent_confidence)), 4),
            planner="rule",
            reason=reason,
        )

    @staticmethod
    def extract_slots(text: str) -> dict[str, Any]:
        slots: dict[str, Any] = {}
        quoted = _QUOTED.search(text or "")
        if quoted:
            slots["topic"] = quoted.group(1).strip()
        time_range = _TIME_RANGE.search(text or "")
        if time_range:
            slots["time_range"] = f"{time_range.group(1)}{time_range.group(2)}{time_range.group(3)}"
        count = _COUNT.search(text or "")
        if count:
            slots["top_k"] = int(count.group(2))
        if (text or "").strip().endswith("？") or (text or "").strip().endswith("?"):
            slots["question_mark"] = True
        return slots


def _guess_template(text: str) -> str:
    if re.search(r"总结|汇总|概括|归纳|梳理", text or ""):
        return SUMMARIZE
    if re.search(r"检索|查找|搜索|列出|有哪些", text or ""):
        return RETRIEVE
    return QA


def _DEFAULT_INTENT_GUESS(text: str) -> str:
    """无意图输入时的兜底猜测（仍标注 planner=rule，不谎称模型规划）。"""
    return "知识问答"


PLANNER = SimplePlanner()
