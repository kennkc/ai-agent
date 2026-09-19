"""R5-06 Function Calling 对接 —— LLM 工具决策 → 工具执行 → 结果回填。

## 这条链路做什么

需求文档 §5 时序图的 Python 侧落点：

```
planner(Py) --工具决策--> LLM
                  <-- 工具选择 --
        --execute--> tool-executor(Java) --> 沙箱/白名单/审计
                  <-- 结果回填 --
        --最终生成--> LLM --> 回答
```

## 诚实边界（务必先读）

1. **决策器是规则实现，不是 LLM** —— 本阶段真实 LLM 引擎仍未接入（DEBT-015/016 沿用），
   因此 `decide()` 返回 `decider="rule"`。它做的是"从问题里拆出可执行的工具调用序列"，
   能力边界与 Phase 4 的 `SimplePlanner` 同源。**不谎称是模型决策**，也**不伪造工具结果**：
   工具没跑通就如实把失败回填，绝不编一个答案。

2. **执行一律走 tool-executor**，Python 侧**自己不做白名单/沙箱/审计**——
   那些是四肢层（Java）的职责。这保证"工具能不能跑"只有一份真相（危险清单不重复维护）。

3. **组合任务**（R5-06 验收："计算+查询"组合任务完成）由 `run()` 编排：
   拆解 → 逐个执行 → 合并 → 生成统一回答。任一步失败都在结果里显式体现。
"""
from __future__ import annotations

import json
import logging
import os
import re
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any

logger = logging.getLogger("nlp-service.function-calling")

DEFAULT_TOOL_EXECUTOR_URL = os.environ.get("TOOL_EXECUTOR_URL", "http://127.0.0.1:8084")

# 城市 → 经纬度（组合任务演示用；未收录的城市如实回落为"未收录"）
CITY_COORDINATES: dict[str, tuple[float, float]] = {
    "北京": (39.9042, 116.4074),
    "上海": (31.2304, 121.4737),
    "广州": (23.1291, 113.2644),
    "深圳": (22.5431, 114.0579),
    "杭州": (30.2741, 120.1551),
    "成都": (30.5728, 104.0668),
    "武汉": (30.5928, 114.3055),
    "西安": (34.3416, 108.9398),
    "南京": (32.0603, 118.7969),
    "重庆": (29.5630, 106.5516),
}

# 全角/排版符号归一：中文输入里"128×17"用的是乘号 × 而不是 *，
# 不做这一步，组合任务 Demo 的原句会**抽不出算式**（实测踩过）。
_SYMBOL_MAP = str.maketrans({
    "×": "*", "✕": "*", "✖": "*", "·": "*", "÷": "/",
    "＋": "+", "－": "-", "−": "-", "（": "(", "）": ")",
    "％": "%", "＾": "^", "，": ",", "　": " ",
})

# 算术表达式：只认数字与运算符，避免把整句话当表达式
_MATH_TOKEN = re.compile(r"[-+]?\d[\d\s.]*(\s*[-+*/%^()]\s*\d[\d\s.]*)+")
_WEATHER_HINT = re.compile(r"天气|气温|温度|weather|下雨|晴|多云")
_CITY_HINT = re.compile("|".join(CITY_COORDINATES.keys()))
_HTTP_URL = re.compile(r"https?://[^\s\u4e00-\u9fff\"'）)]+")


@dataclass
class ToolCall:
    """一次待执行的工具调用（决策产物）"""

    tool: str
    arguments: dict[str, Any]
    reason: str = ""
    label: str = ""

    def to_dict(self) -> dict:
        return {"tool": self.tool, "arguments": dict(self.arguments),
                "reason": self.reason, "label": self.label}


@dataclass
class ToolExecution:
    """一次工具执行的结果（含平台侧横切信息）"""

    tool: str
    arguments: dict[str, Any]
    success: bool
    output: str | None = None
    error_code: str | None = None
    error_message: str | None = None
    latency_ms: int = 0
    call_id: str | None = None
    audit_id: str | None = None
    sandboxed: bool = False
    sandbox_backend: str | None = None
    degraded: bool = False

    def to_dict(self) -> dict:
        return {
            "tool": self.tool,
            "arguments": dict(self.arguments),
            "success": self.success,
            "output": self.output,
            "error_code": self.error_code,
            "error_message": self.error_message,
            "latency_ms": self.latency_ms,
            "call_id": self.call_id,
            "audit_id": self.audit_id,
            "sandboxed": self.sandboxed,
            "sandbox_backend": self.sandbox_backend,
            "degraded": self.degraded,
        }


@dataclass
class Plan:
    """工具决策结果"""

    question: str
    calls: list[ToolCall] = field(default_factory=list)
    decider: str = "rule"
    note: str = ""

    def to_dict(self) -> dict:
        return {
            "question": self.question,
            "decider": self.decider,
            "requires_tools": bool(self.calls),
            "calls": [call.to_dict() for call in self.calls],
            "note": self.note,
        }


