"""Phase 3 重排测试（R3-06 · Should）

重排后端按可用性分支断言：bge-reranker-v2-m3 缺失时回落 lexical-coverage 并标记 degraded。
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.reranker import RERANKER_SERVICE, LexicalRerankerEngine, RerankCandidate, RerankerService

QUERY = "躯体期语义检索的延迟指标是多少"
CANDIDATES = [
    RerankCandidate(id="c1", text="今天食堂的菜谱包括红烧肉和青菜，价格实惠。", score=0.91),
    RerankCandidate(id="c2", text="躯体期要求语义检索 P99 低于 500ms，召回 TOP5 片段。", score=0.72),
    RerankCandidate(id="c3", text="语义检索的延迟指标与性能验收口径在测试设计文档中给出。", score=0.68),
]


def test_status_reports_backend():
    status = RERANKER_SERVICE.status()
    assert status["available"] is True
    assert status["backend"] in ("bge-reranker-v2-m3", "lexical-coverage")
    assert {c["backend"] for c in status["candidates"]} >= {"bge-reranker-v2-m3", "lexical-coverage"}


def test_degraded_flag_matches_backend():
    status = RERANKER_SERVICE.status()
    assert status["degraded"] is (status["backend"] != "bge-reranker-v2-m3")


def test_rerank_promotes_relevant_chunk_over_high_recall_noise():
    """核心断言：高向量分但无关的片段应被降权，相关片段进入 TOP1"""
    results = RERANKER_SERVICE.rerank(QUERY, CANDIDATES, top_k=3)
    assert results
    assert results[0].id in ("c2", "c3")
    assert results[0].rank_after == 0
    assert results[0].rank_before > 0


def test_top_k_truncation_and_rank_metadata():
    results = RERANKER_SERVICE.rerank(QUERY, CANDIDATES, top_k=2)
    assert len(results) == 2
    assert [r.rank_after for r in results] == [0, 1]


def test_empty_candidates_returns_empty():
    assert RERANKER_SERVICE.rerank(QUERY, [], top_k=5) == []


def test_rerank_rejects_blank_query():
    try:
        RERANKER_SERVICE.rerank("   ", CANDIDATES)
        raise AssertionError("blank query accepted")
    except ValueError:
        pass


def test_lexical_scores_are_deterministic():
    engine = LexicalRerankerEngine()
    left = engine.scores(QUERY, [c.text for c in CANDIDATES])
    right = engine.scores(QUERY, [c.text for c in CANDIDATES])
    assert left == right
    assert max(left) == left[1] or max(left) == left[2]


def test_service_with_only_fallback_engine_is_usable():
    service = RerankerService(engines=[LexicalRerankerEngine()])
    assert service.status()["backend"] == "lexical-coverage"
    assert service.status()["degraded"] is True
    assert service.rerank(QUERY, CANDIDATES, top_k=1)
