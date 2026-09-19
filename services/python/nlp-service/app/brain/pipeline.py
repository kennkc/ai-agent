"""R4-06 检索增强生成链路（RagPipeline）—— LangGraph StateGraph 串联。

节点流（**决策链 6 步**，对齐 X4/D5 的"思考链回放"要求）：

    intent（意图） → plan（规划） → retrieve（检索） → gap（覆盖度/缺口）
        ├─ 充分 / 部分不足 → generate（生成） ─┐
        └─ 关键缺口      → insufficient ──────┴→ verify（自校验） → END

设计约定（与全局一致）：

* **缓存优先**：进入管线前查 `SemanticCache`（相似 ≥0.95）→ 命中直接返回，带 `cache_hit=True`。
* **降级可见**：检索不可用 / LLM 不可用 / 模板生成 / 低依据度，分别在
  `degraded_reasons`（数组）与 `generator` 字段如实标注，**不静默填充**。
* **总预算（REC-01）**：`run(..., deadline_ms=...)` 给出端到端上限，**检索与生成共享**。
  截止点放在 state 里跨节点传递（不能挂 `self`：管线会被并发调用，
  挂实例上会让并发问答互相缩短预算）。检索步先按剩余预算收窄自己的超时，
  生成步拿到扣掉检索耗时后的余额 —— 于是「整条链路最坏多久」是一个可承诺的常量。
* **决策链落账**：每步记录 `{step, model, latency_ms, confidence, io}`，
  整条链写入 IN3 AuditLog（见 `app/brain/audit.py`）后可按 `decision_id` 回放 ——
  **X4 明确要求接真实审计，不做 Mock 过渡**。

> 注：需求文档选型 LangGraph 0.2+，本机安装为 1.2.11；`StateGraph` API 一致，
> 已在 `docs/技术债台账.md` 记录版本口径。
"""
from __future__ import annotations

import logging
import os
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Optional, TypedDict

from langgraph.graph import END, StateGraph

from app.brain.audit import AUDIT_LOG, CHAIN_STEPS, Decision
from app.brain.gap import COVERAGE_THRESHOLD, GAP_DETECTOR, SOURCE_ANNOTATOR
from app.brain.llm_gateway import L1, L2, L3, LlmGateway, LlmUnavailable
from app.brain.planner import PLANNER, CHAT, NEEDS_RETRIEVAL, SUMMARIZE, TEMPLATE_LABELS
from app.brain.retrieval import RetrievalUnavailable
from app.brain.semantic_cache import SEMANTIC_CACHE
from app.budget import BRAIN_TOTAL_BUDGET_MS, deadline_at, remaining_from

logger = logging.getLogger("nlp-service.brain.pipeline")

SYSTEM_PROMPT = (
    "你是知识助手，严格基于提供的资料回答。\n"
    "约束：1) 资料不足时明确说明，不臆测；2) 引用来源用 [来源] 标注；"
    "3) 不使用资料外的知识回答事实性问题。"
)

# 自校验口径：回答与检索资料的**依据度**下限（低于此值标注 low_groundedness）。
# 与缺口阈值同源 —— 阈值随嵌入/重排后端校准（见 gap.py 顶部注释与台账 §4.14）。
GROUNDEDNESS_FLOOR = float(os.getenv("GAP_GROUNDEDNESS_FLOOR", "0.25"))


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
    # 端到端截止点（time.monotonic 时间轴）—— 检索与生成共享；
    # 放在 state 里而非实例上：同一条管线会被并发调用，实例字段会串预算。
    deadline_at: float
    deadline_ms: int
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
    attribution: dict[str, Any]


