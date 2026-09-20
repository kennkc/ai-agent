"""Phase 4 大脑层测试（R4-03 LLM Gateway / R4-04 语义缓存 / R4-05 规划器 /
R4-06 RAG 链路 / R4-07 缺口检测 / R4-08 来源标注 + HTTP 端点）。

约定：每个「降级分支」都有对应用例 —— 降级必须可见，且不得伪造成功。
"""
from __future__ import annotations

import time

import pytest
from app.brain import pg as brain_pg
from app.brain.audit import AuditLog, Decision
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
from app.brain.memory_graph import (
    COMPRESSION_TARGET,
    EntityExtractor,
    MemoryGraph,
    normalize,
)
from app.brain.pipeline import RagPipeline
from app.brain.planner import CHAT, QA, RETRIEVE, SUMMARIZE, SimplePlanner
from app.brain.retrieval import RetrievalOutcome, RetrievalUnavailable
from app.brain.semantic_cache import CacheStats, SemanticCache, cosine_similarity
from app.main import app
from fastapi.testclient import TestClient

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
    # X4 思考链 6 主步；gap 是检索判定，标为子步（回放可见，但不占主步位）
    assert [step["step"] for step in result.chain] == [
        "intent", "plan", "retrieve", "gap", "generate", "verify", "annotate"
    ]
    assert [step["step"] for step in result.chain if step.get("kind", "step") == "step"] == [
        "intent", "plan", "retrieve", "generate", "verify", "annotate"
    ]
    assert result.chain[3]["kind"] == "substep" and result.chain[3]["parent"] == "retrieve"
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



# ───────────────────────── R4-04 语义缓存：真 Redis 后端 ─────────────────────────

class _FakeRedis:
    """最小 Redis Hash 替身。

    存在意义：**证明缓存真的写进了 Redis**，而不是"连上了却把数据留在进程内"。
    只靠 `redis_client=None`（内存后端）的用例抓不到 R4-04 的空壳问题。
    """

    def __init__(self) -> None:
        self.hashes: dict[str, dict[str, str]] = {}
        self.expires: dict[str, int] = {}

    def ping(self):
        return True

    def hset(self, key, field, value):
        self.hashes.setdefault(key, {})[field] = value
        return 1

    def hgetall(self, key):
        return dict(self.hashes.get(key, {}))

    def hdel(self, key, *fields):
        bucket = self.hashes.get(key, {})
        removed = 0
        for field in fields:
            if bucket.pop(field, None) is not None:
                removed += 1
        return removed

    def hlen(self, key):
        return len(self.hashes.get(key, {}))

    def expire(self, key, ttl):
        self.expires[key] = ttl
        return True

    def delete(self, *keys):
        removed = 0
        for key in keys:
            if self.hashes.pop(key, None) is not None:
                removed += 1
        return removed

    def scan(self, cursor=0, match=None, count=100):
        return 0, list(self.hashes.keys())


def test_semantic_cache_reports_redis_backend_when_connected():
    cache = SemanticCache(redis_client=_FakeRedis(), encoder=_FakeEncoder())
    assert cache.backend == "redis"
    assert cache.degraded is False


def test_semantic_cache_writes_through_to_redis_not_local_memory():
    """核心回归：数据必须落在 Redis，进程内字典保持为空。"""
    fake = _FakeRedis()
    cache = SemanticCache(redis_client=fake, encoder=_FakeEncoder())
    cache.store("写入redis的问题", {"answer": "A"}, tenant_id="t1")
    assert cache._local.get("t1") is None                 # 没有偷偷留在内存
    assert fake.hlen("brain:cache:t1") == 1               # 真的写进了 Redis
    hit = cache.lookup("写入redis的问题", tenant_id="t1")
    assert hit is not None and hit["answer"] == "A"
    assert hit["cache_backend"] == "redis"


