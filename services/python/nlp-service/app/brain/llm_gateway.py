"""R4-03 LLM Gateway —— 模型路由 / 超时 / 重试 / Token 统计。

设计要点（与全局约定一致）：

* **模型路由**：`简单问答 → L1`、`检索问答 → L2`、`复杂推理 → L3`（L3 仅预留路由位）。
* **分层降级**：`L2 超时 → L1 重试 → 缓存兜底 → 提示`。
* **诚实降级**：未接入真实 LLM 引擎时走 `TemplateEngine`，
  返回值必须带 `degraded=True` 与 `generator="template"` ——
  **降级是可接受的工程取舍，伪造"模型生成"才是红线**（见 `docs/异常流程归纳.md` §2.5）。
* **Token 统计**：无分词器时用字符估算（中文 ≈ 1.5 char/token），用于成本监控口径统一。
* **总预算（REC-01）**：`generate(request, deadline_ms=...)` 给出**共享上限**，
  级联与重试只在预算内调度。没有它时最坏耗时 = 各级超时 × 重试次数的**乘积**
  （L2 8s×2 + L1 3s×2 = 22s），随层级增长而膨胀，上游只能靠不断调大超时追赶 ——
  而那会把「快速失败并降级」变成「长时间等待后仍降级」。见 `app/budget.py`。

后续接入真实引擎时实现 `LlmEngine.available()` + `generate()` 即可，路由/重试/统计无需改动。
"""
from __future__ import annotations

import logging
import os
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Callable, Optional

from app.budget import Deadline

logger = logging.getLogger("nlp-service.brain.llm")

# 模型层级：L1 轻量 / L2 标准 / L3 强推理（预留）
L1, L2, L3 = "L1", "L2", "L3"

_DEFAULT_TIMEOUTS = {L1: 3.0, L2: 8.0, L3: 20.0}

# DEBT-015: 未接入真实 LLM 引擎，生成走确定性模板后端（generator=template）
#   触发点: 接入 OpenAI/本地推理服务后切换（实现 LlmEngine 即可，路由层不动）
# DEBT-016: Token 统计为字符估算（中文 1.5 char/token），非真实分词器
#   触发点: 接入真实模型后改用 tokenizer 计数


def estimate_tokens(text: str) -> int:
    """Token 估算（无分词器时的统一口径）。空串记 0。"""
    if not text:
        return 0
    return max(1, int(round(len(text) / 1.5)))


@dataclass
class LlmRequest:
    prompt: str
    system: str = ""
    level: str = L2
    max_tokens: int = 512
    temperature: float = 0.2


@dataclass
class LlmResponse:
    text: str
    model: str
    level: str
    backend: str
    degraded: bool
    generator: str            # "template" | "model"
    prompt_tokens: int
    completion_tokens: int
    latency_ms: int
    attempts: int = 1
    error: str = ""


@dataclass
class GatewayStats:
    calls: int = 0
    by_level: dict[str, int] = field(default_factory=dict)
    by_backend: dict[str, int] = field(default_factory=dict)
    prompt_tokens: int = 0
    completion_tokens: int = 0
    timeouts: int = 0
    retries: int = 0
    degraded_calls: int = 0
    cache_hits: int = 0
    # REC-01：因**总预算耗尽**而未发起的调用次数（区别于「发起了但超时」的 timeouts）
    deadline_exceeded: int = 0


class LlmEngine:
    """LLM 引擎抽象。`available()` 决定是否参与路由。"""

    name = "abstract"
    level = L2

    def available(self) -> bool:  # pragma: no cover - 抽象
        return False

    def generate(self, request: LlmRequest, timeout: float) -> str:  # pragma: no cover - 抽象
        raise NotImplementedError


class TemplateEngine(LlmEngine):
    """确定性模板后端 —— **降级后端，不是伪实现**。

    它老老实实标注 `degraded=True` + `generator=template`，
    让调用方能据此提示用户「未接入生成模型」，而不是假装模型答的。
    """

    name = "template"
    level = L1

    def available(self) -> bool:
        return True

    def generate(self, request: LlmRequest, timeout: float) -> str:
        # 从 prompt 里取 Context 段的首个片段作为依据，避免凭空编造
        snippet = _first_context_line(request.prompt)
        question = _question_from_prompt(request.prompt)
        if snippet:
            return (
                f"[模板回答 · 未接入生成模型] 针对“{_short(question, 40)}”，"
                f"知识库中最相关的资料片段为：{_short(snippet, 160)}。"
                f"接入 LLM 后将基于该资料生成自然语言回答。"
            )
        return f"[模板回答 · 未接入生成模型] 未检索到可用资料，无法回答“{_short(question, 40)}”。"


