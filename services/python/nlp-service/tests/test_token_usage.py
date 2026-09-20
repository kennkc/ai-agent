# -*- coding: utf-8 -*-
"""Token 用量计量测试（2026-09-20 新增，WB-10 「Token 用量看板」）。

覆盖三个**真实缺口**的守卫：

  1. **供应商 usage 被丢弃** —— 修复前 `HttpLlmEngine` 只取 `choices[0].message.content`，
     供应商自报的 `usage` 直接丢，全链路一律退回字符估算。
  2. **重试残留** —— 用量存在引擎实例属性上，若不在每次尝试前清空，
     一次失败的尝试会把上一次的用量"借给"本次成功的结果。
  3. **口径混算** —— `provider`（真实值）与 `estimated`（字符估算，DEBT-016）
     必须分开累计；合并之后"这周的用量准不准"就答不上来。

测试不依赖真实 PostgreSQL（由同目录 `conftest.py` 钉死 `BRAIN_PG_ENABLED=false`），
走 `token_usage` 的进程内降级路径。
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app import main, token_usage  # noqa: E402
from app.brain.llm_gateway import (  # noqa: E402
    TOKEN_SOURCE_ESTIMATED,
    TOKEN_SOURCE_PROVIDER,
    LlmGateway,
    LlmRequest,
    LlmUnavailable,
    TemplateEngine,
    TokenUsage,
    _parse_usage,
)

ENVELOPE_KEYS = {"code", "message", "details"}


@pytest.fixture(autouse=True)
def _fresh_store():
    """每个用例一份干净的进程内账本，避免用例之间互相看到对方的计数。"""
    token_usage.reset_for_test()
    yield
    token_usage.reset_for_test()


def _request(**over) -> LlmRequest:
    data = {"prompt": "问题：A 股今日如何？", "level": "L1", "role": "generate"}
    data.update(over)
    return LlmRequest(**data)


def _sink_collector() -> tuple[list[dict], object]:
    captured: list[dict] = []
    return captured, (lambda payload: captured.append(dict(payload)))


# ─────────── 1. 供应商 usage 解析 ───────────


class _UsageEngine(TemplateEngine):
    """成功返回并**回传供应商实测用量**的引擎（模拟 OpenAI 兼容响应）。"""

    name = "http:provider-usage"
    level = "L1"
    model = "provider-usage"
    provider = "deepseek"
    role = "generate"

    def generate(self, request, timeout):
        self.last_usage = TokenUsage(prompt_tokens=11, completion_tokens=7)
        return "供应商回答"


class _SilentEngine(TemplateEngine):
    """成功返回但**不回传用量**的引擎（多数本地推理服务就是这样）。"""

    name = "http:silent"
    level = "L1"
    model = "silent"
    role = "generate"

    def generate(self, request, timeout):
        return "本地回答"


def test_provider_usage_wins_over_estimation():
    gateway = LlmGateway(engines=[_UsageEngine()])
    response = gateway.generate(_request())
    assert (response.prompt_tokens, response.completion_tokens) == (11, 7)
    assert response.token_source == TOKEN_SOURCE_PROVIDER
    assert gateway.stats.by_token_source == {TOKEN_SOURCE_PROVIDER: 1}


def test_missing_usage_falls_back_to_estimation_and_says_so():
    gateway = LlmGateway(engines=[_SilentEngine()])
    response = gateway.generate(_request())
    assert response.token_source == TOKEN_SOURCE_ESTIMATED, (
        "没有 usage 时必须标 estimated —— 把估算值标成 provider 会让看板看起来精确、实则系统性偏差"
    )
    assert response.prompt_tokens > 0, "估算路径仍要给出数字，但来源必须如实"
    assert gateway.stats.by_token_source == {TOKEN_SOURCE_ESTIMATED: 1}


class _StaleThenSilent(TemplateEngine):
    """第一次尝试失败但**已写入** last_usage；第二次成功且不写。

    这正是「重试残留」的现场：若网关不在每次尝试前清空用量，
    第二次成功的结果会读到第一次的残留值，并被标成 `provider`。
    """

    name = "http:stale"
    level = "L1"
    model = "stale"
    role = "generate"

    def __init__(self):
        self.calls = 0

    def generate(self, request, timeout):
        self.calls += 1
        if self.calls == 1:
            self.last_usage = TokenUsage(prompt_tokens=999, completion_tokens=999)
            raise TimeoutError("first attempt timed out")
        return "第二次成功"


def test_retry_does_not_inherit_previous_attempt_usage():
    gateway = LlmGateway(engines=[_StaleThenSilent()], max_retries=1)
    response = gateway.generate(_request())
    assert response.attempts == 2
    assert response.token_source == TOKEN_SOURCE_ESTIMATED, (
        "重试成功后不得沿用上一次尝试写入的用量 —— 那会把失败调用的用量记到成功调用上"
    )
    assert response.prompt_tokens != 999 and response.completion_tokens != 999


def test_parse_usage_requires_both_fields():
    assert _parse_usage({"prompt_tokens": 3, "completion_tokens": 4}) == TokenUsage(3, 4)
    # 只回一个字段：**拒绝**采信。把实测值与估算值拼成一条并标 provider，
    # 就是"看起来精确、实则混合口径"。
    assert _parse_usage({"prompt_tokens": 3}) is None
    assert _parse_usage({"completion_tokens": 4}) is None
    assert _parse_usage(None) is None
    assert _parse_usage({"prompt_tokens": "x", "completion_tokens": 4}) is None
    # usage 缺失 ≠ 用了 0 token：必须返回 None，由上层降级并标注来源
    assert _parse_usage({}) is None


# ─────────── 2. 落账出口 ───────────


def test_gateway_sink_receives_role_model_provider_and_tenant():
    captured, sink = _sink_collector()
    gateway = LlmGateway(engines=[_UsageEngine()], usage_sink=sink)
    gateway.generate(_request(tenant_id="acme"))
    assert len(captured) == 1
    payload = captured[0]
    assert payload["tenant_id"] == "acme", "计量必须带租户，否则多租户看板是假维度"
    assert payload["role"] == "generate"
    assert payload["model"] == "provider-usage"
    assert payload["provider"] == "deepseek"
    assert payload["token_source"] == TOKEN_SOURCE_PROVIDER
    assert payload["ok"] is True


def test_total_failure_is_recorded_not_silently_absent():
    """全部引擎不可用时也要落一条失败记录。

    只统计成功调用会让「一直在失败的重试」在看板上表现为"没有用量" ——
    把故障掩盖成"没人用"，是最难被发现的一类失真。
    """
    captured, sink = _sink_collector()

    class _Dead(TemplateEngine):
        name = "http:dead"
        level = "L1"
        model = "dead"
        role = "generate"

        def available(self):
            return True

        def generate(self, request, timeout):
            raise OSError("connection refused")

    gateway = LlmGateway(engines=[_Dead()], max_retries=0, usage_sink=sink)
    with pytest.raises(LlmUnavailable):
        gateway.generate(_request())
    assert len(captured) == 1
    assert captured[0]["ok"] is False
    assert captured[0]["role"] == "generate"


def test_sink_failure_never_breaks_generation():
    """旁路计量抛异常时，主调用必须照常成功。"""
    def _boom(payload):
        raise RuntimeError("metering backend down")

    gateway = LlmGateway(engines=[_UsageEngine()], usage_sink=_boom)
    response = gateway.generate(_request())
    assert response.text == "供应商回答"


# ─────────── 3. 聚合与口径 ───────────


def test_record_and_summary_separate_token_sources():
    token_usage.record_usage(tenant_id="t1", role="generate", model="m-a", provider="deepseek",
                             token_source=TOKEN_SOURCE_PROVIDER,
                             prompt_tokens=100, completion_tokens=40, latency_ms=120)
    token_usage.record_usage(tenant_id="t1", role="generate", model="m-a", provider="deepseek",
                             token_source=TOKEN_SOURCE_PROVIDER,
                             prompt_tokens=50, completion_tokens=10, latency_ms=80)
    token_usage.record_usage(tenant_id="t1", role="generate", model="local", provider="local",
                             token_source=TOKEN_SOURCE_ESTIMATED,
                             prompt_tokens=30, completion_tokens=6, latency_ms=60)
    token_usage.record_usage(tenant_id="t1", role="generate", model="local", provider="local",
                             token_source=TOKEN_SOURCE_ESTIMATED, ok=False)

    summary = token_usage.usage_summary("t1", days=7)
    assert summary["totals"]["calls"] == 3
    assert summary["totals"]["failures"] == 1
    assert summary["totals"]["tokens"] == (100 + 40 + 50 + 10 + 30 + 6)
    # 同角色下按模型分开 —— 看板要能回答"哪个模型吃掉的预算最多"
    assert set(summary["by_model"]) == {"m-a", "local"}
    assert summary["by_token_source"] == {TOKEN_SOURCE_PROVIDER: 2, TOKEN_SOURCE_ESTIMATED: 2}
    # 估算占比 = 4 次尝试（含失败）里的 2 次
    assert summary["estimated_share"] == 0.5
    assert summary["totals"]["attempts"] == 4
    assert summary["totals"]["success_rate"] == 0.75
    assert len(summary["daily"]) == 1, "同一天的记录应聚合到同一个日桶"
    assert summary["daily"][0]["calls"] == 3


def test_summary_is_tenant_scoped():
    token_usage.record_usage(tenant_id="t1", role="generate", model="m")
    token_usage.record_usage(tenant_id="t2", role="generate", model="m",
                             token_source=TOKEN_SOURCE_ESTIMATED)
    assert token_usage.usage_summary("t1")["totals"]["attempts"] == 1
    assert token_usage.usage_summary("t2")["totals"]["attempts"] == 1
    assert token_usage.usage_summary("t3")["totals"]["attempts"] == 0
    assert token_usage.usage_summary("t3")["estimated_share"] == 0.0
    # 空窗口不得报 success_rate=0（"一次都没有"与"全失败"是两件事）
    assert token_usage.usage_summary("t3")["totals"]["success_rate"] is None


def test_degraded_storage_is_declared():
    """进程内降级必须**在响应里说出来** —— 否则降级期的用量会被当成完整用量。"""
    token_usage.record_usage(tenant_id="t1", role="generate", model="m")
    storage = token_usage.usage_summary("t1")["storage"]
    assert storage["degraded"] is True
    assert "不计入" in storage["note"]


def test_record_usage_never_raises(monkeypatch):
    """计量是旁路：任何异常都不得冒泡到主链路。"""
    monkeypatch.setattr(token_usage, "_row", lambda **kwargs: (_ for _ in ()).throw(RuntimeError("boom")))
    assert token_usage.record_usage(tenant_id="t1") is False


# ─────────── 4. 端点 ───────────


def test_usage_endpoint_shapes_for_dashboard():
    token_usage.record_usage(tenant_id="default", role="generate", model="m-a",
                             provider="deepseek", token_source=TOKEN_SOURCE_PROVIDER,
                             prompt_tokens=20, completion_tokens=8, latency_ms=90)
    client = TestClient(main.app)
    res = client.get("/api/nlp/models/usage?days=3")
    assert res.status_code == 200
    body = res.json()
    assert body["window"]["days"] == 3
    assert body["totals"]["tokens"] == 28
    assert body["by_role"]["generate"]["calls"] == 1
    assert body["by_token_source"][TOKEN_SOURCE_PROVIDER] == 1
    assert body["estimated_share"] == 0.0


def test_usage_endpoint_clamps_window():
    client = TestClient(main.app)
    assert client.get("/api/nlp/models/usage?days=0").json()["window"]["days"] == 1
    assert client.get("/api/nlp/models/usage?days=9999").json()["window"]["days"] == 90
    assert client.get("/api/nlp/models/usage").json()["window"]["days"] == 7
