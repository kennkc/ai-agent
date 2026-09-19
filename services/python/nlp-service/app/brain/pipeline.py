"""R4-06 检索增强生成链路（RagPipeline）—— LangGraph StateGraph 串联。

节点流：
    plan（意图→任务模板） → retrieve（Phase3 检索） → gap（覆盖度评估）
      ├─ 充分 / 部分不足 → generate（Prompt 组装 → LLM） → annotate（来源标注）
      └─ 关键缺口      → insufficient（明确提示 + 建议补充）

设计约定（与全局一致）：

* **缓存优先**：进入管线前查 `SemanticCache`（相似 ≥0.95）→ 命中直接返回，带 `cache_hit=True`。
* **降级可见**：检索不可用 / LLM 不可用 / 模板生成，分别在
  `degraded_reasons`（数组）与 `generator` 字段如实标注，**不静默填充**。
* **决策链**：每步记录 `{step, model, latency_ms, confidence, io}`，
  为 D5 思考链回放与 X4 决策审计提供数据（对齐 AuditLog 的 `chain[]` 形态）。

> 注：需求文档选型 LangGraph 0.2+，本机安装为 1.2.11；`StateGraph` API 一致，
> 已在 `docs/技术债台账.md` 记录版本口径。
"""
from __future__ import annotations

import logging
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Optional, TypedDict

from langgraph.graph import END, StateGraph

from app.brain.gap import COVERAGE_THRESHOLD, GAP_DETECTOR, SOURCE_ANNOTATOR
from app.brain.llm_gateway import L1, L2, L3, LlmGateway, LlmUnavailable
from app.brain.planner import PLANNER, CHAT, NEEDS_RETRIEVAL, SUMMARIZE
from app.brain.retrieval import RetrievalUnavailable
from app.brain.semantic_cache import SEMANTIC_CACHE

logger = logging.getLogger("nlp-service.brain.pipeline")

SYSTEM_PROMPT = (
    "你是知识助手，严格基于提供的资料回答。\n"
    "约束：1) 资料不足时明确说明，不臆测；2) 引用来源用 [来源] 标注；"
    "3) 不使用资料外的知识回答事实性问题。"
)


class RagState(TypedDict, total=False):
    """LangGraph 状态通道（显式声明，避免 dict 模式下返回值整体覆盖状态）。"""

    question: str
    session_id: str
    tenant_id: str
    intent: str
    intent_confidence: float
    context: list[dict[str, Any]]
    use_cache: bool
    decision_id: str
    chain: list[dict[str, Any]]
    degraded_reasons: list[str]
    plan: dict[str, Any]
    chunks: list[dict[str, Any]]
    retrieval: dict[str, Any]
    gap: dict[str, Any]
    gap_has_gap: bool
    answer: str
    sources: list[dict[str, Any]]
    generator: str
    model: str
    tokens: dict[str, int]


@dataclass
class RagResult:
    answer: str
    question: str
    session_id: str = ""
    tenant_id: str = "default"
    intent: str = ""
    plan: dict[str, Any] = field(default_factory=dict)
    sources: list[dict[str, Any]] = field(default_factory=list)
    gap: dict[str, Any] = field(default_factory=dict)
    chain: list[dict[str, Any]] = field(default_factory=list)
    decision_id: str = ""
    generator: str = "template"       # template | model | none
    model: str = ""
    degraded: bool = False
    degraded_reasons: list[str] = field(default_factory=list)
    cache_hit: bool = False
    cache_similarity: float = 0.0
    latency_ms: int = 0
    tokens: dict[str, int] = field(default_factory=dict)
    retrieval: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict:
        return {
            "answer": self.answer,
            "question": self.question,
            "session_id": self.session_id,
            "tenant_id": self.tenant_id,
            "intent": self.intent,
            "plan": self.plan,
            "sources": self.sources,
            "gap": self.gap,
            "chain": self.chain,
            "decision_id": self.decision_id,
            "generator": self.generator,
            "model": self.model,
            "degraded": self.degraded,
            "degraded_reasons": list(self.degraded_reasons),
            "cache_hit": self.cache_hit,
            "cache_similarity": round(self.cache_similarity, 4),
            "latency_ms": self.latency_ms,
            "tokens": dict(self.tokens),
            "retrieval": dict(self.retrieval),
        }


