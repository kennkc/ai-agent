"""R4-07 信息缺口检测（基础规则版）+ R4-08 来源标注。

缺口判定（对齐需求文档 §6 执行流程图）：
    检索 TOP-K 综合得分 ≥ 0.85 → 覆盖充分，直接生成
    否则：若无有效片段 → 「关键缺口」提示补充文档；
          若有片段但不足 → 基于现有知识生成 + 标注「部分信息可能不完整」

**诚实性约定**：缺口检测只做「提示与标注」，**不静默补内容**。
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Any

# ── 阈值口径（务必读）────────────────────────────────────────────────
# 需求文档 §6 的口径是「综合得分 ≥ 0.85 视为覆盖充分」，该值假设
#   · 嵌入为 bge-m3（语义相似，DEBT-010 未闭合，现为字符 n-gram 哈希）
#   · 重排为 bge-reranker-v2-m3（交叉编码，DEBT-011 未闭合，现为词法覆盖率）
# 当前后端下实测打分分布落在 **0.15~0.35**（2026-09-19 实测 5 条命中：0.201~0.275），
# 若直接套用 0.85，几乎每个问题都会被判「信息不足」——**不是知识库没数据，而是尺子不对**。
#
# 处置：阈值改由环境变量控制，默认值按**当前后端实测分布**校准；
# DEBT-010 / DEBT-011 闭合（装真实模型）后，用环境变量调回设计口径即可，无需改代码。
# 校准本身已登记在 `docs/技术债台账.md` §4.14。
COVERAGE_THRESHOLD = float(os.getenv("GAP_COVERAGE_THRESHOLD", "0.35"))
MIN_USABLE_SCORE = float(os.getenv("GAP_MIN_USABLE_SCORE", "0.15"))


@dataclass
class GapVerdict:
    sufficient: bool          # 覆盖是否充分（可直接生成）
    has_gap: bool             # 是否存在关键缺口（需提示补充）
    coverage: float           # 覆盖度（0~1）
    usable_chunks: int
    threshold: float
    advice: str = ""
    notice: str = ""          # 给最终回答的标注文案

    def to_dict(self) -> dict:
        return {
            "sufficient": self.sufficient,
            "has_gap": self.has_gap,
            "coverage": round(self.coverage, 4),
            "usable_chunks": self.usable_chunks,
            "threshold": self.threshold,
            "advice": self.advice,
            "notice": self.notice,
        }


class GapDetector:
    """基于检索打分的覆盖度评估（规则版，后续可换模型评估）。"""

    def __init__(self, threshold: float = COVERAGE_THRESHOLD, min_usable: float = MIN_USABLE_SCORE) -> None:
        self.threshold = threshold
        self.min_usable = min_usable

    def detect(self, chunks: list[dict[str, Any]]) -> GapVerdict:
        scored = [_score_of(c) for c in chunks or []]
        usable = [s for s in scored if s >= self.min_usable]
        coverage = _coverage(scored)
        if not usable:
            return GapVerdict(
                sufficient=False, has_gap=True, coverage=coverage, usable_chunks=0,
                threshold=self.threshold,
                advice="知识库未检索到可用资料，建议补充相关文档后重试",
                notice="⚠️ 信息不足：知识库中未找到可用资料，以下回答不作事实依据",
            )
        if coverage >= self.threshold:
            return GapVerdict(
                sufficient=True, has_gap=False, coverage=coverage, usable_chunks=len(usable),
                threshold=self.threshold, advice="", notice="",
            )
        return GapVerdict(
            sufficient=False, has_gap=False, coverage=coverage, usable_chunks=len(usable),
            threshold=self.threshold,
            advice="检索覆盖度偏低，可补充更精确的关键词或文档",
            notice="⚠️ 部分信息可能不完整：以下回答基于有限资料生成",
        )


# ─────────── R4-08 来源标注 ───────────

@dataclass
class Source:
    chunk_id: str = ""
    doc_id: str = ""
    title: str = ""
    heading: str = ""
    score: float = 0.0
    rerank_score: float = 0.0
    snippet: str = ""
    backend: str = ""      # 检索后端（pg / qdrant / ...）
    channel: str = ""      # 来源渠道（知识库 / 感官采集）
    collected_at: str = ""

    def to_dict(self) -> dict:
        return {
            "chunk_id": self.chunk_id,
            "doc_id": self.doc_id,
            "title": self.title,
            "heading": self.heading,
            "score": round(self.score, 4),
            "rerank_score": round(self.rerank_score, 4),
            "snippet": self.snippet,
            "backend": self.backend,
            "channel": self.channel or "知识库",
            "collected_at": self.collected_at,
        }


class SourceAnnotator:
    """把检索片段整理为带来源的回答引用（渠道 / 置信度 / 知识库来源）。"""

    def __init__(self, snippet_limit: int = 160) -> None:
        self.snippet_limit = snippet_limit

    def annotate(self, chunks: list[dict[str, Any]]) -> list[Source]:
        sources: list[Source] = []
        for chunk in chunks or []:
            content = str(chunk.get("content", "") or "")
            sources.append(Source(
                chunk_id=str(chunk.get("chunk_id", "") or ""),
                doc_id=str(chunk.get("doc_id", "") or ""),
                title=str(chunk.get("title", "") or "未命名文档"),
                heading=str(chunk.get("heading", "") or ""),
                score=float(chunk.get("score", 0) or 0),
                rerank_score=float(chunk.get("rerank_score", 0) or 0),
                snippet=_short(content, self.snippet_limit),
                backend=str(chunk.get("vector_backend", "") or chunk.get("backend", "") or ""),
                channel=str(chunk.get("channel", "") or "知识库"),
                collected_at=str(chunk.get("collected_at", "") or ""),
            ))
        return sources


def _score_of(chunk: dict[str, Any]) -> float:
    """取片段的**可用性打分**。

    取值顺序刻意是 `score`（向量召回相似）→ `rerank_score`（重排分）→ 长度启发式：
    重排分的**量纲随后端而变** —— 词法重排（DEBT-011 未闭合）实测压缩在 0.10~0.12，
    交叉编码器又可能是 0~1 的另一种分布。拿它当绝对阈值判「可用」会让
    **换了重排后端就整体失效**，因此统一以向量召回分为准，重排分只用于排序/展示。
    """
    for key in ("score", "rerank_score"):
        value = chunk.get(key)
        if isinstance(value, (int, float)):
            return max(0.0, min(1.0, float(value)))
    # 无打分时按片段长度给一个保守估计（避免把无分片段当成高分）
    content = str(chunk.get("content", "") or "")
    return 0.5 if len(content) >= 40 else 0.2


def _coverage(scores: list[float]) -> float:
    """覆盖度：以最佳片段为主，累加其余片段的边际贡献（递减）。"""
    if not scores:
        return 0.0
    ordered = sorted(scores, reverse=True)
    total = ordered[0]
    weight = 0.5
    for score in ordered[1:]:
        total += score * weight
        weight *= 0.5
    return max(0.0, min(1.0, total))


def _short(text: str, limit: int) -> str:
    text = " ".join((text or "").split())
    return text if len(text) <= limit else text[:limit] + "…"


GAP_DETECTOR = GapDetector()
SOURCE_ANNOTATOR = SourceAnnotator()