class HttpLlmEngine(LlmEngine):
    """OpenAI 兼容 HTTP 引擎（未配置则不参与路由，不静默失败）。"""

    def __init__(self, base_url: str = "", api_key: str = "", model: str = "", level: str = L2) -> None:
        self.base_url = base_url or os.getenv("LLM_BASE_URL", "")
        self.api_key = api_key or os.getenv("LLM_API_KEY", "")
        self.model = model or os.getenv("LLM_MODEL", "")
        self.level = level
        self.name = f"http:{self.model}" if self.model else "http"

    def available(self) -> bool:
        return bool(self.base_url and self.model)

    def generate(self, request: LlmRequest, timeout: float) -> str:
        payload = {
            "model": self.model,
            "messages": _to_messages(request),
            "max_tokens": request.max_tokens,
            "temperature": request.temperature,
        }
        data = _json_dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            self.base_url.rstrip("/") + "/chat/completions",
            data=data,
            headers={"Content-Type": "application/json",
                     **({"Authorization": f"Bearer {self.api_key}"} if self.api_key else {})},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:  # noqa: S310 - 地址由配置给定
            body = _json_loads(resp.read().decode("utf-8"))
        choices = body.get("choices") or []
        if not choices:
            raise ValueError("llm response has no choices")
        return str(choices[0].get("message", {}).get("content", ""))


class LlmGateway:
    """模型路由 + 超时 + 重试 + 统计。

    路由顺序：按请求 level 找**可用**引擎 → 不可用或超时则降一级重试 →
    仍失败则抛 `LlmUnavailable`，由上层（RagPipeline）走降级链路。

    **两种失败必须区分**（这是本类的核心不变量）：

    | 情形 | 含义 | 计数 |
    |---|---|---|
    | 发起调用但引擎超时/报错 | 「下游慢或坏了」 | `timeouts` |
    | 总预算耗尽，不再发起 | **「时间用完了」** | `deadline_exceeded` |

    把后者混进前者，会把预算配置问题诊断成下游故障，进而去关掉一个其实正常的引擎。
    """

    def __init__(
        self,
        engines: Optional[list[LlmEngine]] = None,
        timeouts: Optional[dict[str, float]] = None,
        max_retries: int = 1,
        stats: Optional[GatewayStats] = None,
    ) -> None:
        self.engines = engines or [TemplateEngine()]
        self.timeouts = dict(_DEFAULT_TIMEOUTS)
        if timeouts:
            self.timeouts.update(timeouts)
        self.max_retries = max_retries
        self.stats = stats or GatewayStats()

    # ── 路由 ──
    def route(self, level: str) -> Optional[LlmEngine]:
        """选出该层级可用的引擎；没有则向上/向下找最近可用层级。"""
        for candidate in _fallback_chain(level):
            for engine in self.engines:
                if engine.level == candidate and engine.available():
                    return engine
        return None

    def health(self) -> dict:
        return {
            "available": any(e.available() for e in self.engines),
            "engines": [{"name": e.name, "level": e.level, "available": e.available()} for e in self.engines],
            "timeouts": dict(self.timeouts),
            # 级联最坏耗时（推导值）= 各级超时 × 每级尝试次数之和。
            # 这个数字**不在代码里**，只能算出来 —— 上游给它配预算时就是靠它。
            "cascade_worst_case_ms": self.cascade_worst_case_ms(),
            "stats": self.stats_dict(),
        }

    def cascade_worst_case_ms(self, level: str = L2) -> int:
        """级联最坏耗时（毫秒）—— 无 deadline 时的理论上限。

        它是 :meth:`generate` 逐级 `break` 的路径上所有超时之和，
        登进 `contracts/timeout-budget.yaml` 让上游知道该给多少预算。
        """
        attempts = self.max_retries + 1
        return int(sum(self.timeouts.get(lv, 8.0) for lv in _fallback_chain(level)) * attempts * 1000)

    def stats_dict(self) -> dict:
        s = self.stats
        return {
            "calls": s.calls,
            "by_level": dict(s.by_level),
            "by_backend": dict(s.by_backend),
            "prompt_tokens": s.prompt_tokens,
            "completion_tokens": s.completion_tokens,
            "timeouts": s.timeouts,
            "retries": s.retries,
            "degraded_calls": s.degraded_calls,
            "cache_hits": s.cache_hits,
            "deadline_exceeded": s.deadline_exceeded,
        }

    # ── 生成 ──
    def generate(self, request: LlmRequest, deadline_ms: Optional[int] = None) -> LlmResponse:
        """按请求层级生成；`deadline_ms` 为**整次级联共享**的总预算。

        有 deadline 时，每一级、每一次重试的可用超时都收进「剩余预算」内
        （`min(级超时, 剩余)`）；预算耗尽的瞬间**不再发起新的调用**，
        直接抛 `LlmUnavailable` 让上层走降级链路 —— 这样最坏耗时是**一个常量**，
        而不是随层级/重试数膨胀的乘积。
        """
        started = time.time()
        budget = Deadline(deadline_ms, operation="llm.generate") if deadline_ms else None
        prompt_tokens = estimate_tokens(request.system) + estimate_tokens(request.prompt)
        attempts = 0
        deadline_hit = False
        last_error = ""
        for level in _fallback_chain(request.level):
            engine = self.route(level)
            if engine is None:
                continue
            level_timeout = self.timeouts.get(level, 8.0)
            for attempt in range(1, self.max_retries + 2):
                timeout: Optional[float] = level_timeout
                if budget is not None:
                    timeout = budget.clamp(level_timeout)
                    if timeout is None or timeout <= 0:
                        # 预算已耗尽：**不再发起调用**。这不是「引擎不可用」，
                        # 而是「时间用完了」—— 两者必须区分，否则会把预算问题诊断成下游故障。
                        deadline_hit = True
                        budget.note_exhausted()
                        logger.warning(
                            "llm budget exhausted, stop cascade (level=%s elapsed=%sms budget=%sms)",
                            level, budget.elapsed_ms(), budget.budget_ms,
                        )
                        break
                attempts += 1
                try:
                    text = engine.generate(request, timeout)
                    if not text:
                        raise ValueError("empty completion")
                    self.stats.calls += 1
                    self.stats.by_level[level] = self.stats.by_level.get(level, 0) + 1
                    self.stats.by_backend[engine.name] = self.stats.by_backend.get(engine.name, 0) + 1
                    self.stats.prompt_tokens += prompt_tokens
                    completion_tokens = estimate_tokens(text)
                    self.stats.completion_tokens += completion_tokens
                    degraded = engine.name == TemplateEngine.name
                    if degraded:
                        self.stats.degraded_calls += 1
                    return LlmResponse(
                        text=text,
                        model=engine.name,
                        level=level,
                        backend=engine.name,
                        degraded=degraded,
                        generator="template" if degraded else "model",
                        prompt_tokens=prompt_tokens,
                        completion_tokens=completion_tokens,
                        latency_ms=int((time.time() - started) * 1000),
                        attempts=attempts,
                    )
                except Exception as exc:  # noqa: BLE001 - 超时/网络均需重试
                    last_error = f"{type(exc).__name__}: {exc}"
                    if isinstance(exc, (urllib.error.URLError, TimeoutError, OSError)) or "timeout" in str(exc).lower():
                        self.stats.timeouts += 1
                    if attempt <= self.max_retries:
                        self.stats.retries += 1
                        logger.warning("llm call failed (level=%s attempt=%s): %s", level, attempt, last_error)
                        continue
                    break
            if deadline_hit:
                break
        if deadline_hit:
            self.stats.deadline_exceeded += 1
            raise LlmUnavailable(
                f"llm total budget exhausted (budget={deadline_ms}ms, level={request.level}, "
                f"attempts={attempts}): {last_error or 'no attempt issued'}"
            )
        raise LlmUnavailable(f"no available llm engine (level={request.level}): {last_error}")


