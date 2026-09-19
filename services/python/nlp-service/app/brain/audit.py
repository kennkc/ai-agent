"""IN3 AuditLog —— 决策链审计日志（X4 决策沙盘 / D5 思考链回放的**唯一数据源**）。

需求口径（Phase4 需求设计文档 §10 X4）：

> D5 大脑决策沙盘 M1 **直接接真实 IN3 AuditLog**（VS1 已埋点），**不做 Mock 过渡** ——
> 思考链回放（意图→规划→检索→生成→自校验）为灯塔信任钩子。
> 验收：思考链 6 步可视化；每步含模型/耗时/置信度/io；决策置信度/合规审计/归因溯源三卡。
> 合规：决策链仅本人/管理员可见（审计敏感）。

因此本模块不是"日志打印"，而是**可回放的持久化账本**：

* 存储：PG 表 `brain_decision_log`（`payload` 为 JSONB，含完整 chain）；
  PG 不可用时降级为**进程内有界队列**并如实标 `degraded=True`
  （降级意味着"重启后不可回放"，不是"回放能成功但内容为空"）。
* 回放：按 `decision_id` + `tenant_id` 精确查询；**查不到就是没有**（返回 `None`，
  由调用方按仓库口径回 404 —— 绝不用「200 + 空链」冒充成功）。
* 租户隔离：回放强制带 `tenant_id` 过滤，跨租户访问与"不存在"同等处理（回 404），
  不泄露某条决策是否存在。

决策链 6 步：`intent → plan → retrieve → gap → generate → verify`
（"自校验"即 `verify`；`gap` 为覆盖度/缺口评估，是"是否够格生成"的判据步）。
"""
from __future__ import annotations

import json
import logging
import os
import time
from dataclasses import dataclass, field
from typing import Any, Optional

from app.brain import pg

logger = logging.getLogger("nlp-service.brain.audit")

MEMORY_CAPACITY = int(os.getenv("AUDIT_MEMORY_CAPACITY", "500"))

DDL = (
    """
    CREATE TABLE IF NOT EXISTS brain_decision_log (
        decision_id   TEXT PRIMARY KEY,
        tenant_id     TEXT NOT NULL,
        session_id    TEXT DEFAULT '',
        question      TEXT NOT NULL,
        intent        TEXT DEFAULT '',
        answer        TEXT DEFAULT '',
        generator     TEXT DEFAULT '',
        model         TEXT DEFAULT '',
        degraded      BOOLEAN DEFAULT FALSE,
        confidence    REAL DEFAULT 0,
        payload       JSONB NOT NULL,
        created_at    DOUBLE PRECISION NOT NULL,
        created_at_iso TEXT DEFAULT ''
    )
    """,
    """
    CREATE INDEX IF NOT EXISTS idx_brain_decision_tenant_time
        ON brain_decision_log (tenant_id, created_at DESC)
    """,
)

# 链步的规范顺序（用于回放时校验完整性）
CHAIN_STEPS = ("intent", "plan", "retrieve", "gap", "generate", "verify")