def test_semantic_cache_is_shared_across_instances_via_redis():
    """跨实例共享 —— 内存后端做不到，这条能区分"真 Redis"与"假 Redis"。"""
    fake = _FakeRedis()
    writer = SemanticCache(redis_client=fake, encoder=_FakeEncoder())
    writer.store("跨实例共享的问题", {"answer": "shared"}, tenant_id="t1")
    reader = SemanticCache(redis_client=fake, encoder=_FakeEncoder())
    assert reader.lookup("跨实例共享的问题", tenant_id="t1")["answer"] == "shared"


def test_semantic_cache_redis_entries_expire():
    fake = _FakeRedis()
    cache = SemanticCache(redis_client=fake, encoder=_FakeEncoder(), ttl_seconds=0)
    cache.store("马上过期的问题", {"answer": "x"}, tenant_id="t1")
    assert cache.lookup("马上过期的问题", tenant_id="t1") is None
    assert cache.stats.misses >= 1


def test_semantic_cache_redis_capacity_prune():
    fake = _FakeRedis()
    cache = SemanticCache(redis_client=fake, encoder=_FakeEncoder(), max_entries=2)
    for index in range(5):
        cache.store(f"问题{index}", {"answer": index}, tenant_id="t1")
    assert fake.hlen("brain:cache:t1") == 2
    assert cache.stats.evictions >= 3


def test_semantic_cache_tenant_isolation_on_redis():
    fake = _FakeRedis()
    cache = SemanticCache(redis_client=fake, encoder=_FakeEncoder())
    cache.store("同一个问题", {"answer": "租户A"}, tenant_id="a")
    assert cache.lookup("同一个问题", tenant_id="b") is None


def test_semantic_cache_real_redis_integration():
    """真 Redis 集成（不可用时 skip，不伪造通过）。"""
    try:
        import redis  # type: ignore

        client = redis.Redis(host="127.0.0.1", port=6379, socket_connect_timeout=1.0, socket_timeout=1.0)
        client.ping()
    except Exception as exc:  # noqa: BLE001
        pytest.skip(f"redis unavailable: {exc}")
    tenant = "test-cache-integration"
    cache = SemanticCache(redis_client=client, encoder=_FakeEncoder())
    try:
        cache.store("真Redis集成问题", {"answer": "ok"}, tenant_id=tenant)
        assert cache.backend == "redis"
        other = SemanticCache(redis_client=client, encoder=_FakeEncoder())
        assert other.lookup("真Redis集成问题", tenant_id=tenant)["answer"] == "ok"
    finally:
        cache.clear(tenant)


# ───────────────────────── 决策链 6 步 + 自校验 ─────────────────────────

def test_decision_chain_covers_six_steps():
    pipeline = _pipeline(_FakeRetriever([_chunk(0.95)]))
    result = pipeline.run("Phase 4 有哪些需求？", intent="知识问答")
    # X4 思考链 6 主步；gap 是检索判定，标为子步（回放可见，但不占主步位）
    assert [step["step"] for step in result.chain] == [
        "intent", "plan", "retrieve", "gap", "generate", "verify", "annotate"
    ]
    assert [step["step"] for step in result.chain if step.get("kind", "step") == "step"] == [
        "intent", "plan", "retrieve", "generate", "verify", "annotate"
    ]
    assert result.chain[3]["kind"] == "substep" and result.chain[3]["parent"] == "retrieve"
    for step in result.chain:
        for key in ("model", "latency_ms", "confidence", "io"):
            assert key in step


def test_verify_step_flags_low_groundedness():
    """自校验：回答与资料无关时必须标 low_groundedness（只标注，不篡改答案）。"""

    class UnrelatedEngine(TemplateEngine):
        name = "unrelated"

        def generate(self, request, timeout):
            return "完全无关的自行发挥内容，与检索资料毫无重叠。"

    pipeline = _pipeline(_FakeRetriever([_chunk(0.95)]), engines=[UnrelatedEngine()])
    result = pipeline.run("资料里说了什么？", intent="知识问答")
    assert "low_groundedness" in result.degraded_reasons
    verify = next(step for step in result.chain if step["step"] == "verify")
    checks = verify["io"]["checks"]
    assert checks["groundedness"] < checks["floor"]


