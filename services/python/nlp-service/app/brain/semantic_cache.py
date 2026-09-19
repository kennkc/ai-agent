"""R4-04 语义缓存 —— 相似问题命中缓存（向量相似度阈值 0.95，复用 Phase 3 嵌入）。

存储：**Redis** Hash（`brain:cache:{tenant}`，field=条目 id，value=JSON）+ TTL；
Redis 不可用时**降级为进程内缓存**，并如实标注 `backend=memory` + `degraded=True`。

实现口径（务必读，避免误判技术栈）：

* 向量相似判定为**应用层余弦扫描**（对当前后端 768 维哈希嵌入，条目上限 200/租户，
  单次命中判定 <1ms），**不是 Redis Stack 的 VSS 向量索引** —— 本机 Redis 7.x 无 VSS 模块。
  换 BGE-M3 后端后若条目量上到万级，再换 Redis VSS 或 Qdrant 侧缓存，接口不变。
* 向量以**稀疏形式**存储（`{维度下标: 分量}` + 预计算模长），哈希嵌入绝大多数分量为 0，
  稀疏化后单条目从 ~15KB 降到 ~1KB，避免 Redis 被缓存撑爆。
* 命中率统计只统计**真实命中**，缓存不可用时**不虚增**。

**诚实性约定**：`backend` 字段反映**数据真实落在哪**——Redis 可用即 `redis`，
降级即 `memory`；不出现"连上了 Redis 却把数据留在内存、仍报 redis"的谎报。
"""
from __future__ import annotations

import json
import logging
import os
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Iterable, Optional

logger = logging.getLogger("nlp-service.brain.cache")

SIMILARITY_THRESHOLD = float(os.getenv("SEMANTIC_CACHE_THRESHOLD", "0.95"))
DEFAULT_TTL = int(os.getenv("SEMANTIC_CACHE_TTL_SECONDS", "3600"))
REDIS_HOST = os.getenv("REDIS_HOST", "127.0.0.1")
REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))
KEY_PREFIX = "brain:cache:"
DEFAULT_MAX_ENTRIES = int(os.getenv("SEMANTIC_CACHE_MAX_ENTRIES", "200"))


def cosine_similarity(left: list[float], right: list[float]) -> float:
    """稠密向量余弦相似度（兼容旧调用方；新路径走稀疏实现）。"""
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


def sparse_cosine(left: dict[int, float], left_norm: float,
                  right: dict[int, float], right_norm: float) -> float:
    """稀疏向量余弦：只对**较小的一侧**遍历下标做点积，模长由存储侧预计算提供。"""
    if not left or not right or left_norm <= 0 or right_norm <= 0:
        return 0.0
    if len(left) > len(right):
        left, right = right, left
    dot = 0.0
    for index, value in left.items():
        other = right.get(index)
        if other:
            dot += value * other
    return dot / (left_norm * right_norm)


def to_sparse(vector: Iterable[float]) -> tuple[dict[int, float], float]:
    """稠密向量 → 稀疏表示 + 模长。零分量丢弃（哈希嵌入绝大多数分量为 0）。"""
    sparse: dict[int, float] = {}
    norm = 0.0
    for index, value in enumerate(vector or []):
        if value:
            sparse[index] = float(value)
            norm += float(value) * float(value)
    return sparse, norm ** 0.5


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
    """一条缓存条目（Redis 与内存后端共用同一结构）。"""
    entry_id: str
    question: str
    vector: dict[int, float]
    norm: float
    payload: dict[str, Any]
    created_at: float
    expires_at: float


