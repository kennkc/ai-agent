# -*- coding: utf-8 -*-
"""跨服务预算（deadline）测试 —— REC-01 + GAP-03/04。

背景：超时值分散在各处，各自看都合理，**相遇即错**。2026-09-19 实测到的形状：
上游给 5s、下游内部最坏 5.7s，于是上游把「慢」表达成「不可用」，
而单测全绿、契约 0 FAIL —— 缺陷只存在于**两个数字的差值**里。

本文件守三类不变量：
  1. `run_with_budget` 超时**如实抛错**，不返回空值冒充「没有数据」；
  2. LLM 级联的最坏耗时由**总预算**封顶，而不是「各级超时 × 重试次数」的乘积；
  3. 预算在管线里**向下传播**（检索扣掉的时间不再留给生成）。

约定：**配置层面**预算为 None / 0 表示「不限」—— 这是显式选择，不是遗漏。
但**传播层面**相反：已经算出来的「剩余 0」含义是「时间到了」，上游**不得**把它当作
「不限」往下传（否则下游会重新变成无界）。两者是不同语义，见本文件末两个回归用例。
"""
from __future__ import annotations

import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app import main  # noqa: E402
from app.brain import pipeline as pipeline_mod  # noqa: E402
from app.brain.llm_gateway import L1, L2, LlmGateway, LlmRequest, LlmUnavailable, TemplateEngine  # noqa: E402
from app.brain.pipeline import RagPipeline  # noqa: E402
from app.brain.retrieval import RetrievalOutcome  # noqa: E402
from app.brain.semantic_cache import SemanticCache  # noqa: E402
from app.budget import (  # noqa: E402
    BRAIN_TOTAL_BUDGET_MS,
    BudgetExceeded,
    Deadline,
    budget_status,
    deadline_at,
    remaining_from,
    run_with_budget,
)


# ───────────────────────── run_with_budget ─────────────────────────

def test_within_budget_returns_value_untouched():
    assert run_with_budget(lambda: "ok", 5000, operation="t") == "ok"


def test_exceeding_budget_raises_instead_of_returning_empty():
    """超时**必须抛错** —— 若这里返回 `[]` / `None`，上游会把「慢」读成「没有」。"""
    with pytest.raises(BudgetExceeded) as excinfo:
        run_with_budget(lambda: time.sleep(2), 100, operation="slow-op")
    assert excinfo.value.budget_ms == 100
    assert excinfo.value.operation == "slow-op"


def test_budget_none_means_unlimited_not_zero():
    """None / 0 = 不限。把它当成 0 会让未配置的调用**立刻**失败。"""
    assert run_with_budget(lambda: time.sleep(0.15) or "done", None) == "done"
    assert run_with_budget(lambda: time.sleep(0.15) or "done", 0) == "done"


def test_business_exception_is_not_masked_by_budget():
    """预算只管「时间」，不得改写业务错误语义（否则 400 会被伪装成超时）。"""
    def boom():
        raise ValueError("bad payload")

    with pytest.raises(ValueError, match="bad payload"):
        run_with_budget(boom, 5000, operation="t")


# ───────────────────────── Deadline ─────────────────────────

def test_deadline_clamp_shrinks_step_timeout_to_remaining():
    deadline = Deadline(500, operation="t")
    assert deadline.clamp(8.0) <= 0.5 + 1e-6      # 单步 8s 被收进剩余 0.5s
    assert deadline.remaining_ms() <= 500


def test_deadline_clamp_returns_zero_when_exhausted_and_marks_it():
    deadline = Deadline(50, operation="t")
    time.sleep(0.08)
    assert deadline.clamp(5.0) == 0.0
    assert deadline.expired() is True
    assert deadline.exhausted is True


def test_deadline_without_budget_is_unbounded():
    deadline = Deadline(None, operation="t")
    assert deadline.remaining_ms() is None
    assert deadline.clamp(7.0) == 7.0
    assert deadline.expired() is False


def test_deadline_at_and_remaining_roundtrip():
    at = deadline_at(600)
    assert at is not None
    assert remaining_from(at) <= 600
    assert remaining_from(None) is None


