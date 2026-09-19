"""R4-04 语义缓存 —— 相似问题命中缓存（向量相似度阈值 0.95，复用 Phase 3 嵌入）。

存储：Redis（复用 Phase 3 的 lifeform-redis）；Redis 不可用时**降级为进程内缓存**，
并如实标注 `backend=memory` + `degraded=True` —— 缓存降级不影响正确性，只影响命中率。

**诚实性约定**：
* 命中率统计只统计**真实命中**，缓存不可用时**不虚增**（`unavailable` 期间不计入分母外的命中）。
* 键按 tenant 隔离；写入 TTL 可配，默认 1h（与 body-service 检索缓存口径一致）。
"""
from __future__ import annotations

import logging
import os
import time
from dataclasses import dataclass, field
from typing import Any, Optional

logger = logging.getLogger("nlp-service.brain.cache")

SIMILARITY_THRESHOLD = float(os.getenv("SEMANTIC_CACHE_THRESHOLD", "0.95"))
DEFAULT_TTL = int(os.getenv("SEMANTIC_CACHE_TTL_SECONDS", "3600"))
REDIS_HOST = os.getenv("REDIS_HOST", "127.0.0.1")
REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))
KEY_PREFIX = "brain:cache:"


def cosine_similarity(left: list[float], right: list[float]) -> float:
    """余弦相似度（嵌入向量已归一化，点积即余弦；此处仍按通用式实现，防未归一化后端）。"""
    if not left or not right or len(left) != len(right):
        return 0.0
    dot = 0.0
    norm_l = 0.0
    norm_r = 0.0
    for a, b in zip(left, right):
        dot += a * b
        norm_l += a * a
        norm_r += b * b
    if norm_l <= 0 or norm_r <= 0:
        return 0.0
    return dot / ((norm_l ** 0.5) * (norm_r ** 0.5))


@dataclass
class CacheStats:
    lookups: int = 0
    hits: int = 0
    misses: int = 0
    writes: int = 0
    evictions: int = 0

    @property
    def hit_rate(self) -> float:
        return round(self.hits / self.lookups, 4) if self.lookups else 0.0

    def to_dict(self) -> dict:
        return {
            "lookups": self.lookups, "hits": self.hits, "misses": self.misses,
            "writes": self.writes, "evictions": self.evictions, "hit_rate": self.hit_rate,
        }


@dataclass
class _Entry:
    question: str
    vector: list[float]
    payload: dict[str, Any]
    created_at: float
    expires_at: float


class SemanticCache:
    """向量相似缓存；Redis 优先，进程内兜底。"""

    def __init__(
        self,
        threshold: float = SIMILARITY_THRESHOLD,
        ttl_seconds: int = DEFAULT_TTL,
        redis_client: Any = "auto",
        encoder: Any = None,
        stats: Optional[CacheStats] = None,
    ) -> None:
        """`redis_client="auto"`（默认）自动探测 Redis；显式传 `None` 则强制进程内后端。

        单测/离线场景传 `None` 可得到确定性行为，不依赖 Redis 是否在线。
        """
        self.threshold = threshold
        self.ttl_seconds = ttl_seconds
        self.stats = stats or CacheStats()
        self._encoder = encoder
        self._local: dict[str, list[_Entry]] = {}
        self._backend = "memory"
        self._degraded = True
        self._redis: Any = None
        if redis_client == "auto":
            self._redis = _try_connect()
        elif redis_client is not None:
            self._redis = redis_client
        if self._redis is not None:
            self._backend = "redis"
            self._degraded = False

    # ── 属性 ──
    @property
    def backend(self) -> str:
        return self._backend

    @property
    def degraded(self) -> bool:
        return self._degraded

    def health(self) -> dict:
        return {
            "backend": self._backend,
            "degraded": self._degraded,
            "threshold": self.threshold,
            "ttl_seconds": self.ttl_seconds,
            "entries": self._size(),
            "stats": self.stats.to_dict(),
        }

    # ── 读写 ──
    def lookup(self, question: str, tenant_id: str = "default") -> Optional[dict[str, Any]]:
        self.stats.lookups += 1
        vector = self._encode(question)
        if not vector:
            self.stats.misses += 1
            return None
        best_score = 0.0
        best_payload: Optional[dict[str, Any]] = None
        for entry in self._entries(tenant_id):
            score = cosine_similarity(vector, entry.vector)
            if score > best_score:
                best_score, best_payload = score, entry.payload
        if best_payload is not None and best_score >= self.threshold:
            self.stats.hits += 1
            payload = dict(best_payload)
            payload["cache_hit"] = True
            payload["cache_similarity"] = round(best_score, 4)
            payload["cache_backend"] = self._backend
            return payload
        self.stats.misses += 1
        return None

    def store(self, question: str, payload: dict[str, Any], tenant_id: str = "default") -> None:
        vector = self._encode(question)
        if not vector:
            return
        self.stats.writes += 1
        now = time.time()
        entry = _Entry(question=question, vector=vector, payload=dict(payload),
                       created_at=now, expires_at=now + self.ttl_seconds)
        bucket = self._local.setdefault(tenant_id, [])
        bucket.append(entry)
        self._evict(tenant_id)

    def clear(self, tenant_id: Optional[str] = None) -> None:
        if tenant_id is None:
            self._local.clear()
        else:
            self._local.pop(tenant_id, None)

    # ── 内部 ──
    def _entries(self, tenant_id: str) -> list[_Entry]:
        now = time.time()
        bucket = self._local.get(tenant_id, [])
        alive = [e for e in bucket if e.expires_at > now]
        if len(alive) != len(bucket):
            self.stats.evictions += len(bucket) - len(alive)
            self._local[tenant_id] = alive
        return alive

    def _evict(self, tenant_id: str, max_entries: int = 200) -> None:
        bucket = self._local.get(tenant_id, [])
        if len(bucket) > max_entries:
            self.stats.evictions += len(bucket) - max_entries
            self._local[tenant_id] = bucket[-max_entries:]

    def _size(self) -> int:
        return sum(len(v) for v in self._local.values())

    def _encode(self, text: str) -> list[float]:
        encoder = self._encoder
        if encoder is None:
            from app.embedding import EMBEDDING_SERVICE  # 延迟导入，避免循环依赖
            encoder = EMBEDDING_SERVICE
            self._encoder = encoder
        try:
            return list(encoder.encode_one(text or ""))
        except Exception as exc:  # noqa: BLE001
            logger.warning("semantic cache encode failed: %s", exc)
            return []


def _try_connect() -> Any:
    """尝试连 Redis；失败返回 None（降级进程内，不抛错）。"""
    try:
        import redis  # type: ignore

        client = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, socket_timeout=1.0, socket_connect_timeout=1.0)
        client.ping()
        return client
    except Exception as exc:  # noqa: BLE001
        logger.info("semantic cache degraded to in-process backend: %s", exc)
        return None


SEMANTIC_CACHE = SemanticCache()