@dataclass
class Decision:
    """一条决策记录（对齐 D5-2 §3.10 AuditLog 形态）。"""

    decision_id: str
    question: str
    tenant_id: str = "default"
    session_id: str = ""
    intent: str = ""
    intent_confidence: float = 1.0
    plan: dict[str, Any] = field(default_factory=dict)
    answer: str = ""
    generator: str = "none"
    model: str = ""
    degraded: bool = False
    degraded_reasons: list[str] = field(default_factory=list)
    chain: list[dict[str, Any]] = field(default_factory=list)
    sources: list[dict[str, Any]] = field(default_factory=list)
    gap: dict[str, Any] = field(default_factory=dict)
    retrieval: dict[str, Any] = field(default_factory=dict)
    cache_hit: bool = False
    latency_ms: int = 0
    created_at: float = field(default_factory=time.time)

    # ── X4 三卡 ──
    @property
    def confidence(self) -> float:
        """决策置信度：链步置信度的加权（越靠后的步骤权重越高）。"""
        if self.cache_hit:
            return round(_chain_confidence(self.chain) or 0.6, 4)
        return round(_chain_confidence(self.chain), 4)

    @property
    def compliance(self) -> dict[str, Any]:
        """合规审计卡：回答是否"有据可依 / 如实标注 / 未伪造"。"""
        rules = [
            {
                "rule": "grounded",
                "label": "回答基于检索资料",
                "passed": bool(self.sources) or self.generator in ("none", ""),
                "detail": f"来源 {len(self.sources)} 条 / generator={self.generator}",
            },
            {
                "rule": "annotated",
                "label": "来源已标注",
                "passed": bool(self.sources) or "information_gap" in self.degraded_reasons,
                "detail": "缺口作答时不要求来源，但必须标 degraded",
            },
            {
                "rule": "honest_degrade",
                "label": "降级已如实标注",
                "passed": (not self.degraded) or bool(self.degraded_reasons),
                "detail": ",".join(self.degraded_reasons) or "无降级",
            },
            {
                "rule": "no_fabrication",
                "label": "未伪造生成",
                "passed": self.generator != "none" or bool(self.degraded_reasons),
                "detail": "generator=none 必须附带降级原因，否则视为伪造",
            },
        ]
        return {
            "passed": all(rule["passed"] for rule in rules),
            "rules": rules,
            "checks": len(rules),
            "failed": [rule["rule"] for rule in rules if not rule["passed"]],
        }

    @property
    def attribution(self) -> dict[str, Any]:
        """归因溯源卡：这条回答到底来自哪。"""
        return {
            "doc_ids": sorted({str(s.get("doc_id", "")) for s in self.sources if s.get("doc_id")}),
            "chunk_ids": [str(s.get("chunk_id", "")) for s in self.sources if s.get("chunk_id")],
            "retrieval_backend": str(self.retrieval.get("backend", "") or ""),
            "model": self.model or self.generator,
            "cache_hit": self.cache_hit,
            "steps": [step.get("step", "") for step in self.chain],
        }

    def to_dict(self) -> dict:
        return {
            "decision_id": self.decision_id,
            "tenant_id": self.tenant_id,
            "session_id": self.session_id,
            "question": self.question,
            "intent": self.intent,
            "intent_confidence": round(self.intent_confidence, 4),
            "plan": dict(self.plan),
            "answer": self.answer,
            "generator": self.generator,
            "model": self.model,
            "degraded": self.degraded,
            "degraded_reasons": list(self.degraded_reasons),
            "chain": list(self.chain),
            "sources": list(self.sources),
            "gap": dict(self.gap),
            "retrieval": dict(self.retrieval),
            "cache_hit": self.cache_hit,
            "latency_ms": self.latency_ms,
            "created_at": self.created_at,
            "created_at_iso": _iso(self.created_at),
            "confidence": self.confidence,
            "compliance": self.compliance,
            "attribution": self.attribution,
        }


def _chain_confidence(chain: list[dict[str, Any]]) -> float:
    """链步置信度加权：后步权重更高（生成/自校验决定最终可信度）。"""
    scored = [float(step.get("confidence", 0.0) or 0.0) for step in chain or []]
    if not scored:
        return 0.0
    total_weight = 0.0
    total = 0.0
    for index, value in enumerate(scored):
        weight = 1.0 + index
        total += value * weight
        total_weight += weight
    return round(total / total_weight, 4)


