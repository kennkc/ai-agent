"""Phase 4 大脑层测试（R4-03 LLM Gateway / R4-04 语义缓存 / R4-05 规划器 /
R4-06 RAG 链路 / R4-07 缺口检测 / R4-08 来源标注 + HTTP 端点）。

约定：每个「降级分支」都有对应用例 —— 降级必须可见，且不得伪造成功。
"""
from __future__ import annotations

import time

import pytest
from fastapi.testclient import TestClient

from app.brain.gap import GapDetector, SourceAnnotator
from app.brain.llm_gateway import (
    L1,
    L2,
    LlmGateway,
    LlmRequest,
    LlmUnavailable,
    TemplateEngine,
    estimate_tokens,
)
from app.brain.pipeline import RagPipeline
from app.brain.planner import CHAT, QA, RETRIEVE, SUMMARIZE, SimplePlanner
from app.brain.retrieval import RetrievalOutcome, RetrievalUnavailable
from app.brain.semantic_cache import CacheStats, SemanticCache, cosine_similarity
from app.main import app


# ───────────────────────── R4-03 LLM Gateway ─────────────────────────

def test_estimate_tokens_counts_chinese_by_chars():
    assert estimate_tokens("") == 0
    assert estimate_tokens("你好世界") == 3          # 4 char / 1.5 ≈ 2.67 → 3
    assert estimate_tokens("a" * 30) == 20


def test_template_engine_marks_degraded_not_fake():
    gateway = LlmGateway(engines=[TemplateEngine()])
    response = gateway.generate(LlmRequest(prompt="Question: Phase 4 有什么？\n[1][来源 设计] 大脑期含 FSM。", level=L1))
    assert response.degraded is True
    assert response.generator == "template"          # 诚实标注，不谎称模型生成
    assert "未接入生成模型" in response.text


def test_gateway_stats_accumulate_tokens_and_calls():
    gateway = LlmGateway(engines=[TemplateEngine()])
    gateway.generate(LlmRequest(prompt="问题一", level=L1))
    gateway.generate(LlmRequest(prompt="问题二", level=L1))
    stats = gateway.stats_dict()
    assert stats["calls"] == 2 and stats["degraded_calls"] == 2
    assert stats["prompt_tokens"] > 0 and stats["completion_tokens"] > 0


def test_gateway_falls_back_to_lower_level_on_timeout():
    class BoomL2(TemplateEngine):
        name = "boom-l2"
        level = L2

        def generate(self, request, timeout):
            raise TimeoutError("l2 timeout")

    gateway = LlmGateway(engines=[BoomL2(), TemplateEngine()], max_retries=0)
    response = gateway.generate(LlmRequest(prompt="问题", level=L2))
    assert response.level == L1                      # L2 超时 → 降级 L1
    assert gateway.stats.timeouts == 1


def test_gateway_raises_when_no_engine_available():
    class Dead(TemplateEngine):
        name = "dead"
        level = L1

        def available(self):
            return False

    gateway = LlmGateway(engines=[Dead()], max_retries=0)
    with pytest.raises(LlmUnavailable):
        gateway.generate(LlmRequest(prompt="问题", level=L1))


def test_gateway_health_reports_availability():
    gateway = LlmGateway(engines=[TemplateEngine()])
    health = gateway.health()
    assert health["available"] is True
    assert health["engines"][0]["level"] == L1


# ───────────────────────── R4-04 语义缓存 ─────────────────────────

def test_cosine_similarity_basic():
    assert cosine_similarity([1.0, 0.0], [1.0, 0.0]) == pytest.approx(1.0)
    assert cosine_similarity([1.0, 0.0], [0.0, 1.0]) == pytest.approx(0.0)
    assert cosine_similarity([], [1.0]) == 0.0


def test_semantic_cache_hits_identical_question():
    cache = SemanticCache(redis_client=None, encoder=_FakeEncoder())
    cache.store("今天天气如何", {"answer": "晴"}, tenant_id="t1")
    hit = cache.lookup("今天天气如何", tenant_id="t1")
    assert hit is not None and hit["answer"] == "晴"
    assert hit["cache_hit"] is True and hit["cache_backend"] == "memory"


