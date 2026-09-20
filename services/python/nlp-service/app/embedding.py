# DEBT-010: 嵌入默认走字符 n-gram 哈希后端（非 BGE-M3）— 触发点: 安装 sentence-transformers + BGE-M3 权重后切换
"""嵌入服务（R3-03 躯体期）

引擎链：BGE-M3（sentence-transformers，设计选型，1024 维）→ hash-ngram-768（确定性本地后端，768 维）
设计约束（与 OCR 通道一致）：**能力不可用时必须诚实上报**，不允许伪造向量质量——
`status()` 返回实际后端与 `degraded` 标记，降级路径由调用方（body-service）标注为 DEBT-010。

维度约定：Qdrant 集合维度由 `status()["dim"]` 决定（128 维起可配），
切换后端必须重建集合（回归注意：嵌入模型升级需全量重索引 + 评测回归）。
"""
from __future__ import annotations

import hashlib
import logging
import math
import re
import time
from dataclasses import dataclass
from importlib.util import find_spec

logger = logging.getLogger("nlp.embedding")

# 降级后端的向量维度（R3-03 验收基线 768 维）
HASH_DIM = 768
# BGE-M3 输出维度
BGE_M3_DIM = 1024

_CJK = re.compile(r"[\u4e00-\u9fff]")
_TOKEN = re.compile(r"[a-zA-Z0-9_]+")


@dataclass
class EmbeddingResult:
    vectors: list[list[float]]
    dim: int
    backend: str
    degraded: bool
    latency_ms: float
    ms_per_text: float = 0.0


def _features(text: str) -> list[str]:
    """字符 1/2/3-gram + 英文数字词元，与 L0 意图模型的特征口径保持一致"""
    lowered = text.lower().strip()
    grams: list[str] = []
    for size in (1, 2, 3):
        if len(lowered) < size:
            continue
        grams.extend(lowered[i:i + size] for i in range(len(lowered) - size + 1))
    tokens = _TOKEN.findall(lowered)
    grams.extend("w:" + t for t in tokens)
    return grams


class EmbeddingEngine:
    name = "unavailable"
    dim = 0

    def available(self) -> bool:
        return False

    def encode(self, texts: list[str]) -> list[list[float]]:  # pragma: no cover - 抽象
        raise NotImplementedError


class BgeM3Engine(EmbeddingEngine):
    """设计选型：BAAI/bge-m3（中文/多语言优）。权重需联网下载，故懒加载 + 可用性探测。"""

    name = "bge-m3"
    dim = BGE_M3_DIM

    def __init__(self, model_name: str = "BAAI/bge-m3") -> None:
        self.model_name = model_name
        self._model = None

    def available(self) -> bool:
        return find_spec("sentence_transformers") is not None

    def _ensure_model(self):
        if self._model is None:
            from sentence_transformers import SentenceTransformer  # type: ignore

            logger.info("loading embedding model %s", self.model_name)
            self._model = SentenceTransformer(self.model_name)
        return self._model

    def encode(self, texts: list[str]) -> list[list[float]]:
        model = self._ensure_model()
        vectors = model.encode(texts, normalize_embeddings=True, batch_size=16)
        return [[float(value) for value in row] for row in vectors]


class HashNgramEngine(EmbeddingEngine):
    """确定性本地后端：字符 n-gram 哈希 + 亚线性 TF 加权 + L2 归一化。

    无第三方依赖、结果跨进程稳定（blake2b 哈希），因此可离线演示入库/检索链路；
    其相似度本质是**字面特征重叠**，不等价于语义相似度 —— 由 degraded 标记显式暴露。
    """

    name = "hash-ngram-768"
    dim = HASH_DIM

    def available(self) -> bool:
        return True

    def encode(self, texts: list[str]) -> list[list[float]]:
        return [self._encode_one(text) for text in texts]

    def _encode_one(self, text: str) -> list[float]:
        buckets = [0.0] * HASH_DIM
        counts: dict[int, int] = {}
        for feature in _features(text):
            digest = hashlib.blake2b(feature.encode("utf-8"), digest_size=8).digest()
            bucket = int.from_bytes(digest, "big") % HASH_DIM
            counts[bucket] = counts.get(bucket, 0) + 1
        for bucket, count in counts.items():
            buckets[bucket] = 1.0 + math.log(count)
        norm = math.sqrt(sum(value * value for value in buckets))
        if norm == 0.0:
            return buckets
        return [value / norm for value in buckets]


class EmbeddingService:
    """引擎链调度：取第一个可用引擎（BGE-M3 优先，缺失时回落哈希后端并标记 degraded）"""

    def __init__(self, engines: list[EmbeddingEngine] | None = None) -> None:
        self.engines = engines if engines is not None else [BgeM3Engine(), HashNgramEngine()]
        self.active: EmbeddingEngine | None = next((e for e in self.engines if e.available()), None)
        self._calls = 0
        self._texts = 0
        self._latencies: list[float] = []
        self._max_samples = 200

    # ─────────── 状态 ───────────
    def status(self) -> dict:
        return {
            "available": self.active is not None,
            "backend": self.active.name if self.active else "unavailable",
            "dim": self.active.dim if self.active else 0,
            "degraded": self.active is not None and self.active.name != BgeM3Engine.name,
            "candidates": [{"backend": e.name, "dim": e.dim, "available": e.available()} for e in self.engines],
            "stats": self.stats(),
        }

    def stats(self) -> dict:
        samples = sorted(self._latencies)
        p95 = samples[min(len(samples) - 1, int(len(samples) * 0.95))] if samples else 0.0
        return {
            "calls": self._calls,
            "texts": self._texts,
            "latency_p95_ms": round(p95, 3),
            "latency_max_ms": round(samples[-1], 3) if samples else 0.0,
        }

    # ─────────── 编码 ───────────
    def encode(self, texts: list[str]) -> EmbeddingResult:
        if self.active is None:
            raise RuntimeError("no embedding engine available")
        if not texts:
            raise ValueError("texts must not be empty")
        if len(texts) > 256:
            raise ValueError("batch size exceeds 256 texts")
        started = time.perf_counter()
        vectors = self.active.encode(texts)
        elapsed_ms = (time.perf_counter() - started) * 1000
        self._calls += 1
        self._texts += len(texts)
        self._latencies.append(elapsed_ms)
        if len(self._latencies) > self._max_samples:
            self._latencies.pop(0)
        for vector in vectors:
            if len(vector) != self.active.dim:
                raise RuntimeError(f"dimension mismatch: expected {self.active.dim}, got {len(vector)}")
        return EmbeddingResult(vectors=vectors, dim=self.active.dim, backend=self.active.name,
                               degraded=self.active.name != BgeM3Engine.name,
                               latency_ms=round(elapsed_ms, 3),
                               ms_per_text=round(elapsed_ms / len(texts), 3))

    def encode_one(self, text: str) -> list[float]:
        return self.encode([text]).vectors[0]


EMBEDDING_SERVICE = EmbeddingService()