# ───────────────────────── REC-01：级联受总预算封顶 ─────────────────────────

class _HangingEngine(TemplateEngine):
    """永不返回的引擎 —— 模拟「LLM 卡住」。每次调用都睡满它被允许的超时。"""

    name = "hanging"
    level = L2

    def __init__(self, level=L2):
        self.level = level
        # 类级计数：断言「预算封顶」的直接证据是**发起了几次调用**
        # （耗时受调度抖动影响，调用次数是确定性的）。
        _HangingEngine.calls = getattr(_HangingEngine, "calls", [])
        self.calls = _HangingEngine.calls

    def available(self):
        return True

    def generate(self, request, timeout):
        self.calls.append({"level": self.level, "timeout": timeout})
        time.sleep(timeout if timeout and timeout > 0 else 1.0)
        raise TimeoutError("engine hung until its timeout")


def _reset_hanging_calls():
    _HangingEngine.calls = []


def test_cascade_worst_case_is_the_product_without_deadline():
    """无 deadline 时最坏耗时**确实是乘积** —— 这是 REC-01 要消除的东西。

    L2 级联 = L2 + L1；每级 1 次重试（共 2 次尝试）。用默认超时算：
    这个数字随层级/重试数增长膨胀，上游无论给多大都可能不够。
    """
    gateway = LlmGateway(engines=[_HangingEngine(L2), _HangingEngine(L1)], max_retries=1)
    worst = gateway.cascade_worst_case_ms(level=L2)
    per_attempt = sum(gateway.timeouts.get(lv, 8.0) for lv in (L2, L1)) * 1000
    assert worst == int(per_attempt * 2)


def test_deadline_bounds_cascade_wall_clock_not_the_product():
    """核心不变量：有 deadline 时，级联**发起的调用次数**被预算封顶。

    最坏场景：两个引擎都永不返回、每级 3 次重试 —— 无 deadline 时要打 8 次调用、
    耗时数十秒。给 700ms 预算后，第一次调用吃掉几乎全部预算，
    第二次调用**根本不该发起**（`min(级超时, 剩余)` 已经 <= 0）。
    """
    _reset_hanging_calls()
    gateway = LlmGateway(engines=[_HangingEngine(L2), _HangingEngine(L1)], max_retries=2)
    assert gateway.cascade_worst_case_ms(level=L2) > 20_000   # 乘积远大于下面的预算

    started = time.monotonic()
    with pytest.raises(LlmUnavailable) as excinfo:
        gateway.generate(LlmRequest(prompt="问题", level=L2), deadline_ms=700)
    elapsed_ms = int((time.monotonic() - started) * 1000)

    assert elapsed_ms < 3000, f"级联超出预算太多：{elapsed_ms}ms"
    assert "budget exhausted" in str(excinfo.value)
    assert gateway.stats.deadline_exceeded == 1
    # 直接证据：只打了 1 次调用（无预算时会打 8 次）
    assert len(_HangingEngine.calls) == 1, _HangingEngine.calls
    # 那一次调用的超时也被收进了预算内（≤0.7s），而不是引擎自己的 8s
    assert _HangingEngine.calls[0]["timeout"] <= 0.7 + 1e-6


def test_deadline_stops_cascade_without_issuing_more_calls():
    """预算耗尽的瞬间**不再发起新调用** —— 否则会把时间浪费在注定失败的尝试上。"""
    gateway = LlmGateway(engines=[_HangingEngine(L2), _HangingEngine(L1)], max_retries=3)
    with pytest.raises(LlmUnavailable):
        gateway.generate(LlmRequest(prompt="问题", level=L2), deadline_ms=400)
    # L2 用光预算后不得再进 L1（每次尝试要 8s，若进入则 elapsed 会爆）
    assert gateway.stats.deadline_exceeded == 1


def test_deadline_does_not_block_a_fast_template_answer():
    """有预算不等于更容易降级 —— 快速可用的引擎照常返回。"""
    gateway = LlmGateway(engines=[TemplateEngine()], max_retries=1)
    response = gateway.generate(LlmRequest(prompt="问题", level=L1), deadline_ms=BRAIN_TOTAL_BUDGET_MS)
    assert response.text and response.backend == TemplateEngine.name
    assert gateway.stats.deadline_exceeded == 0


