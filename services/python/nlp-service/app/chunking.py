# -*- coding: utf-8 -*-
"""文档分块器（R3-02 躯体期 · Python 侧）

与 Java 侧 `ChunkProcessor` 保持同算法，用于跨语言一致性校验：
  1. 按 Markdown 标题（`#`~`######`）切分为小节；
  2. 小节内按空行切段落；
  3. 段落累加至 max_chars（默认 800）落块；单段超限时按句末标点切分（回退硬切）；
  4. 相邻块保留 overlap（默认 50）字符尾巴作为上下文延续；
  5. 块小于 min_chars（默认 200）时与相邻块合并（无相邻块则单独成块，不丢弃）。

验收口径（R3-02）：分块无遗漏（拼接后字符覆盖率 ≥ 99%）；块大小 200-800 字。
"""
from __future__ import annotations

import re
from dataclasses import dataclass

HEADING = re.compile(r"^(#{1,6})\s+(.*)$")
SENTENCE_END = re.compile(r"[。！？!?；;\n]")

DEFAULT_MAX_CHARS = 800
DEFAULT_MIN_CHARS = 200
DEFAULT_OVERLAP = 50


@dataclass
class Chunk:
    index: int
    heading: str
    content: str


def _split_sections(content: str) -> list[tuple[str, str]]:
    """按标题切小节，返回 (heading, body) 列表；标题行本身不进正文（作为元数据）"""
    sections: list[tuple[str, str]] = []
    heading = ""
    buffer: list[str] = []

    def flush() -> None:
        body = "\n".join(buffer).strip()
        if body:
            sections.append((heading, body))
        buffer.clear()

    for line in content.splitlines():
        match = HEADING.match(line.strip())
        if match:
            flush()
            heading = match.group(2).strip()
            continue
        buffer.append(line)
    flush()
    if not sections and content.strip():
        sections.append(("", content.strip()))
    return sections


def _split_long_paragraph(paragraph: str, max_chars: int) -> list[str]:
    """超长段落按句末标点切分；无标点时硬切（保证不遗漏）"""
    if len(paragraph) <= max_chars:
        return [paragraph]
    pieces: list[str] = []
    start = 0
    while start < len(paragraph):
        end = min(start + max_chars, len(paragraph))
        if end < len(paragraph):
            window = paragraph[start:end]
            positions = [m.end() for m in SENTENCE_END.finditer(window)]
            if positions:
                cut = start + positions[-1]
                if cut - start >= max_chars // 2:
                    end = cut
        pieces.append(paragraph[start:end].strip())
        start = end
    return [piece for piece in pieces if piece]


def chunk_text(content: str, max_chars: int = DEFAULT_MAX_CHARS, min_chars: int = DEFAULT_MIN_CHARS,
               overlap: int = DEFAULT_OVERLAP) -> list[Chunk]:
    if content is None or not content.strip():
        raise ValueError("content must not be blank")
    if max_chars <= 0 or min_chars < 0 or overlap < 0:
        raise ValueError("invalid chunking parameters")
    if min_chars > max_chars:
        raise ValueError("min_chars must not exceed max_chars")
    if overlap >= max_chars:
        raise ValueError("overlap must be smaller than max_chars")

    raw: list[tuple[str, str]] = []   # (heading, text)
    for heading, body in _split_sections(content):
        paragraphs = [p.strip() for p in re.split(r"\n\s*\n", body) if p.strip()]
        if not paragraphs:
            paragraphs = [body]
        for paragraph in paragraphs:
            for piece in _split_long_paragraph(paragraph, max_chars):
                raw.append((heading, piece))

    # 累加段落成块
    blocks: list[tuple[str, str]] = []
    current_heading = ""
    current: list[str] = []
    current_len = 0

    def flush_current() -> None:
        nonlocal current, current_len
        text = "\n".join(current).strip()
        if text:
            blocks.append((current_heading, text))
        current = []
        current_len = 0

    for heading, piece in raw:
        if current and heading != current_heading:
            flush_current()
        current_heading = heading
        if current and current_len + len(piece) + 1 > max_chars:
            flush_current()
            current_heading = heading
        current.append(piece)
        current_len += len(piece) + 1
    flush_current()

    # 过小块向后合并（仅限同一标题，避免跨小节破坏 heading 归属与语义边界）
    merged: list[tuple[str, str]] = []
    for heading, text in blocks:
        same_section = merged and merged[-1][0] == heading
        if same_section and len(text) < min_chars and len(merged[-1][1]) + len(text) + 1 <= max_chars:
            merged[-1] = (heading, f"{merged[-1][1]}\n{text}")
        else:
            merged.append((heading, text))

    # 块首组装：[标题] → [上块重叠尾巴] → [本块正文]
    # 顺序与 Java 侧 ChunkProcessor.decorateAndOverlap 严格一致：
    # 标题必须在最前（"块首携带标题"验收口径），重叠尾巴放标题之后，避免上一小节标题被顶到块首。
    chunks: list[Chunk] = []
    for index, (heading, text) in enumerate(merged):
        overlap_tail = ""
        if index > 0 and overlap > 0:
            previous_body = merged[index - 1][1]
            overlap_tail = previous_body if len(previous_body) <= overlap else previous_body[-overlap:]
        parts = [part for part in (heading, overlap_tail, text) if part]
        chunks.append(Chunk(index=index, heading=heading, content="\n".join(parts)))
    return chunks