def test_semantic_cache_miss_when_similarity_below_threshold():
    cache = SemanticCache(redis_client=None, encoder=_FakeEncoder(), threshold=0.95)
    cache.store("天气", {"answer": "晴"})
    assert cache.lookup("完全不同的问题", ) is None
    assert cache.stats.misses >= 1 and cache.stats.hits == 0


def test_semantic_cache_respects_threshold():
    cache = SemanticCache(redis_client=None, encoder=_FakeEncoder(), threshold=0.99)
    cache.store("问题A", {"answer": "a"})
    # 编码器对相近文本给 0.97 相似度 → 低于 0.99 阈值应 miss
    assert cache.lookup("问题A?", ) is None
    cache_low = SemanticCache(redis_client=None, encoder=_FakeEncoder(), threshold=0.90)
    cache_low.store("问题A", {"answer": "a"})
    assert cache_low.lookup("问题A?") is not None


def test_semantic_cache_expires_after_ttl():
    cache = SemanticCache(redis_client=None, encoder=_FakeEncoder(), ttl_seconds=0)
    cache.store("过期问题", {"answer": "x"})
    time.sleep(0.01)
    assert cache.lookup("过期问题") is None
    assert cache.stats.evictions >= 1


def test_semantic_cache_tenants_are_isolated():
    cache = SemanticCache(redis_client=None, encoder=_FakeEncoder())
    cache.store("共享问题", {"answer": "租户A"}, tenant_id="a")
    assert cache.lookup("共享问题", tenant_id="b") is None


def test_semantic_cache_hit_rate_and_degraded_flag():
    cache = SemanticCache(redis_client=None, encoder=_FakeEncoder(), stats=CacheStats())
    cache.store("q1", {"answer": "1"})
    cache.lookup("q1")                      # 命中（同向量）
    cache.lookup("完全不同的问题")             # 未命中（正交向量）
    health = cache.health()
    assert health["backend"] == "memory" and health["degraded"] is True
    assert health["stats"]["lookups"] == 2 and health["stats"]["hits"] == 1
    assert health["stats"]["hit_rate"] == pytest.approx(0.5)


# ───────────────────────── R4-05 规划器 ─────────────────────────

def test_planner_maps_intent_to_template():
    planner = SimplePlanner()
    assert planner.plan("知识库里怎么配置？", intent="知识问答").template == QA
    assert planner.plan("帮我总结一下", intent="汇总报告").template == SUMMARIZE
    assert planner.plan("你好", intent="闲聊").template == CHAT


def test_planner_extracts_slots_and_top_k():
    plan = SimplePlanner().plan("总结《架构设计》的要点，前 3 条", intent="汇总报告")
    assert plan.slots.get("topic") == "架构设计"
    assert plan.slots.get("top_k") == 3
    assert plan.top_k == 3
    assert plan.planner == "rule"


def test_planner_needs_retrieval_flag():
    assert SimplePlanner().plan("查资料", intent="知识问答").needs_retrieval is True
    assert SimplePlanner().plan("你好啊", intent="闲聊").needs_retrieval is False


def test_planner_guesses_template_when_intent_unknown():
    plan = SimplePlanner().plan("列出所有接口", intent="未登记意图")
    assert plan.template == RETRIEVE
    assert plan.reason  # 推断依据要留痕


# ───────────────────────── R4-07 缺口检测 ─────────────────────────

def test_gap_sufficient_when_scores_high():
    # 设计文档 §6 口径：语义嵌入 + 交叉编码重排下 0.85
    detector = GapDetector(threshold=0.85, min_usable=0.35)
    verdict = detector.detect([{"rerank_score": 0.95}, {"rerank_score": 0.9}])
    assert verdict.sufficient and not verdict.has_gap
    assert verdict.coverage >= 0.85


def test_gap_partial_insufficient_but_usable():
    detector = GapDetector(threshold=0.85, min_usable=0.35)
    verdict = detector.detect([{"score": 0.6}])
    assert not verdict.sufficient and not verdict.has_gap
    assert "部分信息可能不完整" in verdict.notice