# ───────────────────────── 预算在管线内向下传播 ─────────────────────────

class _ChunkRetriever:
    def __init__(self):
        self.last_timeout = "unset"

    def retrieve(self, query, tenant_id="default", top_k=5, timeout=None):
        self.last_timeout = timeout
        return RetrievalOutcome(chunks=[{
            "chunk_id": "c1", "doc_id": "d1", "title": "设计文档", "heading": "Phase 4",
            "content": "大脑期包含会话状态机、LLM 网关与 RAG 链路。", "score": 0.95,
            "rerank_score": 0.95, "vector_backend": "qdrant",
        }], backend="fake")


def _pipeline(retriever, engines=None):
    return RagPipeline(
        gateway=LlmGateway(engines=engines or [TemplateEngine()]),
        retriever=retriever,
        cache=SemanticCache(redis_client=None, encoder=None),
    )


def test_retrieval_timeout_is_capped_by_total_budget():
    """检索拿到的超时 = min(自身超时, 剩余总预算) —— 不许它吃掉留给生成的时间。"""
    retriever = _ChunkRetriever()
    pipeline = _pipeline(retriever)
    pipeline.run("Phase 4 有哪些需求？", intent="知识问答", use_cache=False, deadline_ms=900)
    assert isinstance(retriever.last_timeout, float)
    assert 0 < retriever.last_timeout <= 0.9 + 1e-6


def test_budget_exhausted_before_retrieval_skips_downstream_and_reports_why(monkeypatch):
    """预算已耗尽 → 检索/生成都**不再发起**，并如实标注原因（不是「服务不可用」）。

    为什么不用 `deadline_ms=1` 这类"极小值"构造：那等于**赌**"这 1ms 内确实过去了工作"，
    机器快的时候会变成"预算还没用完"，测试随时序时红时绿。
    这里直接把截止点设到**过去**（`monotonic() - 1`）——「已耗尽」是被构造出来的事实，不是概率。
    """
    retriever = _ChunkRetriever()
    pipeline = _pipeline(retriever)
    monkeypatch.setattr(pipeline_mod, "deadline_at", lambda _ms: time.monotonic() - 1.0)
    result = pipeline.run("Phase 4 有哪些需求？", intent="知识问答",
                          use_cache=False, deadline_ms=1)
    # ① 检索**没有发起**（连超时参数都不该被赋过值）
    assert retriever.last_timeout == "unset"
    # ② 检索步如实标成「跳过 + 预算原因」，而不是「检索服务不可用」
    retrieve = next(s for s in result.chain if s["step"] == "retrieve")
    assert retrieve["model"] == "skipped", retrieve
    assert retrieve["io"]["reason"].startswith("budget_exhausted_before_retrieval"), retrieve
    # ③ 也没有任何生成发生（零 chunk → 走 insufficient 分支，绝不允许生成）
    assert result.generator == "none"
    assert any(r.startswith("budget_exhausted") for r in result.degraded_reasons), result.degraded_reasons