@dataclass
class RagResult:
    answer: str
    question: str
    session_id: str = ""
    tenant_id: str = "default"
    intent: str = ""
    intent_confidence: float = 1.0
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
    attribution: dict[str, Any] = field(default_factory=dict)
    # REC-01：本次问答的总预算与剩余量 —— 让「慢」在响应体里就可见，
    # 而不是等到上游超时后由别人猜成「大脑层不可用」。
    budget_ms: int = 0
    budget_remaining_ms: int = 0

    def to_dict(self) -> dict:
        return {
            "answer": self.answer,
            "question": self.question,
            "session_id": self.session_id,
            "tenant_id": self.tenant_id,
            "intent": self.intent,
            "intent_confidence": round(self.intent_confidence, 4),
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
            "attribution": dict(self.attribution),
            "budget_ms": self.budget_ms,
            "budget_remaining_ms": self.budget_remaining_ms,
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
        audit: Any = None,
        system_prompt: str = SYSTEM_PROMPT,
    ) -> None:
        self.gateway = gateway or LlmGateway()
        self.retriever = retriever
        self.cache = cache if cache is not None else SEMANTIC_CACHE
        self.planner = planner or PLANNER
        self.gap_detector = gap_detector or GAP_DETECTOR
        self.annotator = annotator or SOURCE_ANNOTATOR
        self.audit = audit if audit is not None else AUDIT_LOG
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
        deadline_ms: Optional[int] = None,
    ) -> RagResult:
        """跑完整条 RAG 决策链。

        `deadline_ms`：**整条链路**（检索 + 生成）的总预算，缺省用
        `BRAIN_TOTAL_BUDGET_MS`。传 0 / None 会退回缺省 —— 不设上限的问答
        在 LLM 慢时会把上游连接拖死，而「拖死上游」比「降级并说明」更糟。
        """
        started = time.time()
        budget_ms = int(deadline_ms or BRAIN_TOTAL_BUDGET_MS)
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
            "deadline_at": deadline_at(budget_ms),
            "deadline_ms": budget_ms,
            "chain": [],
            "degraded_reasons": [],
        }
        if use_cache:
            cached = self._cache_lookup(state)
            if cached:
                cached.latency_ms = int((time.time() - started) * 1000)
                self._record(cached)
                return cached
        final = self._graph.invoke(state)
        result = self._to_result(final)
        result.latency_ms = int((time.time() - started) * 1000)
        # 只缓存「有生成结果」的回答：缺口/不可用结果不进缓存，避免把故障固化
        if use_cache and result.generator != "none":
            self.cache.store(question, result.to_dict(), tenant_id or "default")
        self._record(result)
        return result

    # ── 决策链落账（IN3 AuditLog）──
    def _record(self, result: RagResult) -> Optional[dict]:
        try:
            decision = Decision(
                decision_id=result.decision_id,
                question=result.question,
                tenant_id=result.tenant_id,
                session_id=result.session_id,
                intent=result.intent,
                intent_confidence=result.intent_confidence,
                plan=result.plan,
                answer=result.answer,
                generator=result.generator,
                model=result.model,
                degraded=result.degraded,
                degraded_reasons=list(result.degraded_reasons),
                chain=list(result.chain),
                sources=list(result.sources),
                gap=dict(result.gap),
                retrieval=dict(result.retrieval),
                cache_hit=result.cache_hit,
                latency_ms=result.latency_ms,
            )
            return self.audit.record(decision)
        except Exception as exc:  # noqa: BLE001 - 审计失败不得阻断问答
            logger.warning("audit record failed (decision_id=%s): %s", result.decision_id, exc)
            return None

    # ── 图构建 ──
    def _build_graph(self):
        graph = StateGraph(RagState)
        graph.add_node("intent", self._node_intent)
        graph.add_node("plan", self._node_plan)
        graph.add_node("retrieve", self._node_retrieve)
        graph.add_node("gap", self._node_gap)
        graph.add_node("generate", self._node_generate)
        graph.add_node("insufficient", self._node_insufficient)
        graph.add_node("verify", self._node_verify)
        graph.add_node("annotate", self._node_annotate)
        graph.set_entry_point("intent")
        graph.add_edge("intent", "plan")
        graph.add_edge("plan", "retrieve")
        graph.add_edge("retrieve", "gap")
        graph.add_conditional_edges(
            "gap",
            lambda state: "insufficient" if state.get("gap_has_gap") else "generate",
            {"insufficient": "insufficient", "generate": "generate"},
        )
        graph.add_edge("generate", "verify")
        graph.add_edge("insufficient", "verify")
        graph.add_edge("verify", "annotate")
        graph.add_edge("annotate", END)
        return graph.compile()

    # ── 节点 ──
    def _node_intent(self, state: dict[str, Any]) -> dict[str, Any]:
        """决策链第 1 步：意图。

        意图由调用方（session-manager 的 Phase2 意图级联）传入时**原样记录**；
        未传入时由规划器兜底猜测，并如实标注来源为 `planner-fallback`。
        """
        started = time.time()
        intent = str(state.get("intent", "") or "")
        confidence = float(state.get("intent_confidence", 1.0) or 1.0)
        return {
            "chain": _step(state, "intent", model="phase2-cascade" if intent else "planner-fallback",
                           latency_ms=_ms(started), confidence=confidence,
                           io={"intent": intent or "(未传入，由规划器兜底)", "confidence": round(confidence, 4)}),
        }

    def _node_plan(self, state: dict[str, Any]) -> dict[str, Any]:
        started = time.time()
        plan = self.planner.plan(state["question"], state.get("intent", ""), state.get("intent_confidence", 1.0))
        return {
            "plan": plan.to_dict(),
            "intent": plan.intent,
            "chain": _step(state, "plan", model=plan.planner,
                           latency_ms=_ms(started), confidence=plan.confidence,
                           io={"question": state["question"], "template": plan.template,
                               "label": TEMPLATE_LABELS.get(plan.template, plan.template)}),
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
        # 检索不能吃掉留给生成的时间：把本次调用收进「剩余总预算」内。
        # 预算已耗尽则**不发请求**，如实标注为预算问题（不是「检索服务不可用」）。
        remaining_ms = remaining_from(state.get("deadline_at"))
        if remaining_ms is not None and remaining_ms <= 0:
            reason = f"budget_exhausted_before_retrieval (budget={state.get('deadline_ms')}ms)"
            return {
                "chunks": [],
                "retrieval": {"backend": "skipped", "error": reason, "latency_ms": _ms(started)},
                "degraded_reasons": list(state.get("degraded_reasons", [])) + [reason],
                "chain": _step(state, "retrieve", model="skipped", latency_ms=_ms(started),
                               confidence=0.0, io={"reason": reason}),
            }
        try:
            outcome = retriever.retrieve(
                state["question"], state.get("tenant_id", "default"),
                int(plan.get("top_k", 5) or 5),
                timeout=(remaining_ms / 1000.0) if remaining_ms is not None else None,
            )
            degraded_reasons = list(state.get("degraded_reasons", []))
            return {
                "chunks": outcome.chunks,
                "retrieval": outcome.to_dict(),
                "degraded_reasons": degraded_reasons,
                "chain": _step(state, "retrieve", model="body-service", latency_ms=_ms(started),
                               confidence=min(1.0, 0.5 + 0.1 * len(outcome.chunks)),
                               io={"top_k": plan.get("top_k"), "hits": len(outcome.chunks),
                                   "budget_ms": remaining_ms}),
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
                           io={"coverage": round(verdict.coverage, 4), "usable": verdict.usable_chunks},
                           kind="substep", parent="retrieve"),
        }

    def _node_generate(self, state: dict[str, Any]) -> dict[str, Any]:
        started = time.time()
        chunks = state.get("chunks", [])
        sources = [s.to_dict() for s in self.annotator.annotate(chunks)]
        prompt = self._build_prompt(state, chunks)
        level = _level_for(state.get("plan", {}), len(chunks))
        degraded_reasons = list(state.get("degraded_reasons", []))
        generator, model, answer_text, tokens = "none", "", "", {}
        # 生成拿到的是**扣掉检索耗时后的余额** —— 这就是 deadline 的传播：
        # 「整条链路 ≤ 预算」由构造保证，而不是靠各级超时恰好加起来够用。
        remaining_ms = remaining_from(state.get("deadline_at"))
        # 预算已耗尽 → **不发起生成**。
        #
        # 注意 `remaining_from` 返回 0（“时间到了”）与返回 None（“本就没有截止”）是**两件事**。
        # 而 `Deadline` / `run_with_budget` 的既定约定是「0 = 不限」（显式选择，有测试守着），
        # 于是把“算出来的 0”原样传下去，会被下游读成“不限” ——
        # 后果是**检索吃光预算后生成照常无界运行**，「整条链路 ≤ 预算」的保证随即失效。
        # 所以必须在这一层拦住：约定不动，但绝不把「耗尽」表达成「不限」。
        if remaining_ms is not None and remaining_ms <= 0:
            reason = f"budget_exhausted_before_generation (budget={state.get('deadline_ms')}ms)"
            degraded_reasons.append(reason)
            notice = state.get("gap", {}).get("notice", "")
            answer_text = _retrieval_only_answer(chunks)
            if notice:
                answer_text = f"{notice}\n\n{answer_text}" if answer_text else notice
            return {
                "answer": answer_text, "sources": sources, "generator": "none", "model": "unavailable",
                "tokens": {}, "degraded_reasons": degraded_reasons,
                "chain": _step(state, "generate", model="skipped", latency_ms=_ms(started),
                               confidence=0.0, io={"reason": reason, "budget_ms": remaining_ms}),
            }
        try:
            from app.brain.llm_gateway import LlmRequest

            response = self.gateway.generate(
                LlmRequest(prompt=prompt, system=self.system_prompt, level=level),
                deadline_ms=remaining_ms,
            )
            answer_text = response.text
            generator = response.generator
            model = response.model
            tokens = {"prompt": response.prompt_tokens, "completion": response.completion_tokens}
            if response.degraded:
                degraded_reasons.append("llm_template_backend")
        except LlmUnavailable as exc:
            # 降级链路：检索结果直出（纯检索回答）+ 标注「未生成」
            # 预算耗尽与引擎不可用**分开标注** —— 前者是配置问题，后者是故障，
            # 混成一个 reason 会让排障方向从「调预算」跑偏到「查引擎」。
            reason = "llm_budget_exhausted" if "budget exhausted" in str(exc) else "llm_unavailable"
            degraded_reasons.append(f"{reason}: {exc}")
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
                           io={"level": level, "prompt_chars": len(prompt), "sources": len(sources),
                               "budget_ms": remaining_ms}),
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
                           confidence=0.0, io={"coverage": gap.get("coverage", 0), "advice": advice},
                           kind="substep", parent="generate"),
        }

    def _node_verify(self, state: dict[str, Any]) -> dict[str, Any]:
        """决策链末步：自校验（X4/D5 要求）。

        校验三件事，**只标注不改答案**（改答案就是伪造）：

        1. **依据度 groundedness** —— 回答中的实词有多少出现在检索资料里；
           偏低说明回答偏离资料，标 `low_groundedness`；
        2. **来源完整性** —— 生成了回答却没有来源，标 `missing_sources`；
        3. **缺口一致性** —— 判了信息缺口却仍生成了正文，标 `gap_inconsistent`。

        输出置信度 = 依据度与链步置信度的折中，供 X4「决策置信度」卡使用。
        """
        started = time.time()
        answer = str(state.get("answer", "") or "")
        sources = list(state.get("sources", []) or [])
        generator = str(state.get("generator", "none") or "none")
        reasons = list(state.get("degraded_reasons", []))
        groundedness = _groundedness(answer, sources)
        checks = {
            "groundedness": round(groundedness, 4),
            "floor": GROUNDEDNESS_FLOOR,
            "sources": len(sources),
            "answer_chars": len(answer),
        }
        if generator != "none" and answer and sources and groundedness < GROUNDEDNESS_FLOOR:
            reasons.append("low_groundedness")
        if generator != "none" and answer and not sources:
            reasons.append("missing_sources")
        if "information_gap" not in reasons and generator == "none" and answer and not reasons:
            reasons.append("unannotated_degrade")
        confidence = round(min(1.0, 0.5 * groundedness + 0.5 * (0.9 if generator == "model" else 0.6)), 4)
        return {
            "degraded_reasons": reasons,
            "chain": _step(state, "verify", model="rule", latency_ms=_ms(started),
                           confidence=confidence,
                           io={"checks": checks, "reasons": reasons[len(state.get("degraded_reasons", [])):]}),
        }

    def _node_annotate(self, state: dict[str, Any]) -> dict[str, Any]:
        """决策链末步：来源标注（R4-08 / X4 六步链的第 6 步）。

        之前标注动作藏在 generate 节点里（顺手做了但链上不可见），
        导致 X4 要求的「来源标注」这一步在回放时**查无此步**。
        本节点把它显式成链：去重、编号、算归因覆盖率，供三卡中的 attribution 使用。
        """
        started = time.time()
        sources = list(state.get("sources", []) or [])
        seen: set[str] = set()
        unique: list[dict[str, Any]] = []
        for item in sources:
            key = str(item.get("chunk_id") or item.get("doc_id") or item.get("title") or "")
            if key in seen:
                continue
            seen.add(key)
            unique.append(item)
        scored = [s for s in unique if s.get("score") is not None]
        attribution = {
            "cited": len(unique),
            "with_score": len(scored),
            "top_score": round(max((float(s["score"]) for s in scored), default=0.0), 4),
            "distinct_titles": len({str(s.get("title") or "") for s in unique}),
        }
        return {
            "sources": unique,
            "attribution": attribution,
            "chain": _step(state, "annotate", model="rule", latency_ms=_ms(started),
                           confidence=min(1.0, 0.4 + 0.2 * len(unique)),
                           io=attribution),
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
            intent_confidence=float(cached.get("intent_confidence", 1.0) or 1.0),
            plan=dict(cached.get("plan", {})),
            sources=list(cached.get("sources", [])),
            gap=dict(cached.get("gap", {})),
            chain=[{"step": "cache", "model": cached.get("cache_backend", "cache"), "latency_ms": 0,
                    "confidence": cached.get("cache_similarity", 1.0),
                    "io": {"hit": True, "cache_of": cached.get("decision_id", "")}}],
            decision_id=state.get("decision_id", ""),
            generator=str(cached.get("generator", "template")),
            model=str(cached.get("model", "")),
            degraded=bool(cached.get("degraded", False)),
            degraded_reasons=list(cached.get("degraded_reasons", [])),
            cache_hit=True,
            cache_similarity=float(cached.get("cache_similarity", 0) or 0),
            tokens=dict(cached.get("tokens", {})),
            retrieval=dict(cached.get("retrieval", {})),
            budget_ms=int(state.get("deadline_ms", 0) or 0),
            budget_remaining_ms=int(remaining_from(state.get("deadline_at")) or 0),
        )
        return result

    def _to_result(self, state: dict[str, Any]) -> RagResult:
        reasons = list(state.get("degraded_reasons", []))
        budget_ms = int(state.get("deadline_ms", 0) or 0)
        remaining = remaining_from(state.get("deadline_at"))
        return RagResult(
            answer=str(state.get("answer", "")),
            question=state.get("question", ""),
            session_id=state.get("session_id", ""),
            tenant_id=state.get("tenant_id", "default"),
            intent=str(state.get("intent", "")),
            intent_confidence=float(state.get("intent_confidence", 1.0) or 1.0),
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
            attribution=dict(state.get("attribution", {})),
            budget_ms=budget_ms,
            budget_remaining_ms=int(remaining or 0),
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


def _groundedness(answer: str, sources: list[dict[str, Any]]) -> float:
    """回答依据度：回答里的实词有多少能在检索资料中命中（词法口径，与当前后端一致）。

    与嵌入/重排同源于"当前是词法后端"，因此阈值同样可配；
    DEBT-010/011 闭合后这里应换成语义蕴含判定。
    """
    terms = _terms(answer)
    if not terms:
        return 0.0
    corpus = " ".join(str(s.get("snippet", "") or "") + " " + str(s.get("title", "") or "") for s in sources)
    if not corpus.strip():
        return 0.0
    hit = sum(1 for term in terms if term in corpus)
    return hit / len(terms)


def _terms(text: str, min_len: int = 2) -> list[str]:
    """粗分词：按非字母数字切分，过滤过短片段（中文按 2-gram 补）。"""
    import re

    raw = [t for t in re.split(r"[^\w一-龥]+", text or "") if len(t) >= min_len]
    terms: list[str] = []
    for token in raw:
        if any("一" <= ch <= "龥" for ch in token):
            terms.extend(token[i:i + 2] for i in range(len(token) - 1))
        else:
            terms.append(token.lower())
    return terms[:120]


def _step(state: dict[str, Any], step: str, model: str, latency_ms: int, confidence: float, io: dict,
          kind: str = "step", parent: str = "") -> list:
    """追加一个链步。

    X4 要求「思考链 6 步可视化」：意图 → 规划 → 检索 → 生成 → 自校验 → 标注。
    缺口检测（gap）是**检索结果的判定**，属于检索步的内部环节，
    故标 `kind='substep'` + `parent='retrieve'` —— 数据照记（回放要看），
    但沙盘按 6 个主步呈现，不会把 6 步撑成 7 步而与设计口径冲突。
    """
    chain = list(state.get("chain", []))
    entry = {
        "step": step, "model": model, "latency_ms": latency_ms,
        "confidence": round(float(confidence), 4), "io": io,
    }
    if kind != "step":
        entry["kind"] = kind
    if parent:
        entry["parent"] = parent
    chain.append(entry)
    return chain


def _ms(started: float) -> int:
    return int((time.time() - started) * 1000)
