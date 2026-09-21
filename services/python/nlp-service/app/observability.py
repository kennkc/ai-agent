"""Prometheus business metrics for nlp-service."""
from __future__ import annotations

from prometheus_client import Counter, Histogram

LLM_CALLS = Counter(
    "lifeform_llm_calls_total",
    "LLM calls by role, backend, outcome and degradation state.",
    ["role", "backend", "outcome", "degraded"],
)
LLM_TOKENS = Counter(
    "lifeform_llm_tokens_total",
    "LLM tokens by role, model, source and token kind.",
    ["role", "model", "token_source", "kind"],
)
LLM_LATENCY = Histogram(
    "lifeform_llm_latency_seconds",
    "LLM call latency in seconds.",
    ["role", "backend", "outcome"],
    buckets=(0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 30, 60),
)


def record_llm_call(*, role: str, backend: str, outcome: str, degraded: bool,
                    latency_ms: int, prompt_tokens: int = 0,
                    completion_tokens: int = 0, token_source: str = "",
                    model: str = "") -> None:
    role = role or "unknown"
    backend = backend or "unknown"
    model = model or backend
    labels = {"role": role, "backend": backend, "outcome": outcome, "degraded": str(bool(degraded)).lower()}
    LLM_CALLS.labels(**labels).inc()
    LLM_LATENCY.labels(role=role, backend=backend, outcome=outcome).observe(max(0, latency_ms) / 1000.0)
    if prompt_tokens > 0:
        LLM_TOKENS.labels(role=role, model=model, token_source=token_source or "unknown", kind="prompt").inc(prompt_tokens)
    if completion_tokens > 0:
        LLM_TOKENS.labels(role=role, model=model, token_source=token_source or "unknown", kind="completion").inc(completion_tokens)