def test_gap_uses_vector_score_not_compressed_rerank():
    """词法重排的 rerank_score 被压缩在 0.1 附近，若拿它判「可用」会全判缺口。

    实测（2026-09-19）：score 0.20~0.28 / rerank_score 0.10~0.12。
    判定统一以 score 为准，rerank_score 只用于排序展示。
    """
    detector = GapDetector(threshold=0.35, min_usable=0.15)
    verdict = detector.detect([{"score": 0.27, "rerank_score": 0.12},
                               {"score": 0.26, "rerank_score": 0.12}])
    assert verdict.usable_chunks == 2
    assert verdict.has_gap is False
    assert verdict.coverage >= 0.35


def test_gap_critical_when_no_usable_chunk():
    verdict = GapDetector().detect([])
    assert verdict.has_gap and verdict.usable_chunks == 0
    assert "信息不足" in verdict.notice


def test_gap_defaults_are_calibrated_for_current_backends():
    """默认阈值按当前后端实测分布校准（哈希嵌入 + 词法重排，得分约 0.15~0.35）。

    需求文档口径 0.85 假设 bge-m3 + bge-reranker（DEBT-010/011 未闭合），
    直接套用会让所有问题都判「信息不足」——那不是知识库没数据，而是尺子不对。
    """
    from app.brain import gap as gap_module

    detector = GapDetector()          # 默认（校准值）
    verdict = detector.detect([{"score": 0.27}, {"score": 0.26}, {"score": 0.25}])
    assert verdict.has_gap is False          # 有可用片段，不应判关键缺口
    assert verdict.usable_chunks == 3
    assert detector.threshold == gap_module.COVERAGE_THRESHOLD
    assert detector.threshold <= 0.85       # 校准值不高于设计口径


# ───────────────────────── R4-08 来源标注 ─────────────────────────

def test_source_annotator_builds_citations():
    sources = SourceAnnotator(snippet_limit=20).annotate([
        {"chunk_id": "c1", "doc_id": "d1", "title": "设计文档", "content": "x" * 50,
         "score": 0.8, "rerank_score": 0.9, "vector_backend": "qdrant"},
    ])
    assert len(sources) == 1
    payload = sources[0].to_dict()
    assert payload["title"] == "设计文档" and payload["backend"] == "qdrant"
    assert payload["snippet"].endswith("…") and len(payload["snippet"]) == 21
    assert payload["channel"] == "知识库"


# ───────────────────────── R4-06 RAG 链路 ─────────────────────────

def _pipeline(retriever, cache=None, engines=None):
    return RagPipeline(
        gateway=LlmGateway(engines=engines or [TemplateEngine()]),
        retriever=retriever,
        cache=cache or SemanticCache(redis_client=None, encoder=_FakeEncoder()),
    )


def test_rag_pipeline_full_chain_with_sources():
    pipeline = _pipeline(_FakeRetriever([_chunk(0.95)]))
    result = pipeline.run("Phase 4 有哪些需求？", intent="知识问答")
    assert [step["step"] for step in result.chain] == ["plan", "retrieve", "gap", "generate"]
    assert result.sources and result.sources[0]["title"] == "设计文档"
    assert result.generator == "template" and result.degraded is True
    assert "llm_template_backend" in result.degraded_reasons
    assert result.decision_id


def test_rag_pipeline_cache_hit_on_second_question():
    pipeline = _pipeline(_FakeRetriever([_chunk(0.95)]))
    first = pipeline.run("什么是大脑期？", intent="知识问答")
    second = pipeline.run("什么是大脑期？", intent="知识问答")
    assert first.cache_hit is False and second.cache_hit is True
    assert second.cache_similarity >= 0.95


def test_rag_pipeline_reports_gap_when_retrieval_unavailable():
    pipeline = _pipeline(_DeadRetriever())
    result = pipeline.run("随便问点什么", intent="知识问答")
    assert result.gap["has_gap"] is True
    assert "信息不足" in result.answer
    assert result.generator == "none"
    assert any("retrieval_unavailable" in r for r in result.degraded_reasons)


def test_rag_pipeline_degrades_to_retrieval_only_when_llm_dead():
    pipeline = _pipeline(_FakeRetriever([_chunk(0.95)]), engines=[_DeadEngine(level=L1)])
    result = pipeline.run("问点什么", intent="知识问答")
    assert result.generator == "none"
    assert "（未生成）" in result.answer
    assert any("llm_unavailable" in r for r in result.degraded_reasons)


