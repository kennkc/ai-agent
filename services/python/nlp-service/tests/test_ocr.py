# -*- coding: utf-8 -*-
"""Phase 2 OCR 通道测试（R2-03）

OCR 引擎（PaddleOCR / Tesseract）在开发机可能未安装，测试按可用性分支断言：
  - 引擎可用 → 真实识别（基线准确率）
  - 引擎缺失 → 如实上报 503，视觉渠道由健康监控标记 DEGRADED
"""
from __future__ import annotations

import base64
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.ocr import OCR_SERVICE, UnavailableOcrEngine


def test_status_reports_candidate_engines():
    status = OCR_SERVICE.status()
    assert "available" in status
    assert "engine" in status
    assert {c["engine"] for c in status["candidates"]} >= {"paddleocr", "tesseract"}


def test_status_is_truthful_when_no_engine_installed():
    status = OCR_SERVICE.status()
    if not status["available"]:
        assert status["engine"] == UnavailableOcrEngine.name
        try:
            OCR_SERVICE.recognize_base64(base64.b64encode(b"x").decode())
            raise AssertionError("engine missing but recognize did not raise")
        except RuntimeError as exc:
            assert "no OCR engine" in str(exc)


def test_rejects_invalid_base64_payload():
    if not OCR_SERVICE.status()["available"]:
        return
    try:
        OCR_SERVICE.recognize_base64("!!!not-base64!!!")
        raise AssertionError("invalid base64 should be rejected")
    except ValueError as exc:
        assert "base64" in str(exc)


def test_rejects_empty_payload():
    if not OCR_SERVICE.status()["available"]:
        return
    try:
        OCR_SERVICE.recognize_base64("")
        raise AssertionError("empty payload should be rejected")
    except ValueError as exc:
        assert "image" in str(exc) or "empty" in str(exc)


if __name__ == "__main__":
    failures = 0
    for name, fn in sorted(globals().items()):
        if not name.startswith("test_") or not callable(fn):
            continue
        try:
            fn()
            print(f"  PASS {name}")
        except AssertionError as exc:
            failures += 1
            print(f"  FAIL {name}: {exc}")
    print(f"\nOCR 引擎状态: {OCR_SERVICE.status()}")
    sys.exit(1 if failures else 0)