class LlmUnavailable(RuntimeError):
    """所有层级均不可用 —— 上层应据此走降级链路（缓存 / 检索直出），不得伪造回答。"""


# ─────────── 内部工具 ───────────

_FALLBACK_ORDER = {L3: [L3, L2, L1], L2: [L2, L1], L1: [L1]}


def _fallback_chain(level: str) -> list[str]:
    return _FALLBACK_ORDER.get(level, [L2, L1])


def _to_messages(request: LlmRequest) -> list[dict]:
    messages = []
    if request.system:
        messages.append({"role": "system", "content": request.system})
    messages.append({"role": "user", "content": request.prompt})
    return messages


_PREFIXES = ("System:", "Context:", "History:", "模板:", "约束", "Question:")


def _first_context_line(prompt: str) -> str:
    """取 Prompt 中第一条检索片段（形如 `[1][来源 标题] 正文`）。"""
    for line in prompt.splitlines():
        text = line.strip()
        if not text or text.startswith(_PREFIXES):
            continue
        if text.startswith("[") and "]" in text:
            body = text.split("]", 1)[1].strip()
            if body.startswith("[") and "]" in body:    # 形如 [1][来源 X] 正文
                body = body.split("]", 1)[1].strip()
            if body:
                return body
        return text            # 无编号前缀时退回首条正文
    return ""


def _question_from_prompt(prompt: str) -> str:
    """取 Prompt 中的 `Question:` 行；取不到则退回最后一行非空正文。"""
    question = ""
    for line in prompt.splitlines():
        text = line.strip()
        if text.startswith("Question:"):
            question = text[len("Question:"):].strip()
            break
    if question:
        return question
    for line in reversed(prompt.splitlines()):
        text = line.strip()
        if text and not text.startswith("约束"):
            return text
    return ""


def _short(text: str, limit: int) -> str:
    text = " ".join(text.split())
    return text if len(text) <= limit else text[:limit] + "…"


def _json_dumps(value: Any) -> str:
    import json
    return json.dumps(value, ensure_ascii=False)


def _json_loads(text: str) -> Any:
    import json
    return json.loads(text)


LLM_GATEWAY = LlmGateway(engines=[TemplateEngine(), HttpLlmEngine(level=L2)])