def test_rag_pipeline_skips_retrieval_for_chat_template():
    pipeline = _pipeline(_FakeRetriever([_chunk(0.95)]))
    result = pipeline.run("你好", intent="闲聊")
    assert result.plan["template"] == CHAT
    assert result.retrieval.get("skipped") is True
    assert result.sources == []


def test_rag_pipeline_keeps_multi_turn_context_in_prompt():
    captured = {}

    class SpyPipeline(RagPipeline):
        def _build_prompt(self, state, chunks):
            captured["context"] = state.get("context")
            return super()._build_prompt(state, chunks)

    pipeline = SpyPipeline(
        gateway=LlmGateway(engines=[TemplateEngine()]),
        retriever=_FakeRetriever([_chunk(0.9)]),
        cache=SemanticCache(redis_client=None, encoder=_FakeEncoder()),
    )
    pipeline.run("刚才说的再细一点", intent="知识问答",
                 context=[{"role": "user", "content": "第一轮"}, {"role": "assistant", "content": "答"}])
    assert captured["context"] and captured["context"][-1]["content"] == "答"


# ───────────────────────── HTTP 端点 ─────────────────────────

client = TestClient(app)


def test_http_brain_plan_endpoint():
    response = client.post("/api/nlp/brain/plan", json={"question": "总结《架构》", "intent": "汇总报告"})
    assert response.status_code == 200
    body = response.json()
    assert body["template"] == SUMMARIZE and body["slots"]["topic"] == "架构"


def test_http_brain_ask_endpoint_returns_envelope_fields():
    response = client.post("/api/nlp/brain/ask", json={"question": "大脑期做了什么？", "tenant_id": "default"})
    assert response.status_code == 200
    body = response.json()
    for key in ("answer", "plan", "sources", "gap", "chain", "generator", "degraded", "decision_id"):
        assert key in body


def test_http_brain_ask_rejects_blank_question():
    response = client.post("/api/nlp/brain/ask", json={"question": "   "})
    assert response.status_code == 400
    assert response.json()["code"] == "AGENT_BAD_REQUEST"


def test_http_brain_health_reports_degradation():
    body = client.get("/api/nlp/brain/health").json()
    assert "llm" in body and "semantic_cache" in body
    assert body["planner"]["engine"] == "rule"
    assert body["semantic_cache"]["backend"] in ("redis", "memory")


def test_http_brain_cache_stats_endpoint():
    body = client.get("/api/nlp/brain/cache/stats").json()
    assert "stats" in body and "hit_rate" in body["stats"]


def test_http_brain_unknown_method_is_405():
    response = client.get("/api/nlp/brain/ask")
    assert response.status_code == 405


# ───────────────────────── 测试替身 ─────────────────────────

def _chunk(score: float) -> dict:
    return {"chunk_id": "c1", "doc_id": "d1", "title": "设计文档", "heading": "Phase 4",
            "content": "大脑期包含会话状态机、LLM 网关与 RAG 链路。", "score": score,
            "rerank_score": score, "vector_backend": "qdrant"}


class _FakeRetriever:
    def __init__(self, chunks):
        self._chunks = chunks

    def retrieve(self, query, tenant_id="default", top_k=5):
        return RetrievalOutcome(chunks=self._chunks, backend="fake")


class _DeadRetriever:
    def retrieve(self, query, tenant_id="default", top_k=5):
        raise RetrievalUnavailable("connection refused")


class _DeadEngine(TemplateEngine):
    name = "dead-engine"

    def __init__(self, level=L1):
        self.level = level

    def available(self):
        return True

    def generate(self, request, timeout):
        raise RuntimeError("engine exploded")


class _FakeEncoder:
    """确定性编码器：相同文本 → 同向量；相近文本（相差一个标点）→ 0.97 相似。"""

    def encode_one(self, text: str) -> list[float]:
        base = [1.0, 0.0, 0.0]
        if text.endswith("?") or text.endswith("？"):
            return [0.97, 0.24, 0.0]      # 与 [1,0,0] 的余弦 ≈ 0.971
        if text == "完全不同的问题":
            return [0.0, 1.0, 0.0]
        return base