def test_generation_is_stopped_when_retrieval_ate_the_whole_budget(monkeypatch):
    """回归：`remaining = 0` 曾被当成「不限」传给网关 → **生成无界运行**。

    `Deadline` / `run_with_budget` 的约定是「0 = 不限」，所以**上游绝不能把「耗尽」表达成 0**。
    本用例以 `_node_generate` 为观测点（而不是整条管线）：整条跑的话，零 chunk 会先走
    `insufficient` 分支、根本到不了生成步 —— 那样的断言会因为**走错分支**而变绿，等于没守。
    """
    calls: list = []

    class _SpyGateway(LlmGateway):
        def generate(self, request, deadline_ms=None):  # type: ignore[override]
            calls.append(deadline_ms)
            return super().generate(request, deadline_ms)

    pipeline = RagPipeline(
        gateway=_SpyGateway(engines=[TemplateEngine()]),
        retriever=_ChunkRetriever(),
        cache=SemanticCache(redis_client=None, encoder=None),
    )
    monkeypatch.setattr(pipeline_mod, "deadline_at", lambda _ms: time.monotonic() - 1.0)
    state = {
        "question": "Phase 4 有哪些需求？", "tenant_id": "default", "plan": {"level": L2},
        "chunks": [{"chunk_id": "c1", "doc_id": "d1", "title": "设计文档", "heading": "Phase 4",
                    "content": "大脑期包含会话状态机、LLM 网关与 RAG 链路。", "score": 0.95,
                    "rerank_score": 0.95, "vector_backend": "qdrant"}],
        "chain": [], "degraded_reasons": [], "gap": {},
        "deadline_at": pipeline_mod.deadline_at(0), "deadline_ms": 1000,
    }
    out = pipeline._node_generate(state)  # noqa: SLF001 - 边界守卫，须精确观测该节点
    assert calls == [], f"预算耗尽后仍发起了生成调用：{calls}"
    assert out["generator"] == "none"
    last = out["chain"][-1]
    assert last["step"] == "generate" and last["model"] == "skipped", last
    assert last["io"]["reason"].startswith("budget_exhausted_before_generation"), last


def test_result_exposes_budget_so_slowness_is_visible_in_the_body():
    """响应用户的响应体里就带预算口径 —— 让「慢」可见，而不是等上游猜。"""
    pipeline = _pipeline(_ChunkRetriever())
    result = pipeline.run("Phase 4 有哪些需求？", intent="知识问答",
                          use_cache=False, deadline_ms=5000)
    body = result.to_dict()
    assert body["budget_ms"] == 5000
    assert 0 <= body["budget_remaining_ms"] <= 5000


# ───────────────────────── GAP-03/04：embed/ocr 独立预算 ─────────────────────────

def _client() -> TestClient:
    return TestClient(main.app)


def test_embed_times_out_with_504_not_empty_vectors(monkeypatch):
    """嵌入挂起 → 504 AGENT_TIMEOUT。**绝不能返回空向量**让体层以为「没有内容」。"""
    monkeypatch.setattr(main, "EMBED_BUDGET_MS", 120)

    def hanging(_texts):
        time.sleep(2)

    monkeypatch.setattr(main.EMBEDDING_SERVICE, "encode", hanging)
    response = _client().post("/api/nlp/embed", json={"texts": ["上海天气"]})
    assert response.status_code == 504
    body = response.json()
    assert body["code"] == "AGENT_TIMEOUT"
    assert "vectors" not in body


def test_ocr_times_out_with_504_not_empty_text(monkeypatch):
    """OCR 挂起 → 504 AGENT_TIMEOUT，不得返回空文本冒充「图里没字」。"""
    monkeypatch.setattr(main, "OCR_BUDGET_MS", 120)

    def hanging(_b64):
        time.sleep(2)

    monkeypatch.setattr(main.OCR_SERVICE, "recognize_base64", hanging)
    response = _client().post("/api/nlp/ocr", json={"image_base64": "aGk="})
    assert response.status_code == 504
    assert response.json()["code"] == "AGENT_TIMEOUT"


def test_embed_still_succeeds_within_budget():
    """加了预算不等于更容易失败 —— 正常路径照常返回向量。"""
    response = _client().post("/api/nlp/embed", json={"texts": ["上海天气"]})
    assert response.status_code == 200
    assert response.json()["count"] == 1


def test_healthz_exposes_named_budgets_with_registry_pointer():
    """运维要能一眼看到口径，以及「权威值在哪」。"""
    body = _client().get("/healthz").json()
    budget = body["budget"]
    for key in ("BRAIN_TOTAL_BUDGET_MS", "EMBED_BUDGET_MS", "OCR_BUDGET_MS", "RETRIEVAL_BUDGET_MS"):
        assert isinstance(budget[key], int) and budget[key] > 0
    assert budget["registry"] == "contracts/timeout-budget.yaml"
    assert budget_status()["BRAIN_TOTAL_BUDGET_MS"] == BRAIN_TOTAL_BUDGET_MS