class AuditLog:
    """决策链账本：PG 持久化，不可用时进程内兜底（有界 + 标 degraded）。"""

    def __init__(self, capacity: int = MEMORY_CAPACITY) -> None:
        self.capacity = capacity
        self._memory: dict[str, dict[str, Any]] = {}
        self._writes = 0
        self._fallback_writes = 0

    # ── 写入 ──
    def record(self, decision: Decision) -> dict:
        payload = decision.to_dict()
        self._writes += 1
        ok = pg.ensure_schema(DDL)
        if ok:
            ok, _ = pg.execute([(
                """
                INSERT INTO brain_decision_log
                    (decision_id, tenant_id, session_id, question, intent, answer, generator,
                     model, degraded, confidence, payload, created_at, created_at_iso)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (decision_id) DO UPDATE SET
                    payload = EXCLUDED.payload,
                    confidence = EXCLUDED.confidence,
                    degraded = EXCLUDED.degraded
                """,
                (
                    decision.decision_id, decision.tenant_id, decision.session_id,
                    decision.question, decision.intent, decision.answer, decision.generator,
                    decision.model, decision.degraded, decision.confidence,
                    json.dumps(payload, ensure_ascii=False), decision.created_at,
                    _iso(decision.created_at),
                ),
            )])
        if not ok:
            self._fallback_writes += 1
            self._memory[decision.decision_id] = payload
            if len(self._memory) > self.capacity:
                oldest = sorted(self._memory.values(), key=lambda item: item.get("created_at", 0.0))
                for item in oldest[: len(self._memory) - self.capacity]:
                    self._memory.pop(item["decision_id"], None)
        payload["storage"] = self.storage()
        return payload

    # ── 回放 ──
    def replay(self, decision_id: str, tenant_id: str = "default") -> Optional[dict]:
        """按 decision_id 回放；**查不到返回 None**（调用方应回 404）。"""
        if not decision_id:
            return None
        if pg.status()["degraded"]:
            item = self._memory.get(decision_id)
            if item and str(item.get("tenant_id", "")) == str(tenant_id):
                return dict(item)
            return None
        ok, rows = pg.execute([(
            """
            SELECT payload FROM brain_decision_log
            WHERE decision_id = %s AND tenant_id = %s
            """,
            (decision_id, tenant_id),
        )], fetch=True)
        if ok and rows:
            return dict(rows[0][0])
        # PG 查询失败时仍尝试内存兜底（写入可能落在内存里）
        item = self._memory.get(decision_id)
        if item and str(item.get("tenant_id", "")) == str(tenant_id):
            return dict(item)
        return None

    # ── 列表（供大脑视图/沙盘检索最近决策）──
    def recent(self, tenant_id: str = "default", limit: int = 10) -> list[dict]:
        if pg.status()["degraded"]:
            items = [v for v in self._memory.values() if str(v.get("tenant_id", "")) == str(tenant_id)]
            items.sort(key=lambda item: item.get("created_at", 0.0), reverse=True)
            return [_summary(item) for item in items[:limit]]
        ok, rows = pg.execute([(
            """
            SELECT decision_id, question, intent, generator, degraded, confidence, created_at, created_at_iso
            FROM brain_decision_log WHERE tenant_id = %s
            ORDER BY created_at DESC LIMIT %s
            """,
            (tenant_id, limit),
        )], fetch=True)
        if not ok or not rows:
            return []
        return [
            {
                "decision_id": row[0], "question": row[1], "intent": row[2],
                "generator": row[3], "degraded": bool(row[4]),
                "confidence": float(row[5] or 0), "created_at": float(row[6] or 0),
                "created_at_iso": row[7],
            }
            for row in rows
        ]

    # ── 状态 ──
    def storage(self) -> dict:
        state = pg.status()
        return {
            "backend": state["backend"],
            "degraded": state["degraded"],
            "reason": state["reason"],
            "dsn": state["dsn"],
        }

    def stats(self) -> dict:
        return {
            "writes": self._writes,
            "fallback_writes": self._fallback_writes,
            "memory_entries": len(self._memory),
            "capacity": self.capacity,
            "storage": self.storage(),
        }


def _summary(item: dict[str, Any]) -> dict:
    return {
        "decision_id": item.get("decision_id", ""),
        "question": item.get("question", ""),
        "intent": item.get("intent", ""),
        "generator": item.get("generator", ""),
        "degraded": bool(item.get("degraded", False)),
        "confidence": float(item.get("confidence", 0) or 0),
        "created_at": float(item.get("created_at", 0) or 0),
        "created_at_iso": item.get("created_at_iso", ""),
    }


def _iso(epoch: float) -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(epoch))


AUDIT_LOG = AuditLog()