class FunctionCallingAdapter:
    """LLM 工具决策 ↔ tool-executor 执行 ↔ 结果回填。"""

    def __init__(self, base_url: str | None = None, timeout_seconds: float = 20.0):
        self.base_url = (base_url or DEFAULT_TOOL_EXECUTOR_URL).rstrip("/")
        self.timeout_seconds = timeout_seconds
        # 不走系统代理：本机环境变量里常带 HTTP_PROXY，会把 127.0.0.1 也代理走
        self._opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    # ─────────── 决策 ───────────
    def decide(self, question: str) -> Plan:
        """从自然语言里拆出工具调用序列（**规则实现**，如实标注 decider=rule）。"""
        text = (question or "").strip()
        normalized = text.translate(_SYMBOL_MAP)   # 全角/排版符号归一后再抽取
        calls: list[ToolCall] = []

        math = self._extract_math(normalized)
        if math:
            calls.append(ToolCall(tool="calculator", arguments={"expr": math},
                                  reason="问题含可计算的算术表达式", label=f"计算 {math}"))

        if _WEATHER_HINT.search(text):
            city = self._extract_city(text)
            if city:
                latitude, longitude = CITY_COORDINATES[city]
                url = (f"https://api.open-meteo.com/v1/forecast?latitude={latitude}"
                       f"&longitude={longitude}&current_weather=true")
                calls.append(ToolCall(tool="http",
                                      arguments={"url": url, "method": "GET"},
                                      reason=f"问题在问天气，且识别到城市「{city}」",
                                      label=f"查询{city}天气"))

        url = self._extract_url(text)
        if url and not any(call.tool == "http" for call in calls):
            calls.append(ToolCall(tool="http", arguments={"url": url, "method": "GET"},
                                  reason="问题含显式 URL", label=f"请求 {url}"))

        note = ""
        if not calls:
            note = "未识别出需要工具的动作——按普通问答处理（本阶段不伪造工具调用）"
        elif _WEATHER_HINT.search(text):
            note = "天气查询走 HTTP 工具（域名白名单 api.open-meteo.com，免密钥）"
        return Plan(question=question, calls=calls, decider="rule", note=note)

    # ─────────── 执行 ───────────
    def execute(self, call: ToolCall, tenant_id: str = "default") -> ToolExecution:
        payload = json.dumps({"tool_name": call.tool, "arguments": call.arguments},
                             ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            f"{self.base_url}/api/tool/execute",
            data=payload,
            headers={"Content-Type": "application/json",
                     "X-Tenant-Id": tenant_id,
                     "X-Requested-By": "nlp-function-calling"},
            method="POST")
        started = time.time()
        try:
            with self._opener.open(request, timeout=self.timeout_seconds) as response:
                body = json.loads(response.read().decode("utf-8"))
            return ToolExecution(
                tool=call.tool, arguments=call.arguments, success=bool(body.get("success")),
                output=body.get("output"), error_code=body.get("error_code"),
                error_message=body.get("error_message"),
                latency_ms=int(body.get("latency_ms") or int((time.time() - started) * 1000)),
                call_id=body.get("call_id"), audit_id=body.get("audit_id"),
                sandboxed=bool(body.get("sandboxed")), sandbox_backend=body.get("sandbox_backend"),
                degraded=bool(body.get("degraded")))
        except urllib.error.HTTPError as error:
            # 统一错误信封：{code, message, details}（tool-executor 的 GlobalExceptionHandler 产出）
            detail = self._read_error(error)
            return ToolExecution(
                tool=call.tool, arguments=call.arguments, success=False,
                error_code=detail.get("code") or f"HTTP_{error.code}",
                error_message=detail.get("message") or str(error.reason),
                latency_ms=int((time.time() - started) * 1000),
                call_id=(detail.get("details") or {}).get("call_id"),
                audit_id=(detail.get("details") or {}).get("audit_id"))
        except Exception as error:  # 连接失败：**如实上报不可用**，不冒充成功
            logger.warning("tool-executor 不可达：%s", error)
            return ToolExecution(
                tool=call.tool, arguments=call.arguments, success=False,
                error_code="AGENT_UPSTREAM_UNAVAILABLE",
                error_message=f"tool-executor 不可达（{self.base_url}）：{error}",
                latency_ms=int((time.time() - started) * 1000))

    @staticmethod
    def _read_error(error: urllib.error.HTTPError) -> dict:
        try:
            return json.loads(error.read().decode("utf-8"))
        except Exception:
            return {}

    # ─────────── 组合任务（R5-06 验收） ───────────
    def run(self, question: str, tenant_id: str = "default") -> dict:
        """决策 → 执行 → 合并 → 生成回答。任一工具失败都在结果里显式体现。"""
        plan = self.decide(question)
        executions = [self.execute(call, tenant_id=tenant_id) for call in plan.calls]
        answer, answer_kind = self._compose(question, plan, executions)
        return {
            "question": question,
            "planner": plan.decider,
            "tool_calls": [call.to_dict() for call in plan.calls],
            "tool_results": [execution.to_dict() for execution in executions],
            "tool_count": len(executions),
            "succeeded": sum(1 for execution in executions if execution.success),
            "failed": sum(1 for execution in executions if not execution.success),
            "answer": answer,
            "answer_kind": answer_kind,
            "degraded": any(execution.degraded for execution in executions),
            "note": plan.note,
        }

    def _compose(self, question: str, plan: Plan, executions: list[ToolExecution]) -> tuple[str, str]:
        """把工具结果合并成回答。**全部失败时明确说失败**，绝不编造。"""
        if not executions:
            return ("这个问题不需要调用工具——按普通知识问答处理（本阶段工具链未介入）。",
                    "no_tool")
        if all(not execution.success for execution in executions):
            reasons = "；".join(f"{e.tool}: {e.error_message or e.error_code}" for e in executions)
            return (f"工具调用未能完成。失败原因：{reasons}", "tool_failed")

        parts: list[str] = []
        for execution in executions:
            if execution.success:
                parts.append(self._describe_success(execution))
            else:
                parts.append(f"{execution.tool} 未成功（{execution.error_message or execution.error_code}）")
        suffix = ""
        if any(execution.degraded for execution in executions):
            suffix = "（注意：本次代码执行落在降级后端，不是真隔离沙箱）"
        return ("；".join(parts) + suffix, "tool_composed")

    @staticmethod
    def _describe_success(execution: ToolExecution) -> str:
        if execution.tool == "calculator":
            return f"计算结果 {execution.output}"
        if execution.tool == "http":
            weather = _parse_open_meteo(execution.output)
            if weather:
                return (f"当前气温 {weather['temperature']}℃，风速 {weather['windspeed']} km/h"
                        f"，风向 {weather['winddirection']}°")
            return f"HTTP 返回（前 120 字）：{(execution.output or '')[:120]}"
        return f"{execution.tool} 输出：{(execution.output or '')[:200]}"

    # ─────────── 抽取 ───────────
    @staticmethod
    def _extract_math(text: str) -> str | None:
        match = _MATH_TOKEN.search(text)
        if not match:
            return None
        expr = match.group().strip()
        # 至少两个操作数才算是"算式"，避免把"2026年"这种年份当计算
        if not re.search(r"\d\s*[-+*/%^]\s*\d", expr):
            return None
        return expr

    @staticmethod
    def _extract_city(text: str) -> str | None:
        match = _CITY_HINT.search(text)
        return match.group() if match else None

    @staticmethod
    def _extract_url(text: str) -> str | None:
        match = _HTTP_URL.search(text)
        return match.group().rstrip(".,;") if match else None

    # ─────────── 状态 ───────────
    def specs(self) -> dict:
        """返回供 LLM 使用的工具描述（与 tool-executor 注册表同源：运行时拉取，不硬编码）。"""
        try:
            with self._opener.open(f"{self.base_url}/api/tool/list", timeout=8) as response:
                payload = json.loads(response.read().decode("utf-8"))
            return {"source": "tool-executor", "available": True,
                    "total": payload.get("total"), "tools": payload.get("tools", [])}
        except Exception as error:
            logger.warning("拉取工具注册表失败：%s", error)
            return {"source": "tool-executor", "available": False, "total": 0, "tools": [],
                    "error": str(error)}

    def health(self) -> dict:
        try:
            with self._opener.open(f"{self.base_url}/api/tool/health", timeout=5) as response:
                payload = json.loads(response.read().decode("utf-8"))
            return {"tool_executor": self.base_url, "available": True,
                    "tools": payload.get("tools"),
                    "sandbox": (payload.get("sandbox") or {}).get("active_backend"),
                    "sandbox_degraded": (payload.get("sandbox") or {}).get("degraded"),
                    "audit_backend": (payload.get("audit") or {}).get("backend"),
                    "decider": "rule"}
        except Exception as error:
            return {"tool_executor": self.base_url, "available": False, "error": str(error),
                    "decider": "rule"}


def _parse_open_meteo(body: str | None) -> dict | None:
    """解析 open-meteo 的 current_weather；不是该结构就返回 None（不硬猜）。"""
    if not body:
        return None
    try:
        payload = json.loads(body)
    except Exception:
        return None
    weather = payload.get("current_weather") if isinstance(payload, dict) else None
    if not isinstance(weather, dict):
        return None
    return {
        "temperature": weather.get("temperature"),
        "windspeed": weather.get("windspeed"),
        "winddirection": weather.get("winddirection"),
    }


FUNCTION_CALLING = FunctionCallingAdapter()