def test_verify_step_passes_when_answer_grounded():
    pipeline = _pipeline(_FakeRetriever([_chunk(0.95)]))
    result = pipeline.run("大脑期包含什么？", intent="知识问答")
    verify = next(step for step in result.chain if step["step"] == "verify")
    assert verify["step"] == "verify"
    assert result.chain[-1]["step"] == "annotate", "末步必须是来源标注（X4 第 6 步）"
    assert "low_groundedness" not in result.degraded_reasons


# ───────────────────────── IN3 AuditLog（X4/D5）─────────────────────────

def _offline_audit(monkeypatch) -> AuditLog:
    """关掉 PG，得到确定性的进程内审计（不影响断言"回放语义"）。"""
    monkeypatch.setattr(brain_pg, "PG_ENABLED", False)
    monkeypatch.setattr(brain_pg, "_state", {"initialized": True, "degraded": True, "reason": "test"})
    return AuditLog()


def test_audit_log_replays_recorded_decision(monkeypatch):
    log = _offline_audit(monkeypatch)
    decision = Decision(decision_id="d-1", question="问什么", tenant_id="t1",
                        chain=[{"step": "plan", "confidence": 0.8}],
                        sources=[{"doc_id": "doc-1"}], generator="template")
    log.record(decision)
    replayed = log.replay("d-1", "t1")
    assert replayed is not None and replayed["question"] == "问什么"
    assert replayed["confidence"] > 0
    assert replayed["compliance"]["checks"] == 4
    assert replayed["attribution"]["doc_ids"] == ["doc-1"]


def test_audit_log_returns_none_for_unknown_or_foreign_tenant(monkeypatch):
    """查不到就是 None（调用方回 404）；跨租户与"不存在"同等处理。"""
    log = _offline_audit(monkeypatch)
    log.record(Decision(decision_id="d-2", question="q", tenant_id="t1"))
    assert log.replay("d-2", "t2") is None
    assert log.replay("not-exist", "t1") is None
    assert log.replay("", "t1") is None


def test_http_brain_decision_replay_returns_404_when_missing():
    """关键回归：不许用「200 + 空链」冒充成功。"""
    response = client.get("/api/nlp/brain/decision/definitely-not-a-decision",
                          headers={"X-Tenant-Id": "test-no-such-decision"})
    assert response.status_code == 404
    assert response.json()["code"] == "AGENT_NOT_FOUND"


def test_http_brain_decision_replay_serves_real_record():
    asked = client.post("/api/nlp/brain/ask",
                        json={"question": "回放验证问题？", "tenant_id": "test-replay"})
    assert asked.status_code == 200
    decision_id = asked.json()["decision_id"]
    replay = client.get(f"/api/nlp/brain/decision/{decision_id}",
                        headers={"X-Tenant-Id": "test-replay"})
    assert replay.status_code == 200
    body = replay.json()
    assert body["decision_id"] == decision_id
    main_steps = [s["step"] for s in body["chain"] if s.get("kind", "step") == "step"]
    sub_steps = [s["step"] for s in body["chain"] if s.get("kind") == "substep"]
    assert main_steps[0] == "intent" and main_steps[-1] == "annotate", f"链首尾不对：{main_steps}"
    # 本用例未注入检索替身 → 走缺口拒答分支：generate 由 insufficient 子步取代。
    # 两条路径都必须可解释：要么真的生成，要么明确说明为什么没生成。
    assert ("generate" in main_steps) or ("insufficient" in sub_steps), \
        f"既没有生成步也没有缺口说明，链条不可解释：{main_steps}/{sub_steps}"
    assert "compliance" in body and "attribution" in body and "confidence" in body


def test_http_brain_decisions_lists_recent():
    body = client.get("/api/nlp/brain/decisions?limit=5",
                      headers={"X-Tenant-Id": "test-replay"}).json()
    assert "items" in body and isinstance(body["items"], list)
    assert "storage" in body


# ───────────────────────── IN-02 记忆图谱 ─────────────────────────

