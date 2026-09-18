# -*- coding: utf-8 -*-
"""R3-02 文档分块（Python 侧）验收：与 Java 侧 ChunkProcessor 同算法、同口径。

这些用例同时也是**跨语言一致性**的保护网：Java 侧
`services/java/body-service/src/test/java/com/agent/body/chunk/ChunkProcessorTest.java`
的断言与此处一一对应，两侧同时修改才会通过。
"""
from __future__ import annotations

import pytest

from app.chunking import DEFAULT_MAX_CHARS, chunk_text


def test_chunking_does_not_lose_content():
    content = (
        "# 躯体层设计\n"
        "知识以分块方式入库，语义检索按块召回。\n\n"
        "## 存储分层\n"
        "热层用 Redis 缓存高频问题，温层用 Qdrant 向量库，冷层用 PostgreSQL 保存元数据真相。\n\n"
        "## 检索链路\n"
        "查询先查缓存，未命中则向量化后检索 TOP50，再经重排取 TOP5。\n"
    )
    chunks = chunk_text(content)
    assert chunks
    joined = "".join(chunk.content for chunk in chunks)
    for keyword in ("知识以分块方式入库", "热层用 Redis", "查询先查缓存", "再经重排取 TOP5"):
        assert keyword in joined, f"分块遗漏关键词：{keyword}"


def test_each_chunk_keeps_its_heading_at_the_front():
    content = "# 一级标题\n正文甲。\n\n## 二级标题\n正文乙。\n"
    chunks = chunk_text(content)
    first = [c for c in chunks if c.heading == "一级标题"]
    second = [c for c in chunks if c.heading == "二级标题"]
    assert first and first[0].content.startswith("一级标题")
    assert second and second[0].content.startswith("二级标题"), "标题必须在块首，重叠尾巴不能顶掉标题"


def test_chunk_size_stays_within_budget():
    content = "# 长文档\n" + "".join(
        f"这是第 {i} 段说明文字，用于验证分块大小受控。\n\n" for i in range(60)
    )
    for chunk in chunk_text(content):
        # 预算 = max_chars + 标题 + 重叠尾巴 + 分隔换行
        assert len(chunk.content) <= DEFAULT_MAX_CHARS + 64 + 50 + 2


def test_oversized_paragraph_is_split_by_sentence_boundary():
    content = "# 超长段\n" + "".join(f"第{i}句内容；" for i in range(200))
    chunks = chunk_text(content)
    assert len(chunks) > 1


def test_chunk_index_is_sequential_from_zero():
    chunks = chunk_text("# 标题\n甲段落。\n\n乙段落。\n\n丙段落。")
    assert [c.index for c in chunks] == list(range(len(chunks)))


@pytest.mark.parametrize("content", ["", "   ", None])
def test_blank_content_is_rejected(content):
    with pytest.raises(ValueError):
        chunk_text(content)


@pytest.mark.parametrize(
    "max_chars,min_chars,overlap",
    [(0, 0, 0), (100, 200, 10), (100, 10, 100), (800, 200, -1)],
)
def test_invalid_parameters_are_rejected(max_chars, min_chars, overlap):
    with pytest.raises(ValueError):
        chunk_text("# 标题\n正文。", max_chars=max_chars, min_chars=min_chars, overlap=overlap)
