# -*- coding: utf-8 -*-
"""nlp-service HTTP 层错误语义测试（2026-09-18 新增）

背景：本服务此前**只有算法单测，没有任何 HTTP 层测试** —— 错误出口（未映射路由、
方法不支持、请求体校验、兜底 500）长期无覆盖。本轮统一错误信封时一并补上。

断言口径（与 Java 三服务 / wp-bff 对齐，见 docs/异常流程归纳.md §2）：
  - 路径不存在           → 404 AGENT_NOT_FOUND
  - 路径存在但方法不支持 → 405 AGENT_METHOD_NOT_ALLOWED（附 Allow 头）
  - 请求体不合法         → 400 AGENT_BAD_REQUEST（**不得出现 422**）
  - 未预期异常           → 500 AGENT_INTERNAL_ERROR（**不泄露内部消息**）
  - 所有错误体统一为 {code, message, details}，不再出现 FastAPI 默认的 {"detail": ...}
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from fastapi.testclient import TestClient  # noqa: E402

from app import main  # noqa: E402

ENVELOPE_KEYS = {"code", "message", "details"}


def _assert_envelope(body: dict) -> None:
    assert set(body.keys()) == ENVELOPE_KEYS, f"错误信封键应为 {ENVELOPE_KEYS}，实得 {set(body.keys())}"
    assert isinstance(body["message"], str) and body["message"]
    assert isinstance(body["details"], dict)


def test_unmapped_route_returns_404_not_found_envelope():
    with TestClient(main.app) as client:
        res = client.get("/api/nlp/definitely-not-a-route")
    assert res.status_code == 404
    body = res.json()
    _assert_envelope(body)
    assert body["code"] == "AGENT_NOT_FOUND"
    assert "detail" not in body, "FastAPI 默认的 detail 键不应再出现（全平台统一信封）"


def test_wrong_method_returns_405_with_allow_header():
    with TestClient(main.app) as client:
        res = client.get("/api/nlp/embed")  # 该路径只接受 POST
    assert res.status_code == 405
    body = res.json()
    _assert_envelope(body)
    assert body["code"] == "AGENT_METHOD_NOT_ALLOWED"
    assert "POST" in res.headers.get("allow", ""), "405 必须带 Allow 头，否则客户端无法自纠"


def test_validation_error_maps_to_400_with_field_details():
    # 注意：EmbedRequest.texts 有 default_factory=list，故 `{}` 是**合法**载荷
    # （由端点自身抛 400「texts must not be empty」）；要触发 pydantic 校验失败
    # 必须传类型不对的值。
    with TestClient(main.app) as client:
        res = client.post("/api/nlp/embed", json={"texts": "abc"})  # 应为数组
    assert res.status_code == 400, "校验失败必须是 400（不是 FastAPI 默认的 422），以与 Java/BFF 对齐"
    body = res.json()
    _assert_envelope(body)
    assert body["code"] == "AGENT_BAD_REQUEST"
    assert "texts" in body["details"], f"details 应给出字段级原因，实得 {body['details']}"
    assert "list" in body["details"]["texts"].lower()


def test_validation_error_reports_nested_field_path():
    with TestClient(main.app) as client:
        res = client.post("/api/nlp/embed", json={"texts": [1]})  # 元素应为字符串
    assert res.status_code == 400
    assert "texts.0" in res.json()["details"], "嵌套字段应给出带下标的路径"


def test_domain_error_keeps_400_and_message():
    with TestClient(main.app) as client:
        res = client.post("/api/nlp/embed", json={"texts": []})
    assert res.status_code == 400
    body = res.json()
    _assert_envelope(body)
    assert body["code"] == "AGENT_BAD_REQUEST"
    assert "empty" in body["message"].lower() or "空" in body["message"]


def test_empty_object_is_valid_payload_not_validation_error():
    """`{}` 合法（texts 有默认值）—— 400 来自端点业务校验，而非 422 校验失败。"""
    with TestClient(main.app) as client:
        res = client.post("/api/nlp/embed", json={})
    assert res.status_code == 400
    assert res.json()["message"] == "texts must not be empty"


def test_no_422_leaks_from_any_probe():
    """全局守卫：任何探测都不得返回 422（否则客户端仍需两套分支）。"""
    with TestClient(main.app) as client:
        probes = [
            client.post("/api/nlp/embed", json={}),
            client.post("/api/nlp/rerank", json={}),
            client.post("/api/nlp/intent", json={}),
            client.get("/api/nlp/definitely-not-a-route"),
            client.get("/api/nlp/embed"),
        ]
    assert all(res.status_code != 422 for res in probes), \
        f"出现了 422：{[res.status_code for res in probes]}"


def test_unhandled_exception_is_sanitized(monkeypatch):
    """兜底 500：只返回通用消息，绝不把内部异常文本（可能含路径/实现细节）回给客户端。"""
    def boom(_texts):
        raise TypeError("内部细节：/opt/models/bge-m3/weights.bin 不可读")

    monkeypatch.setattr(main.EMBEDDING_SERVICE, "encode", boom)
    with TestClient(main.app, raise_server_exceptions=False) as client:
        res = client.post("/api/nlp/embed", json={"texts": ["正常文本"]})
    assert res.status_code == 500
    body = res.json()
    _assert_envelope(body)
    assert body["code"] == "AGENT_INTERNAL_ERROR"
    assert body["message"] == "服务内部错误"
    assert "weights.bin" not in res.text, "内部异常消息不得泄露给客户端"


def test_agent_code_mapping_is_exhaustive_for_common_statuses():
    expected = {
        400: "AGENT_BAD_REQUEST",
        401: "AGENT_UNAUTHORIZED",
        403: "AGENT_FORBIDDEN",
        404: "AGENT_NOT_FOUND",
        405: "AGENT_METHOD_NOT_ALLOWED",
        409: "AGENT_CONFLICT",
        422: "AGENT_BAD_REQUEST",
        502: "AGENT_UPSTREAM_UNAVAILABLE",
        503: "AGENT_BUS_UNAVAILABLE",
        504: "AGENT_TIMEOUT",
    }
    for status, code in expected.items():
        assert main._agent_code(status) == code, f"HTTP {status} 应映射为 {code}"
    assert main._agent_code(500) == "AGENT_INTERNAL_ERROR"
    assert main._agent_code(504) == "AGENT_TIMEOUT"
    assert main._agent_code(599) == "AGENT_INTERNAL_ERROR", "未登记的 5xx 一律兜底为 AGENT_INTERNAL_ERROR"


def test_healthz_still_works_after_envelope_change():
    with TestClient(main.app) as client:
        res = client.get("/healthz")
    assert res.status_code == 200
    assert res.json()["status"] == "ok"
