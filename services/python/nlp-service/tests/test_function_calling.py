"""R5-06 Function Calling 对接测试。

覆盖三件事：
1. `decide()` 的**决策正确性**（算术表达式 / 城市天气 / 显式 URL / 不该调工具的场景）；
2. `execute()` 的**失败可见性**（tool-executor 不可达时必须如实上报，不许伪造成功）；
3. `run()` 的**组合任务**（"计算+查询"→ 回填 → 合并回答）。

刻意不连真实 tool-executor：HTTP 层真联调由端到端脚本负责，单测只证"决策与失败语义"。
"""
from __future__ import annotations

import json

from app.tools.function_calling import (
    CITY_COORDINATES,
    FunctionCallingAdapter,
    ToolCall,
    ToolExecution,
    _parse_open_meteo,
)

UNREACHABLE = "http://127.0.0.1:1"


def adapter() -> FunctionCallingAdapter:
    return FunctionCallingAdapter(base_url=UNREACHABLE, timeout_seconds=2)


# ─────────── 决策 ───────────
def test_decide_extracts_arithmetic():
    plan = adapter().decide("帮我算一下 128*17 等于多少")
    assert [call.tool for call in plan.calls] == ["calculator"]
    assert plan.calls[0].arguments["expr"] == "128*17"
    assert plan.decider == "rule"


def test_decide_handles_combined_task():
    """R5-06 验收的核心场景：一条话里同时要计算和查天气。"""
    plan = adapter().decide("帮我算 128×17，再查一下北京天气")
    tools = [call.tool for call in plan.calls]
    assert "calculator" in tools, "应先拆出计算调用"
    assert "http" in tools, "再拆出天气查询调用"

    http_call = next(call for call in plan.calls if call.tool == "http")
    latitude, longitude = CITY_COORDINATES["北京"]
    assert f"latitude={latitude}" in http_call.arguments["url"]
    assert "api.open-meteo.com" in http_call.arguments["url"], "天气查询必须落在白名单域名上"


def test_decide_recognizes_city_weather_only():
    plan = adapter().decide("上海今天的天气怎么样")
    assert [call.tool for call in plan.calls] == ["http"]
    assert "latitude=31.2304" in plan.calls[0].arguments["url"]


def test_decide_ignores_bare_number_like_year():
    """单个数（如"2026年"）不该被当成算式去算。"""
    plan = adapter().decide("2026年公司战略是什么")
    assert plan.calls == []


def test_decide_without_tool_reports_honestly():
    plan = adapter().decide("介绍一下你们的服务范围")
    assert plan.calls == []
    assert plan.to_dict()["requires_tools"] is False
    assert plan.note, "不调工具时要说明原因，不能静默返回空"


def test_decide_extracts_explicit_url():
    plan = adapter().decide("帮我请求一下 https://httpbin.org/get 看看返回什么")
    assert [call.tool for call in plan.calls] == ["http"]
    assert plan.calls[0].arguments["url"] == "https://httpbin.org/get"


# ─────────── 执行（失败可见性）───────────
def test_execute_reports_unreachable_executor_not_fake_success():
    result = adapter().execute(ToolCall(tool="calculator", arguments={"expr": "1+1"}))
    assert result.success is False
    assert result.error_code == "AGENT_UPSTREAM_UNAVAILABLE"
    assert "不可达" in result.error_message
    assert result.output is None, "失败时绝不能给出输出"


def test_run_with_unreachable_executor_composes_failure_answer():
    outcome = adapter().run("帮我算 128*17")
    assert outcome["tool_count"] == 1
    assert outcome["succeeded"] == 0
    assert outcome["failed"] == 1
    assert outcome["answer_kind"] == "tool_failed"
    assert "未能完成" in outcome["answer"], "失败必须写在回答里，而不是编一个答案"
    # 审计与调用 ID 应透传（HTTP 错误信封的 details）
    assert outcome["tool_results"][0]["error_code"] == "AGENT_UPSTREAM_UNAVAILABLE"


def test_run_without_tools_marks_no_tool():
    outcome = adapter().run("随便聊聊吧")
    assert outcome["tool_count"] == 0
    assert outcome["answer_kind"] == "no_tool"


# ─────────── 回答合成 ───────────
def test_compose_reports_calculator_result():
    synthesized, kind = adapter()._compose(
        "帮我算 128*17",
        type("P", (), {"calls": []})(),
        [ToolExecution(tool="calculator", arguments={"expr": "128*17"}, success=True, output="2176")])
    assert kind == "tool_composed"
    assert "2176" in synthesized


def test_compose_parses_weather_payload():
    body = json.dumps({"current_weather": {"temperature": 21.5, "windspeed": 9.4, "winddirection": 180}})
    synthesized, _ = adapter()._compose(
        "北京天气", type("P", (), {"calls": []})(),
        [ToolExecution(tool="http", arguments={}, success=True, output=body)])
    assert "21.5" in synthesized
    assert "9.4" in synthesized


def test_compose_flags_degraded_sandbox_in_answer():
    synthesized, _ = adapter()._compose(
        "跑段代码", type("P", (), {"calls": []})(),
        [ToolExecution(tool="code", arguments={}, success=True, output="hi",
                       sandboxed=True, sandbox_backend="process-restricted", degraded=True)])
    assert "降级后端" in synthesized, "沙箱降级必须在回答里可见"


def test_compose_partial_failure_keeps_success_part():
    synthesized, kind = adapter()._compose(
        "算 1+1 再查北京天气", type("P", (), {"calls": []})(),
        [ToolExecution(tool="calculator", arguments={}, success=True, output="2"),
         ToolExecution(tool="http", arguments={}, success=False,
                       error_code="AGENT_TOOL_ARGS_BLOCKED", error_message="域名不在白名单")])
    assert kind == "tool_composed"
    assert "2" in synthesized
    assert "未成功" in synthesized, "部分失败也要显式写出来"


# ─────────── 解析器 ───────────
def test_parse_open_meteo_rejects_unexpected_shape():
    assert _parse_open_meteo("not json") is None
    assert _parse_open_meteo(json.dumps({"foo": 1})) is None
    assert _parse_open_meteo(None) is None


def test_parse_open_meteo_reads_current_weather():
    parsed = _parse_open_meteo(json.dumps({"current_weather": {"temperature": 5, "windspeed": 1,
                                                               "winddirection": 90}}))
    assert parsed == {"temperature": 5, "windspeed": 1, "winddirection": 90}