class SemanticCache:
    """向量相似缓存；**Redis 优先且真实读写**，进程内仅作降级兜底。"""

    def __init__(
        self,
        threshold: float = SIMILARITY_THRESHOLD,
        ttl_seconds: int = DEFAULT_TTL,
        redis_client: Any = "auto",
        encoder: Any = None,
        stats: Optional[CacheStats] = None,
        max_entries: int = DEFAULT_MAX_ENTRIES,
    ) -> None:
        """`redis_client="auto"`（默认）自动探测 Redis；显式传 `None` 则强制进程内后端。

        单测/离线场景传 `None` 可得到确定性行为，不依赖 Redis 是否在线；
        要验证「真的写进了 Redis」需注入一个假 Redis（见 `tests/test_brain.py` 的
        `_FakeRedis`）或直连本机 Redis —— **不能只靠 `redis_client=None` 的用例**，
        那样只会证明内存兜底自洽，抓不到主路径是空壳。
        """
        self.threshold = threshold
        self.ttl_seconds = ttl_seconds
        self.max_entries = max_entries
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
        sparse, norm = self._encode(question)
        if not sparse:
            self.stats.misses += 1
            return None
        best_score = 0.0
        best_payload: Optional[dict[str, Any]] = None
        for entry in self._iter_entries(tenant_id):
            score = sparse_cosine(sparse, norm, entry.vector, entry.norm)
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
        sparse, norm = self._encode(question)
        if not sparse:
            return
        self.stats.writes += 1
        now = time.time()
        entry = _Entry(
            entry_id=uuid.uuid4().hex,
            question=question,
            vector=sparse,
            norm=norm,
            payload=dict(payload),
            created_at=now,
            expires_at=now + self.ttl_seconds,
        )
        if self._redis is not None:
            self._redis_store(tenant_id, entry)
        else:
            bucket = self._local.setdefault(tenant_id, [])
            bucket.append(entry)
            self._local_prune(tenant_id)

    def clear(self, tenant_id: Optional[str] = None) -> None:
        if tenant_id is None:
            self._local.clear()
            if self._redis is not None:
                self._redis_clear_all()
            return
        self._local.pop(tenant_id, None)
        if self._redis is not None:
            self._redis.delete(self._key(tenant_id))

    # ── 条目遍历（按后端分流）──
    def _iter_entries(self, tenant_id: str) -> list[_Entry]:
        if self._redis is not None:
            return self._redis_entries(tenant_id)
        return self._local_entries(tenant_id)

    def _local_entries(self, tenant_id: str) -> list[_Entry]:
        now = time.time()
        bucket = self._local.get(tenant_id, [])
        alive = [e for e in bucket if e.expires_at > now]
        if len(alive) != len(bucket):
            self.stats.evictions += len(bucket) - len(alive)
            self._local[tenant_id] = alive
        return alive

    def _local_prune(self, tenant_id: str) -> None:
        bucket = self._local.get(tenant_id, [])
        if len(bucket) > self.max_entries:
            self.stats.evictions += len(bucket) - self.max_entries
            self._local[tenant_id] = bucket[-self.max_entries:]

    def _size(self) -> int:
        if self._redis is not None:
            try:
                return int(self._redis.hlen(self._key("*")) or 0) if False else self._redis_size()
            except Exception as exc:  # noqa: BLE001
                logger.warning("semantic cache size failed: %s", exc)
                return 0
        return sum(len(v) for v in self._local.values())

    # ── Redis 读写 ──
    def _key(self, tenant_id: str) -> str:
        return f"{KEY_PREFIX}{tenant_id}"

    def _redis_store(self, tenant_id: str, entry: _Entry) -> None:
        key = self._key(tenant_id)
        try:
            self._redis.hset(key, entry.entry_id, _dumps(_encode_entry(entry)))
            self._redis.expire(key, self.ttl_seconds + 60)
        except Exception as exc:  # noqa: BLE001
            logger.warning("semantic cache redis write failed, drop entry: %s", exc)
            return
        self._redis_prune(tenant_id)

    def _redis_entries(self, tenant_id: str) -> list[_Entry]:
        key = self._key(tenant_id)
        try:
            raw = self._redis.hgetall(key) or {}
        except Exception as exc:  # noqa: BLE001
            logger.warning("semantic cache redis read failed: %s", exc)
            return []
        now = time.time()
        entries: list[_Entry] = []
        expired: list[str] = []
        for field, value in raw.items():
            entry = _decode_entry(field, value)
            if entry is None:
                expired.append(field)
                continue
            if entry.expires_at <= now:
                expired.append(field)
                continue
            entries.append(entry)
        if expired:
            try:
                self._redis.hdel(key, *expired)
                self.stats.evictions += len(expired)
            except Exception as exc:  # noqa: BLE001 - 清理失败不阻断主链路
                logger.warning("semantic cache redis prune failed: %s", exc)
        return entries

    def _redis_prune(self, tenant_id: str) -> None:
        """容量裁剪：超过 max_entries 时按创建时间淘汰最旧的一批。"""
        entries = self._redis_entries(tenant_id)
        if len(entries) <= self.max_entries:
            return
        ordered = sorted(entries, key=lambda e: e.created_at)
        drop = ordered[: len(entries) - self.max_entries]
        try:
            self._redis.hdel(self._key(tenant_id), *[e.entry_id for e in drop])
            self.stats.evictions += len(drop)
        except Exception as exc:  # noqa: BLE001
            logger.warning("semantic cache redis capacity prune failed: %s", exc)

    def _redis_size(self) -> int:
        total = 0
        try:
            cursor = 0
            while True:
                cursor, keys = self._redis.scan(cursor=cursor, match=f"{KEY_PREFIX}*", count=100)
                for key in keys or []:
                    total += int(self._redis.hlen(key) or 0)
                if not cursor:
                    break
        except Exception as exc:  # noqa: BLE001
            logger.warning("semantic cache redis scan failed: %s", exc)
        return total

    def _redis_clear_all(self) -> None:
        try:
            cursor = 0
            while True:
                cursor, keys = self._redis.scan(cursor=cursor, match=f"{KEY_PREFIX}*", count=100)
                if keys:
                    self._redis.delete(*keys)
                if not cursor:
                    break
        except Exception as exc:  # noqa: BLE001
            logger.warning("semantic cache redis clear failed: %s", exc)

    # ── 编码 ──
    def _encode(self, text: str) -> tuple[dict[int, float], float]:
        encoder = self._encoder
        if encoder is None:
            from app.embedding import EMBEDDING_SERVICE  # 延迟导入，避免循环依赖
            encoder = EMBEDDING_SERVICE
            self._encoder = encoder
        try:
            vector = list(encoder.encode_one(text or ""))
        except Exception as exc:  # noqa: BLE001
            logger.warning("semantic cache encode failed: %s", exc)
            return {}, 0.0
        return to_sparse(vector)


# ─────────── 序列化 ───────────

def _encode_entry(entry: _Entry) -> dict:
    return {
        "q": entry.question,
        "v": {str(k): round(v, 6) for k, v in entry.vector.items()},
        "n": round(entry.norm, 6),
        "p": entry.payload,
        "t": entry.created_at,
        "e": entry.expires_at,
    }


def _decode_entry(field: Any, raw: Any) -> Optional[_Entry]:
    """反序列化；**单条损坏不影响整体**（返回 None 由调用方剔除）。"""
    try:
        data = _loads(raw)
        vector = {int(k): float(v) for k, v in (data.get("v") or {}).items()}
        return _Entry(
            entry_id=str(field),
            question=str(data.get("q", "")),
            vector=vector,
            norm=float(data.get("n", 0.0)),
            payload=dict(data.get("p") or {}),
            created_at=float(data.get("t", 0.0)),
            expires_at=float(data.get("e", 0.0)),
        )
    except Exception:  # noqa: BLE001
        return None


def _dumps(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False)


def _loads(text: Any) -> Any:
    if isinstance(text, bytes):
        text = text.decode("utf-8")
    return json.loads(text)


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