def test_memory_graph_extracts_entities_and_relations():
    extractor = EntityExtractor()
    extraction = extractor.extract("《Agent-Lifeform》项目依赖 BodyService 服务，项目文档引用设计文档。")
    names = [entity.name for entity in extraction.entities]
    assert any("Agent-Lifeform" in name for name in names)
    assert extraction.extractor == "rule"          # 如实标注：规则抽取，非模型
    assert extraction.relations
    assert extraction.relations[0].rel in {"依赖", "引用", "关联"}


def test_memory_graph_extracts_nothing_from_noise():
    """抽不到就返回空图，不拿停用词凑数。"""
    extraction = EntityExtractor().extract("嗯，这个那个")
    assert extraction.entities == [] and extraction.relations == []


def test_memory_entity_name_normalization_merges_variants():
    assert normalize("Agent-Lifeform") == normalize("agent-lifeform")
    assert normalize("《Agent-Lifeform》") == normalize("Agent-Lifeform")


def test_memory_graph_subgraph_and_compression(monkeypatch):
    monkeypatch.setattr(brain_pg, "PG_ENABLED", False)
    monkeypatch.setattr(brain_pg, "_state", {"initialized": True, "degraded": True, "reason": "test"})
    graph = MemoryGraph()
    tenant = "test-memory"
    # 模拟一段真实的多轮会话历史（压缩率口径就是"用子图替代长会话原文"）
    turn = ("用户问：《Agent-Lifeform》项目依赖哪些服务？助手答：项目依赖 BodyService 服务，"
            "BodyService 服务引用 Qdrant 数据库，Qdrant 数据库属于存储层，"
            "会话状态机负责串联上下文，LLM 网关负责路由与降级标注。")
    text = " ".join(turn for _ in range(6))
    graph.ingest(text, tenant)
    loaded = graph.subgraph("Agent-Lifeform", tenant)
    assert loaded.entities and loaded.load_ms < 100          # 子图加载 < 100ms
    compressed = graph.compress(text, tenant)
    assert compressed["applied"] is True
    assert compressed["compression"] >= COMPRESSION_TARGET   # 压缩率 ≥ 60%
    assert compressed["meets_target"] is True


def test_memory_graph_reports_absent_entity_instead_of_empty_success(monkeypatch):
    monkeypatch.setattr(brain_pg, "PG_ENABLED", False)
    monkeypatch.setattr(brain_pg, "_state", {"initialized": True, "degraded": True, "reason": "test"})
    graph = MemoryGraph()
    loaded = graph.subgraph("从未出现过的实体", "test-memory-none")
    assert loaded.entities == {}
    assert "不在图中" in loaded.reason


def test_memory_graph_compression_reports_failure_when_no_entity(monkeypatch):
    monkeypatch.setattr(brain_pg, "PG_ENABLED", False)
    monkeypatch.setattr(brain_pg, "_state", {"initialized": True, "degraded": True, "reason": "test"})
    result = MemoryGraph().compress("嗯，这个那个", "test-memory-none")
    assert result["applied"] is False
    assert result["compression"] == 0.0
    assert result["meets_target"] is False

# ───────────────────────── 测试替身 ─────────────────────────

def _chunk(score: float) -> dict:
    return {"chunk_id": "c1", "doc_id": "d1", "title": "设计文档", "heading": "Phase 4",
            "content": "大脑期包含会话状态机、LLM 网关与 RAG 链路。", "score": score,
            "rerank_score": score, "vector_backend": "qdrant"}


class _FakeRetriever:
    def __init__(self, chunks):
        self._chunks = chunks
        self.last_timeout = None

    def retrieve(self, query, tenant_id="default", top_k=5, timeout=None):
        # 记录管线传下来的超时 —— 用于断言「检索被收进剩余总预算内」
        self.last_timeout = timeout
        return RetrievalOutcome(chunks=self._chunks, backend="fake")


class _DeadRetriever:
    def retrieve(self, query, tenant_id="default", top_k=5, timeout=None):
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
