"""R4-03 LLM Gateway —— 模型路由 / 超时 / 重试 / Token 统计。

设计要点（与全局约定一致）：

* **模型路由**：`简单问答 → L1`、`检索问答 → L2`、`复杂推理 → L3`（L3 仅预留路由位）。
* **角色路由（WB-10）**：`request.role` 命中前台配置（`llm_model_config` 表）时，
  先按该角色注册的引擎顺序尝试，失败后再落回上面的层级级联。
  两条路共用同一套预算与重试语义，**层级降级语义不被绕过**。
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
from collections.abc import Callable
from dataclasses import dataclass, field, replace
from typing import Any

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
    return max(1, round(len(text) / 1.5))


# Token 用量的**来源标识**。这个字段比数字本身更重要：
# 估算值与供应商自报值在成本核算里不可混用，混用会让"用量看板"看起来精确、实则系统性偏差。
TOKEN_SOURCE_PROVIDER = "provider"    # 供应商响应体里的 usage（真实值）
TOKEN_SOURCE_ESTIMATED = "estimated"  # 字符估算（DEBT-016）


@dataclass(frozen=True)
class TokenUsage:
    """供应商自报的用量。由引擎在单次调用后回传，**不改 `generate()` 签名**。

    为什么用属性回传而不是让 `generate()` 返回结构体：后者会让每一个
    既有引擎实现（含测试里的桩）同时失效，收益与改动面不成比例。
    代价是网关必须在**每次尝试前清空**该属性，否则重试会读到上一次的残留值。
    """

    prompt_tokens: int
    completion_tokens: int
    source: str = TOKEN_SOURCE_PROVIDER


@dataclass
class LlmRequest:
    prompt: str
    system: str = ""
    level: str = L2
    max_tokens: int = 512
    temperature: float = 0.2
    # 功能角色（intent / embed / rerank / generate / plan / code）。
    # 有值时**优先**走该角色在前台配置的模型；无值或该角色未配置时，
    # 退回按 `level` 的层级级联 —— 这两条路互不影响。
    role: str = ""
    # 计量归属的租户。**只用于用量落账**，不参与路由 ——
    # 路由按租户分流属多租户兜底（model_config.resolve_engines 的 inherited_from），
    # 两件事分开，避免"计量维度和路由维度"耦合在一起。
    tenant_id: str = "default"


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
    # 上面两个 token 数的**来源**。`estimated` 表示无分词器时的字符估算（DEBT-016）——
    # 用它算成本会系统性偏差，看板必须能区分，不能把估算值当真实值展示。
    token_source: str = TOKEN_SOURCE_ESTIMATED
    attempts: int = 1
    error: str = ""
    role: str = ""            # 实际服务的功能角色（用于回放时区分"谁答的"）


@dataclass
class GatewayStats:
    calls: int = 0
    by_level: dict[str, int] = field(default_factory=dict)
    by_backend: dict[str, int] = field(default_factory=dict)
    prompt_tokens: int = 0
    completion_tokens: int = 0
    # 按来源拆分调用数：`provider` 与 `estimated` 各多少。
    # 只报总和是不够的 —— 一个"全是估算值"的看板和一个"含真实值"的看板
    # 在页面上长得一样，必须让人一眼看出成本口径的成色。
    by_token_source: dict[str, int] = field(default_factory=dict)
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
    # 归属的功能角色；空串 = 通用引擎（只参与层级级联，不参与角色路由）
    role = ""
    # 实际模型名；基类必须给出，否则 role_summary()/health() 遍历异类引擎时会崩
    model: str = ""
    provider: str = ""
    # 该引擎自带的生成参数；None = 沿用请求里的值
    max_tokens: int | None = None
    temperature: float | None = None
    timeout_ms: int | None = None
    # 最近一次 `generate()` 回传的**供应商自报**用量；None = 本次没有（或引擎不回）。
    # 类属性缺省用不可变的 None（不能放 dict 这类易变对象 —— 那会变成跨实例共享的脏状态）。
    last_usage: TokenUsage | None = None

    def available(self) -> bool:  # pragma: no cover - 抽象
        return False

    def generate(self, request: LlmRequest, timeout: float) -> str:  # pragma: no cover - 抽象
        raise NotImplementedError

    def apply_defaults(self, request: LlmRequest) -> LlmRequest:
        """把引擎自带的生成参数覆盖到请求上（**仅覆盖，不新增语义**）。

        例：前台给「内容生成」角色配了 `max_tokens=2048`，而管线按通用默认
        构造了 512 —— 若不做这一步，前台配的参数就是摆设。
        """
        overrides: dict[str, Any] = {}
        if self.max_tokens:
            overrides["max_tokens"] = int(self.max_tokens)
        if self.temperature is not None:
            overrides["temperature"] = float(self.temperature)
        return replace(request, **overrides) if overrides else request

    def effective_timeout(self, fallback: float) -> float:
        return float(self.timeout_ms) / 1000.0 if self.timeout_ms else fallback


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
    """OpenAI 兼容 HTTP 引擎（未配置则不参与路由，不静默失败）。

    两种来源：
    * **环境变量**（`LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL`）—— 启动兜底；
    * **前台配置**（`llm_model_config` 表）—— 经 `from_spec()` 构造，按功能角色注册。

    后者优先。两者都不存在时 `available()` 为 False，路由自然跳过。
    """

    def __init__(self, base_url: str = "", api_key: str = "", model: str = "", level: str = L2,
                 role: str = "", name: str = "", provider: str = "",
                 max_tokens: int | None = None, temperature: float | None = None,
                 timeout_ms: int | None = None) -> None:
        self.base_url = base_url or os.getenv("LLM_BASE_URL", "")
        self.api_key = api_key or os.getenv("LLM_API_KEY", "")
        self.model = model or os.getenv("LLM_MODEL", "")
        self.level = level
        self.role = role
        self.provider = provider
        self.max_tokens = max_tokens
        self.temperature = temperature
        self.timeout_ms = timeout_ms
        # 名字进决策链回放，必须能区分"同角色下换了哪个模型"，故带上角色与显示名
        base_name = f"http:{self.model}" if self.model else "http"
        self.name = name or (f"{role}:{base_name}" if role else base_name)

    @classmethod
    def from_spec(cls, spec: dict) -> HttpLlmEngine:
        """由 `model_config.resolve_engines()` 的规格构造（凭据已解密）。"""
        return cls(
            base_url=str(spec.get("base_url") or ""),
            api_key=str(spec.get("api_key") or ""),
            model=str(spec.get("model") or ""),
            level=str(spec.get("tier") or L2),
            role=str(spec.get("role") or ""),
            name=f"{spec.get('role', '')}:{spec.get('name', '')}",
            provider=str(spec.get("provider") or ""),
            max_tokens=spec.get("max_tokens"),
            temperature=spec.get("temperature"),
            timeout_ms=spec.get("timeout_ms"),
        )

    def available(self) -> bool:
        return bool(self.base_url and self.model)

    def generate(self, request: LlmRequest, timeout: float) -> str:
        # **每次调用前清空**：重试时读到上一次的残留用量，会把失败的调用算成成功用量。
        self.last_usage: TokenUsage | None = None
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
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = _json_loads(resp.read().decode("utf-8"))
        choices = body.get("choices") or []
        if not choices:
            raise ValueError("llm response has no choices")
        # OpenAI 兼容响应里的 `usage` 是**供应商自报的真实用量**。
        # 修复前这里只取 content，把 usage 直接丢了，于是全链路一律退回字符估算 ——
        # 用量看板无论怎么做都会偏，且偏得"看起来很正常"。
        self.last_usage = _parse_usage(body.get("usage"))
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
        engines: list[LlmEngine] | None = None,
        timeouts: dict[str, float] | None = None,
        max_retries: int = 1,
        stats: GatewayStats | None = None,
        role_engines: dict[str, list[LlmEngine]] | None = None,
        usage_sink: Callable[[dict], Any] | None = None,
    ) -> None:
        self.engines = engines or [TemplateEngine()]
        self.timeouts = dict(_DEFAULT_TIMEOUTS)
        if timeouts:
            self.timeouts.update(timeouts)
        self.max_retries = max_retries
        self.stats = stats or GatewayStats()
        # 用量落账的出口（默认 lazy 导入 app.token_usage）。做成可注入是为了让测试
        # 不必起库就能断言"记了什么"，同时也让计量与网关**不形成导入环**。
        self.usage_sink: Callable[[dict], Any] = usage_sink or _default_usage_sink
        # 功能角色 → 引擎列表（按 routing_weight 降序，见 model_config.resolve_engines）。
        # 它与 `self.engines` 是**两条独立路由**：这里是「前台为该角色配的模型」，
        # 后者是「按层级的通用兜底」。角色路由失败后仍会落到层级级联上 ——
        # 保持既有 `L2 超时 → L1 重试 → 模板兜底` 的降级语义不被绕过。
        self.role_engines: dict[str, list[LlmEngine]] = {
            str(key): list(value) for key, value in (role_engines or {}).items()
        }

    # ── 角色引擎（前台配置侧）──
    def set_role_engines(self, mapping: dict[str, list[LlmEngine]] | None) -> None:
        self.role_engines = {str(key): list(value) for key, value in (mapping or {}).items()}

    def apply_specs(self, specs: list[dict] | None) -> dict:
        """由 `model_config.resolve_engines()` 的规格重建角色引擎，返回装配摘要。

        `available()` 为假的规格（缺 base_url / model）被**丢弃**而不是注册成
        "永远不可用的引擎" —— 后者会让 `health()` 显示的引擎数与实际可用的对不上。
        """
        grouped: dict[str, list[LlmEngine]] = {}
        for spec in specs or []:
            engine = HttpLlmEngine.from_spec(spec)
            if engine.available():
                grouped.setdefault(engine.role, []).append(engine)
        self.set_role_engines(grouped)
        return self.role_summary()

    def role_summary(self) -> dict:
        return {
            role: [
                {"name": getattr(engine, "name", ""), "model": getattr(engine, "model", ""),
                 "level": getattr(engine, "level", L2), "timeout_ms": getattr(engine, "timeout_ms", None),
                 "provider": getattr(engine, "provider", "")}
                for engine in engines
            ]
            for role, engines in self.role_engines.items()
        }

    def reload_from_model_config(self, tenant_id: str = "default") -> dict:
        """从 `llm_model_config` 表重新装配角色引擎（**不重启进程**）。

        失败时**保留原有装配**而不是清空 —— 一次读取失败不应该让线上问答
        从"用配置的模型"变成"全部降级到模板"。
        """
        from app import model_config  # 延迟导入，避免与 storage 层形成导入环

        try:
            specs = model_config.resolve_engines(tenant_id)
        except Exception as exc:  # noqa: BLE001 - 配置读取失败不得击穿服务
            logger.warning("reload llm role engines failed (tenant=%s): %s", tenant_id, exc)
            return {"ok": False, "error": f"{type(exc).__name__}: {exc}",
                    "roles": self.role_summary(), "kept_previous": True}
        roles = self.apply_specs(specs)
        return {"ok": True, "roles": roles,
                "count": sum(len(value) for value in self.role_engines.values()),
                "tenant_id": tenant_id}

    def _candidates(self, request: LlmRequest) -> list[LlmEngine]:
        """本次调用的候选引擎序列：**角色优先，层级兜底**（顺序即降级顺序）。"""
        candidates: list[LlmEngine] = []
        seen: set[int] = set()
        for engine in self.role_engines.get(request.role or "", []):
            if engine.available() and id(engine) not in seen:
                seen.add(id(engine))
                candidates.append(engine)
        for level in _fallback_chain(request.level):
            engine = self.route(level)
            if engine is not None and id(engine) not in seen:
                seen.add(id(engine))
                candidates.append(engine)
        return candidates

    # ── 路由 ──
    def route(self, level: str) -> LlmEngine | None:
        """选出该层级可用的引擎；没有则向上/向下找最近可用层级。"""
        for candidate in _fallback_chain(level):
            for engine in self.engines:
                if engine.level == candidate and engine.available():
                    return engine
        return None

    def health(self) -> dict:
        return {
            "available": any(e.available() for e in self.engines)
                         or any(e.available() for group in self.role_engines.values() for e in group),
            "engines": [{"name": e.name, "level": e.level, "available": e.available()} for e in self.engines],
            # 前台配置的角色引擎：`role_configured` 为空表示完全没配，
            # 此时生成一律走层级级联（最终落到模板后端并标 degraded）。
            "roles": self.role_summary(),
            "role_configured": sorted(self.role_engines.keys()),
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
            "by_token_source": dict(s.by_token_source),
            "timeouts": s.timeouts,
            "retries": s.retries,
            "degraded_calls": s.degraded_calls,
            "cache_hits": s.cache_hits,
            "deadline_exceeded": s.deadline_exceeded,
        }

    # ── 生成 ──
    def _record_usage(self, engine: LlmEngine | None, request: LlmRequest, *,
                      ok: bool, prompt_tokens: int, completion_tokens: int,
                      token_source: str, latency_ms: int, error: str = "") -> None:
        """把一次调用的用量交给落账出口。

        **计量失败绝不影响主链路** —— 这里是旁路。异常在此吞掉并 warn：
        一次成功的问答不该因为"记账"而变成 500。
        """
        try:
            self.usage_sink({
                "tenant_id": request.tenant_id or "default",
                "role": (getattr(engine, "role", "") or request.role or ""),
                "model": (getattr(engine, "model", "") or (engine.name if engine else "")),
                "provider": (getattr(engine, "provider", "") or ""),
                "token_source": token_source,
                "prompt_tokens": int(prompt_tokens),
                "completion_tokens": int(completion_tokens),
                "latency_ms": int(latency_ms),
                "ok": bool(ok),
                "error": error,
            })
        except Exception as exc:  # noqa: BLE001 - 旁路计量不得冒泡
            logger.warning("llm usage sink failed: %s: %s", type(exc).__name__, exc)

    def generate(self, request: LlmRequest, deadline_ms: int | None = None) -> LlmResponse:
        """按请求层级生成；`deadline_ms` 为**整次级联共享**的总预算。

        有 deadline 时，每一级、每一次重试的可用超时都收进「剩余预算」内
        （`min(级超时, 剩余)`）；预算耗尽的瞬间**不再发起新的调用**，
        直接抛 `LlmUnavailable` 让上层走降级链路 —— 这样最坏耗时是**一个常量**，
        而不是随层级/重试数膨胀的乘积。

        **角色优先**：`request.role` 命中前台配置时，先按该角色的引擎顺序尝试；
        全部失败后再落回按 `level` 的层级级联。两条路共用同一个预算与重试语义。
        """
        started = time.time()
        budget = Deadline(deadline_ms, operation="llm.generate") if deadline_ms else None
        prompt_tokens = estimate_tokens(request.system) + estimate_tokens(request.prompt)
        attempts = 0
        deadline_hit = False
        last_error = ""
        last_engine: LlmEngine | None = None
        for engine in self._candidates(request):
            last_engine = engine
            level_timeout = engine.effective_timeout(self.timeouts.get(engine.level, 8.0))
            for attempt in range(1, self.max_retries + 2):
                timeout: float | None = level_timeout
                if budget is not None:
                    timeout = budget.clamp(level_timeout)
                    if timeout is None or timeout <= 0:
                        # 预算已耗尽：**不再发起调用**。这不是「引擎不可用」，
                        # 而是「时间用完了」—— 两者必须区分，否则会把预算问题诊断成下游故障。
                        deadline_hit = True
                        budget.note_exhausted()
                        logger.warning(
                            "llm budget exhausted, stop cascade (engine=%s elapsed=%sms budget=%sms)",
                            engine.name, budget.elapsed_ms(), budget.budget_ms,
                        )
                        break
                attempts += 1
                # 每次尝试前清空供应商用量，避免重试时把上一次的残留值当成本次结果
                _clear_usage(engine)
                try:
                    text = engine.generate(engine.apply_defaults(request), timeout)
                    if not text:
                        raise ValueError("empty completion")
                    prompt_used, completion_used, token_source = _resolve_tokens(
                        engine, prompt_tokens, text
                    )
                    self.stats.calls += 1
                    self.stats.by_level[engine.level] = self.stats.by_level.get(engine.level, 0) + 1
                    self.stats.by_backend[engine.name] = self.stats.by_backend.get(engine.name, 0) + 1
                    self.stats.prompt_tokens += prompt_used
                    self.stats.completion_tokens += completion_used
                    self.stats.by_token_source[token_source] = (
                        self.stats.by_token_source.get(token_source, 0) + 1
                    )
                    degraded = engine.name == TemplateEngine.name
                    if degraded:
                        self.stats.degraded_calls += 1
                    self._record_usage(
                        engine, request, ok=True, prompt_tokens=prompt_used,
                        completion_tokens=completion_used, token_source=token_source,
                        latency_ms=int((time.time() - started) * 1000),
                    )
                    return LlmResponse(
                        text=text,
                        model=engine.name,
                        level=engine.level,
                        backend=engine.name,
                        degraded=degraded,
                        generator="template" if degraded else "model",
                        prompt_tokens=prompt_used,
                        completion_tokens=completion_used,
                        token_source=token_source,
                        latency_ms=int((time.time() - started) * 1000),
                        attempts=attempts,
                        role=engine.role or request.role,
                    )
                except Exception as exc:  # noqa: BLE001 - 超时/网络均需重试
                    last_error = f"{type(exc).__name__}: {exc}"
                    if isinstance(exc, (urllib.error.URLError, TimeoutError, OSError)) or "timeout" in str(exc).lower():
                        self.stats.timeouts += 1
                    if attempt <= self.max_retries:
                        self.stats.retries += 1
                        logger.warning("llm call failed (engine=%s attempt=%s): %s",
                                       engine.name, attempt, last_error)
                        continue
                    break
            if deadline_hit:
                break
        elapsed = int((time.time() - started) * 1000)
        # 失败也记账：只统计成功调用会让「一直在失败的重试」在看板上表现为"没有用量"，
        # 从而把故障掩盖成"没人用"。失败行按最后一个尝试过的引擎归属。
        self._record_usage(last_engine, request, ok=False, prompt_tokens=0, completion_tokens=0,
                           token_source=TOKEN_SOURCE_ESTIMATED, latency_ms=elapsed,
                           error=last_error)
        if deadline_hit:
            self.stats.deadline_exceeded += 1
            raise LlmUnavailable(
                f"llm total budget exhausted (budget={deadline_ms}ms, level={request.level}, "
                f"attempts={attempts}): {last_error or 'no attempt issued'}"
            )
        raise LlmUnavailable(
            f"no available llm engine (level={request.level}, role={request.role or '-'}): {last_error}"
        )


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


def _parse_usage(raw: Any) -> TokenUsage | None:
    """把 OpenAI 兼容响应里的 `usage` 转成 `TokenUsage`；拿不到**完整**用量返回 None。

    三条口径，都是刻意的：

    * **`usage` 缺失 ≠ 用了 0 个 token** —— 多数本地推理服务（vLLM/LM Studio 的
      部分版本）不回 `usage`。把缺失写成 0，看板会少算用量却不报任何异常。
      故返回 `None`，由上层落到 `estimated` 并**如实标注来源**。
    * **两个字段必须都在**才认作供应商实测值。只回 `prompt_tokens` 的响应
      是很常见的，此时若把实测值与估算值拼成一条记录并标 `provider`，
      就是"看起来精确、实则混合口径"—— 正是本仓反复要避免的那种失真。
    * **返回 None 而不是抛异常**：用量是旁路信息，不能因为它让主调用失败。
    """
    if not isinstance(raw, dict):
        return None
    prompt = _as_int(raw.get("prompt_tokens"))
    completion = _as_int(raw.get("completion_tokens"))
    if prompt is None or completion is None:
        return None
    return TokenUsage(prompt_tokens=prompt, completion_tokens=completion)


def _as_int(value: Any) -> int | None:
    if isinstance(value, bool) or value is None:
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _clear_usage(engine: LlmEngine) -> None:
    """清空引擎上一次回传的用量。

    直接赋实例属性（不依赖任何子类的 `__init__`）—— 引擎实现包含测试里的桩，
    它们未必调用基类构造，走属性赋值才对所有实现都成立。
    """
    engine.last_usage = None


def _default_usage_sink(payload: dict) -> Any:
    """默认计量出口：交给 `app.token_usage` 落账（lazy 导入，避免导入环）。"""
    from app import token_usage

    error = str(payload.pop("error", "") or "")
    if error:
        logger.info("llm call recorded as failure: %s", error[:200])
    return token_usage.record_usage(**payload)


def _resolve_tokens(engine: LlmEngine, estimated_prompt: int, text: str) -> tuple[int, int, str]:
    """决定本次调用对外申报的 token 数与**来源**。

    供应商自报值优先；拿不到就退回字符估算，并把 `estimated` 一路带到
    `LlmResponse.token_source` 与用量表 —— 让"这行数字准不准"可被下游判定。
    """
    usage = getattr(engine, "last_usage", None)
    if isinstance(usage, TokenUsage):
        return int(usage.prompt_tokens), int(usage.completion_tokens), TOKEN_SOURCE_PROVIDER
    return estimated_prompt, estimate_tokens(text), TOKEN_SOURCE_ESTIMATED


# 全局单例：`engines` 是**层级兜底**（TemplateEngine + 环境变量配置的 HTTP 引擎）。
# **角色引擎不在这里注册** —— 它们由 `main.py` 启动时从 `llm_model_config` 表装配
# （见 `reload_from_model_config`），改配置后调一次 reload 即可生效，无需重启进程。
LLM_GATEWAY = LlmGateway(engines=[TemplateEngine(), HttpLlmEngine(level=L2)])
