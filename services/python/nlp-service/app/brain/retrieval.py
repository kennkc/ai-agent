"""检索客户端 —— 复用 Phase 3 body-service 的 `POST /api/body/retrieve`。

契约（与 `BodyController.hitRows` 一致）：
    请求 `{"query": str, "top_k": int, "use_cache": bool}` + 头 `X-Tenant-Id`
    响应 `list[dict]`，字段：chunk_id / doc_id / title / heading / chunk_index /
          content / source / score / rerank_score / ingest_time(_iso)

**降级可见**：body-service 不可用时抛 `RetrievalUnavailable`，由管线走「无资料回答 +
明确提示」，**绝不返回编造片段**。
"""
from __future__ import annotations

import json
import logging
import os
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Callable, Optional

logger = logging.getLogger("nlp-service.brain.retrieval")

DEFAULT_BODY_BASE_URL = os.getenv("BODY_BASE_URL", "http://127.0.0.1:8083")
DEFAULT_TIMEOUT = float(os.getenv("RETRIEVAL_TIMEOUT_SECONDS", "5"))


class RetrievalUnavailable(RuntimeError):
    """检索链路不可用（网络/超时/5xx）—— 上层必须显式降级，不得伪造结果。"""


@dataclass
class RetrievalOutcome:
    chunks: list[dict[str, Any]]
    backend: str = "body-service"
    degraded: bool = False
    latency_ms: int = 0
    error: str = ""

    def to_dict(self) -> dict:
        return {
            "chunks": self.chunks,
            "backend": self.backend,
            "degraded": self.degraded,
            "latency_ms": self.latency_ms,
            "error": self.error,
        }


class BodyRetriever:
    """body-service 检索客户端（可用 `transport` 注入做单测替身）。"""

    def __init__(
        self,
        base_url: str = DEFAULT_BODY_BASE_URL,
        timeout: float = DEFAULT_TIMEOUT,
        transport: Optional[Callable[[str, dict, dict, float], list]] = None,
    ) -> None:
        self.base_url = (base_url or DEFAULT_BODY_BASE_URL).rstrip("/")
        self.timeout = timeout
        self._transport = transport

    def retrieve(self, query: str, tenant_id: str = "default", top_k: int = 5) -> RetrievalOutcome:
        started = time.time()
        payload = {"query": query, "top_k": top_k, "use_cache": True}
        try:
            rows = self._call(payload, tenant_id)
        except Exception as exc:  # noqa: BLE001 - 统一转为「检索不可用」
            logger.warning("retrieval failed: %s", exc)
            raise RetrievalUnavailable(str(exc)) from exc
        chunks = [dict(row) for row in rows if isinstance(row, dict)]
        return RetrievalOutcome(
            chunks=chunks,
            backend="body-service",
            degraded=False,
            latency_ms=int((time.time() - started) * 1000),
        )

    def _call(self, payload: dict, tenant_id: str) -> list:
        if self._transport is not None:
            return self._transport("/api/body/retrieve", payload, {"X-Tenant-Id": tenant_id}, self.timeout)
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(  # noqa: S310 - 地址来自配置
            self.base_url + "/api/body/retrieve",
            data=body,
            headers={"Content-Type": "application/json", "X-Tenant-Id": tenant_id},
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=self.timeout) as response:  # noqa: S310
            raw = response.read().decode("utf-8")
        data = json.loads(raw)
        return data if isinstance(data, list) else []


RETRIEVER = BodyRetriever()
