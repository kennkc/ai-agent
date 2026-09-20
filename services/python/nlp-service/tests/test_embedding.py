"""Phase 3 嵌入与分块测试（R3-02 / R3-03）

嵌入后端按可用性分支断言：BGE-M3 缺失时回落 hash-ngram-768 并标记 degraded
（诚实上报，不伪造语义质量）。
"""
from __future__ import annotations

import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.chunking import chunk_text
from app.embedding import BGE_M3_DIM, EMBEDDING_SERVICE, HASH_DIM, EmbeddingService, HashNgramEngine


# ─────────── R3-03 嵌入 ───────────
def test_status_reports_backend_and_dim():
    status = EMBEDDING_SERVICE.status()
    assert status["available"] is True
    assert status["dim"] in (HASH_DIM, BGE_M3_DIM)
    assert {c["backend"] for c in status["candidates"]} >= {"bge-m3", "hash-ngram-768"}


def test_status_is_truthful_when_only_fallback_available():
    """无 sentence-transformers 时必须标记降级，并给出降级后端维度"""
    status = EMBEDDING_SERVICE.status()
    if status["backend"] == "hash-ngram-768":
        assert status["degraded"] is True
        assert status["dim"] == HASH_DIM


def test_vectors_are_normalized_and_dimension_consistent():
    result = EMBEDDING_SERVICE.encode(["知识库语义检索第一段", "另一段完全不同的文本"])
    assert len(result.vectors) == 2
    assert result.dim == len(result.vectors[0]) == len(result.vectors[1])
    for vector in result.vectors:
        norm = math.sqrt(sum(value * value for value in vector))
        assert abs(norm - 1.0) < 1e-6


def test_similar_text_scores_higher_than_unrelated():
    """降级后端也需保证字面相关性单调：近义表述余弦相似度高于无关文本"""
    query = EMBEDDING_SERVICE.encode_one("躯体期知识库语义检索性能指标")
    near = EMBEDDING_SERVICE.encode_one("知识库的语义检索性能指标与延迟要求")
    far = EMBEDDING_SERVICE.encode_one("今天中午吃什么比较好呢")
    cosine_near = sum(a * b for a, b in zip(query, near, strict=False))
    cosine_far = sum(a * b for a, b in zip(query, far, strict=False))
    assert cosine_near > cosine_far


def test_encode_rejects_empty_and_oversized_batch():
    service = EmbeddingService()
    for bad in ([], ["x"] * 257):
        try:
            service.encode(bad)
            raise AssertionError("invalid batch accepted")
        except ValueError:
            pass


def test_fallback_only_chain_is_still_usable():
    """仅保留降级后端时，服务仍可用且维度正确（离线可演示）"""
    service = EmbeddingService(engines=[HashNgramEngine()])
    status = service.status()
    assert status["available"] is True
    assert status["backend"] == "hash-ngram-768"
    assert status["dim"] == HASH_DIM
    assert service.encode_one("离线演示文本")


def test_hash_backend_is_deterministic_across_instances():
    left = HashNgramEngine().encode(["确定性校验文本"])[0]
    right = HashNgramEngine().encode(["确定性校验文本"])[0]
    assert left == right


# ─────────── R3-02 分块 ───────────
DOC = """# 躯体期设计

躯体层负责知识存储与检索。三级存储分别是 Redis、Qdrant 与 PostgreSQL。

## 分块策略

按标题与段落边界切分，最大 800 字，重叠 50 字。块元数据包含 doc_id、chunk_index、heading。

## 检索管线

查询先向量化，再到 Qdrant 检索 TOP-50，随后重排取 TOP-5，最后附带来源与评分返回。
"""


def test_chunk_covers_content_without_loss():
    chunks = chunk_text(DOC)
    assert chunks
    joined = "".join(chunk.content for chunk in chunks)
    # 重叠与标题剥离会导致长度差，但正文关键词必须全部保留
    for keyword in ["三级存储", "分块策略", "检索管线", "TOP-50", "重叠 50 字"]:
        assert keyword in joined


def test_chunk_size_within_bounds():
    chunks = chunk_text(DOC)
    for chunk in chunks:
        assert 0 < len(chunk.content) <= 900


def test_chunk_carries_heading_metadata():
    chunks = chunk_text(DOC)
    headings = {chunk.heading for chunk in chunks}
    assert "分块策略" in headings
    assert "检索管线" in headings


def test_long_paragraph_is_split_by_sentence_boundary():
    paragraph = "这是第一句。" * 200  # 1200 字，超 max_chars
    chunks = chunk_text(paragraph, max_chars=300, min_chars=50, overlap=0)
    assert len(chunks) >= 4
    for chunk in chunks:
        assert len(chunk.content) <= 300
        assert chunk.content.endswith("。")


def test_oversized_content_is_not_dropped():
    paragraph = "无标点长文本" * 300
    chunks = chunk_text(paragraph, max_chars=200, min_chars=50, overlap=0)
    total = sum(len(chunk.content) for chunk in chunks)
    assert total >= 300 * 6


def test_chunk_rejects_invalid_input():
    for kwargs in ({"max_chars": 0}, {"min_chars": 900, "max_chars": 800}, {"overlap": 900, "max_chars": 800}):
        try:
            chunk_text(DOC, **kwargs)
            raise AssertionError("invalid parameters accepted")
        except ValueError:
            pass
    try:
        chunk_text("   ")
        raise AssertionError("blank content accepted")
    except ValueError:
        pass
