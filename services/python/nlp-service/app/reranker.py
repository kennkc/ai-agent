# DEBT-011: 重排默认走词法打分（非 bge-reranker-v2-m3 交叉编码器）— 触发点: 安装 sentence-transformers + reranker 权重后切换
"""结果重排（R3-06 躯体期 · Should）

引擎链：bge-reranker-v2-m3（CrossEncoder，设计选型）→ lexical（确定性本地后端）
口径：向量召回 TOP-50 → 重排 → TOP-5 返回。降级后端为**词法相关性**，
不等价于语义精排，由 `status()["degraded"]` 如实暴露（DEBT-011）。
"""
from __future__ import annotations

import logging
import os
import re
from dataclasses import dataclass
from importlib.util import find_spec

from app.embedding import _features

logger = logging.getLogger("nlp.rerank")

_TOKEN = re.compile(r"[a-zA-Z0-9_]+")


@dataclass
class RerankCandidate:
    id: str
    text: str
    score: float = 0.0


@dataclass
class RerankResult:
    id: str
    text: str
    score: float          # 原始召回分
    rerank_score: float   # 重排分
    rank_before: int
    rank_after: int


class RerankerEngine:
    name = "unavailable"

    def available(self) -> bool:
        return False

    def scores(self, query: str, texts: list[str]) -> list[float]:  # pragma: no cover - 抽象
        raise NotImplementedError


# 与 EMBEDDING_MODEL_PATH 同约定：指向本地 bge-reranker 权重目录
#   RERANKER_MODEL_PATH=E:/ai_workspace/project_space/模型权重/bge-reranker-v2-m3
_DEFAULT_RERANK_MODEL = os.getenv("RERANKER_MODEL_PATH", "BAAI/bge-reranker-v2-m3")


class BgeRerankerEngine(RerankerEngine):
    name = "bge-reranker-v2-m3"

    def __init__(self, model_name: str = _DEFAULT_RERANK_MODEL) -> None:
        self.model_name = model_name
        self._model = None

    def available(self) -> bool:
        return find_spec("sentence_transformers") is not None

    def _ensure_model(self):
        if self._model is None:
            from sentence_transformers import CrossEncoder  # type: ignore

            logger.info("loading reranker model %s", self.model_name)
            self._model = CrossEncoder(self.model_name)
        return self._model

    def scores(self, query: str, texts: list[str]) -> list[float]:
        model = self._ensure_model()
        pairs = [(query, text) for text in texts]
        return [float(value) for value in model.predict(pairs)]


class LexicalRerankerEngine(RerankerEngine):
    """词法精排：查询特征覆盖率 + 词元命中 + 长度惩罚。

    与 hash 嵌入同源（共享 `_features`），故在降级链路上保持单调一致：
    优先保留**覆盖查询特征更多、且长度更贴近查询**的片段。
    """

    name = "lexical-coverage"

    def available(self) -> bool:
        return True

    def scores(self, query: str, texts: list[str]) -> list[float]:
        query_features = set(_features(query))
        query_tokens = set(_TOKEN.findall(query.lower()))
        results: list[float] = []
        for text in texts:
            lowered = text.lower()
            features = set(_features(text))
            coverage = len(query_features & features) / len(query_features) if query_features else 0.0
            token_hits = sum(1 for token in query_tokens if token in lowered)
            token_ratio = token_hits / len(query_tokens) if query_tokens else 0.0
            # 长度惩罚：过短/过长都降权，理想区间 120-640 字
            length = max(len(text), 1)
            if 120 <= length <= 640:
                length_factor = 1.0
            elif length < 120:
                length_factor = 0.6 + 0.4 * (length / 120)
            else:
                length_factor = max(0.5, 640 / length)
            results.append(round((0.7 * coverage + 0.3 * token_ratio) * length_factor, 6))
        return results


class RerankerService:
    def __init__(self, engines: list[RerankerEngine] | None = None) -> None:
        self.engines = engines if engines is not None else [BgeRerankerEngine(), LexicalRerankerEngine()]
        self.active: RerankerEngine | None = next((e for e in self.engines if e.available()), None)

    def status(self) -> dict:
        return {
            "available": self.active is not None,
            "backend": self.active.name if self.active else "unavailable",
            "degraded": self.active is not None and self.active.name != BgeRerankerEngine.name,
            "candidates": [{"backend": e.name, "available": e.available()} for e in self.engines],
        }

    def rerank(self, query: str, candidates: list[RerankCandidate], top_k: int = 5) -> list[RerankResult]:
        if self.active is None:
            raise RuntimeError("no reranker available")
        if not query.strip():
            raise ValueError("query must not be blank")
        if not candidates:
            return []
        texts = [candidate.text for candidate in candidates]
        rerank_scores = self.active.scores(query, texts)
        ordered_before = sorted(range(len(candidates)), key=lambda i: (-candidates[i].score, i))
        rank_before = {index: position for position, index in enumerate(ordered_before)}
        order = sorted(range(len(candidates)), key=lambda i: (-rerank_scores[i], -candidates[i].score, i))
        results: list[RerankResult] = []
        for position, index in enumerate(order[:max(top_k, 0)]):
            candidate = candidates[index]
            results.append(RerankResult(id=candidate.id, text=candidate.text, score=candidate.score,
                                        rerank_score=round(rerank_scores[index], 6),
                                        rank_before=rank_before[index], rank_after=position))
        return results


RERANKER_SERVICE = RerankerService()