class RagPipeline:
    """RAG 管线（可注入 gateway / retriever / cache 便于单测）。"""

    def __init__(
        self,
        gateway: Optional[LlmGateway] = None,
        retriever: Any = None,
        cache: Any = None,
        planner: Any = None,
        gap_detector: Any = None,
        annotator: Any = None,
        system_prompt: str = SYSTEM_PROMPT,
    ) -> None:
        self.gateway = gateway or LlmGateway()
        self.retriever = retriever
        self.cache = cache if cache is not None else SEMANTIC_CACHE
        self.planner = planner or PLANNER
        self.gap_detector = gap_detector or GAP_DETECTOR
        self.annotator = annotator or SOURCE_ANNOTATOR
        self.system_prompt = system_prompt
        self._graph = self._build_graph()

    # ── 对外入口 ──
    def run(
        self,
        question: str,
        session_id: str = "",
        tenant_id: str = "default",
        intent: str = "",
        intent_confidence: float = 1.0,
        context: Optional[list[dict[str, Any]]] = None,
        use_cache: bool = True,
    ) -> RagResult:
        started = time.time()
        decision_id = uuid.uuid4().hex
        state: dict[str, Any] = {
            "question": question or "",
            "session_id": session_id,
            "tenant_id": tenant_id or "default",
            "intent": intent,
            "intent_confidence": intent_confidence,
            "context": list(context or []),
            "use_cache": use_cache,
            "decision_id": decision_id,
            "chain": [],
            "degraded_reasons": [],
        }
        if use_cache:
            cached = self._cache_lookup(state)
            if cached:
                cached.latency_ms = int((time.time() - started) * 1000)
                return cached
        final = self._graph.invoke(state)
        result = self._to_result(final)
        result.latency_ms = int((time.time() - started) * 1000)
        # 只缓存「有生成结果」的回答：缺口/不可用结果不进缓存，避免把故障固化
        if use_cache and result.generator != "none":
            self.cache.store(question, result.to_dict(), tenant_id or "default")
        return result

    # ── 图构建 ──
    def _build_graph(self):
        graph = StateGraph(RagState)
        graph.add_node("plan", self._node_plan)
        graph.add_node("retrieve", self._node_retrieve)
        graph.add_node("gap", self._node_gap)
        graph.add_node("generate", self._node_generate)
        graph.add_node("insufficient", self._node_insufficient)
        graph.set_entry_point("plan")
        graph.add_edge("plan", "retrieve")
        graph.add_edge("retrieve", "gap")
        graph.add_conditional_edges(
            "gap",
            lambda state: "insufficient" if state.get("gap_has_gap") else "generate",
            {"insufficient": "insufficient", "generate": "generate"},
        )
        graph.add_edge("generate", END)
        graph.add_edge("insufficient", END)
        return graph.compile()

    # ── 节点 ──
    def _node_plan(self, state: dict[str, Any]) -> dict[str, Any]:
        started = time.time()
        plan = self.planner.plan(state["question"], state.get("intent", ""), state.get("intent_confidence", 1.0))
        return {
            "plan": plan.to_dict(),
            "intent": plan.intent,
            "chain": _step(state, "plan", model=plan.planner,
                           latency_ms=_ms(started), confidence=plan.confidence,
                           io={"question": state["question"], "template": plan.template}),
        }

    def _node_retrieve(self, state: dict[str, Any]) -> dict[str, Any]:
        started = time.time()
        plan = state.get("plan", {})
        if not plan.get("needs_retrieval", True):
            return {
                "chunks": [], "retrieval": {"backend": "none", "skipped": True, "latency_ms": 0},
                "chain": _step(state, "retrieve", model="none", latency_ms=_ms(started), confidence=1.0,
                               io={"skipped": True, "template": plan.get("template")}),
            }
        retriever = self.retriever
        if retriever is None:
            from app.brain.retrieval import RETRIEVER  # 延迟导入
            retriever = RETRIEVER
            self.retriever = retriever
        try:
            outcome = retriever.retrieve(state["question"], state.get("tenant_id", "default"),
                                         int(plan.get("top_k", 5) or 5))
            degraded_reasons = list(state.get("degraded_reasons", []))
            return {
                "chunks": outcome.chunks,
                "retrieval": outcome.to_dict(),
                "degraded_reasons": degraded_reasons,
                "chain": _step(state, "retrieve", model="body-service", latency_ms=_ms(started),
                               confidence=min(1.0, 0.5 + 0.1 * len(outcome.chunks)),
                               io={"top_k": plan.get("top_k"), "hits": len(outcome.chunks)}),
            }
        except RetrievalUnavailable as exc:
            reasons = list(state.get("degraded_reasons", [])) + [f"retrieval_unavailable: {exc}"]
            return {
                "chunks": [], "retrieval": {"backend": "unavailable", "error": str(exc),
                                            "latency_ms": _ms(started)},
                "degraded_reasons": reasons,
                "chain": _step(state, "retrieve", model="unavailable", latency_ms=_ms(started),
                               confidence=0.0, io={"error": str(exc)}),
            }

    def _node_gap(self, state: dict[str, Any]) -> dict[str, Any]:
        started = time.time()
        verdict = self.gap_detector.detect(state.get("chunks", []))
        return {
            "gap": verdict.to_dict(),
            "gap_has_gap": verdict.has_gap,
            "chain": _step(state, "gap", model="rule", latency_ms=_ms(started),
                           confidence=verdict.coverage,
                           io={"coverage": round(verdict.coverage, 4), "usable": verdict.usable_chunks}),
        }

    def _node_generate(self, state: dict[str, Any]) -> dict[str, Any]:
        started = time.time()
        chunks = state.get("chunks", [])
        sources = [s.to_dict() for s in self.annotator.annotate(chunks)]
        prompt = self._build_prompt(state, chunks)
        level = _level_for(state.get("plan", {}), len(chunks))
        degraded_reasons = list(state.get("degraded_reasons", []))
        generator, model, answer_text, tokens = "none", "", "", {}
        try:
            from app.brain.llm_gateway import LlmRequest

            response = self.gateway.generate(LlmRequest(prompt=prompt, system=self.system_prompt, level=level))
            answer_text = response.text
            generator = response.generator
            model = response.model
            tokens = {"prompt": response.prompt_tokens, "completion": response.completion_tokens}
            if response.degraded:
                degraded_reasons.append("llm_template_backend")
        except LlmUnavailable as exc:
            # 降级链路：检索结果直出（纯检索回答）+ 标注「未生成」
            degraded_reasons.append(f"llm_unavailable: {exc}")
            generator = "none"
            model = "unavailable"
            answer_text = _retrieval_only_answer(chunks)
        notice = state.get("gap", {}).get("notice", "")
        if notice:
            answer_text = f"{notice}\n\n{answer_text}" if answer_text else notice
        return {
            "answer": answer_text, "sources": sources, "generator": generator, "model": model,
            "tokens": tokens, "degraded_reasons": degraded_reasons,
            "chain": _step(state, "generate", model=model or "none", latency_ms=_ms(started),
                           confidence=0.6 if generator == "template" else 0.9,
                           io={"level": level, "prompt_chars": len(prompt), "sources": len(sources)}),
        }

    def _node_insufficient(self, state: dict[str, Any]) -> dict[str, Any]:
        """关键缺口：明确提示 + 建议补充 —— **不编造回答**。"""
        started = time.time()
        gap = state.get("gap", {})
        chunks = state.get("chunks", [])
        sources = [s.to_dict() for s in self.annotator.annotate(chunks)]
        advice = gap.get("advice") or "建议补充相关文档后重试"
        answer = (
            f"⚠️ 信息不足，暂不作答：{advice}。\n"
            f"当前检索到 {len(chunks)} 条候选片段，覆盖度 {gap.get('coverage', 0)}"
            # 阈值兜底必须取**当前生效的配置值**（环境变量可覆盖），
            # 写死 0.85 会在校准后给出与判定不一致的提示口径。
            f"（阈值 {gap.get('threshold', COVERAGE_THRESHOLD)}）。"
        )
        reasons = list(state.get("degraded_reasons", [])) + ["information_gap"]
        return {
            "answer": answer, "sources": sources, "generator": "none", "model": "none",
            "tokens": {}, "degraded_reasons": reasons,
            "chain": _step(state, "insufficient", model="rule", latency_ms=_ms(started),
                           confidence=0.0, io={"coverage": gap.get("coverage", 0), "advice": advice}),
        }

    # ── 工具 ──
    def _build_prompt(self, state: dict[str, Any], chunks: list[dict[str, Any]]) -> str:
        plan = state.get("plan", {})
        lines = [f"System: {self.system_prompt}", "Context:"]
        if chunks:
            for idx, chunk in enumerate(chunks[:8], start=1):
                title = chunk.get("title") or chunk.get("doc_id") or "知识库"
                content = " ".join(str(chunk.get("content", "")).split())
                lines.append(f"[{idx}][来源 {title}] {content[:400]}")
        else:
            lines.append("(无可用资料)")
        context = state.get("context") or []
        if context:
            recent = context[-6:]
            lines.append("History:")
            for turn in recent:
                role = turn.get("role", "user")
                content = " ".join(str(turn.get("content", "")).split())[:200]
                lines.append(f"- {role}: {content}")
        lines.append(f"模板: {plan.get('label', '')}")
        lines.append(f"Question: {state['question']}")
        lines.append("约束: 资料不足时明确说明; 引用来源标注 [来源]")
        return "\n".join(lines)

    def _cache_lookup(self, state: dict[str, Any]) -> Optional[RagResult]:
        try:
            cached = self.cache.lookup(state["question"], state.get("tenant_id", "default"))
        except Exception as exc:  # noqa: BLE001 - 缓存故障不得阻断主链路
            logger.warning("semantic cache lookup failed: %s", exc)
            return None
        if not cached:
            return None
        result = RagResult(
            answer=str(cached.get("answer", "")),
            question=state["question"],
            session_id=state.get("session_id", ""),
            tenant_id=state.get("tenant_id", "default"),
            intent=str(cached.get("intent", "")),
            plan=dict(cached.get("plan", {})),
            sources=list(cached.get("sources", [])),
            gap=dict(cached.get("gap", {})),
            chain=[{"step": "cache", "model": cached.get("cache_backend", "cache"), "latency_ms": 0,
                    "confidence": cached.get("cache_similarity", 1.0), "io": {"hit": True}}],
            decision_id=state.get("decision_id", ""),
            generator=str(cached.get("generator", "template")),
            model=str(cached.get("model", "")),
            degraded=bool(cached.get("degraded", False)),
            degraded_reasons=list(cached.get("degraded_reasons", [])),
            cache_hit=True,
            cache_similarity=float(cached.get("cache_similarity", 0) or 0),
            tokens=dict(cached.get("tokens", {})),
            retrieval=dict(cached.get("retrieval", {})),
        )
        return result

    def _to_result(self, state: dict[str, Any]) -> RagResult:
        reasons = list(state.get("degraded_reasons", []))
        return RagResult(
            answer=str(state.get("answer", "")),
            question=state.get("question", ""),
            session_id=state.get("session_id", ""),
            tenant_id=state.get("tenant_id", "default"),
            intent=str(state.get("intent", "")),
            plan=dict(state.get("plan", {})),
            sources=list(state.get("sources", [])),
            gap=dict(state.get("gap", {})),
            chain=list(state.get("chain", [])),
            decision_id=str(state.get("decision_id", "")),
            generator=str(state.get("generator", "none")),
            model=str(state.get("model", "")),
            degraded=bool(reasons),
            degraded_reasons=reasons,
            tokens=dict(state.get("tokens", {})),
            retrieval=dict(state.get("retrieval", {})),
        )


def _level_for(plan: dict[str, Any], hit_count: int) -> str:
    template = plan.get("template", "")
    if template == SUMMARIZE or hit_count >= 6:
        return L2
    if template in (CHAT,) or hit_count == 0:
        return L1
    return L1 if hit_count <= 2 else L2


def _retrieval_only_answer(chunks: list[dict[str, Any]]) -> str:
    if not chunks:
        return "（未生成）检索服务与生成服务均不可用，且无可用资料，无法作答。"
    lines = ["（未生成）生成服务不可用，以下为检索原文直出："]
    for idx, chunk in enumerate(chunks[:3], start=1):
        title = chunk.get("title") or chunk.get("doc_id") or "知识库"
        content = " ".join(str(chunk.get("content", "")).split())[:200]
        lines.append(f"[{idx}] 来源《{title}》：{content}")
    return "\n".join(lines)


def _step(state: dict[str, Any], step: str, model: str, latency_ms: int, confidence: float, io: dict) -> list:
    chain = list(state.get("chain", []))
    chain.append({
        "step": step, "model": model, "latency_ms": latency_ms,
        "confidence": round(float(confidence), 4), "io": io,
    })
    return chain


def _ms(started: float) -> int:
    return int((time.time() - started) * 1000)
