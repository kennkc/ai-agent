"""Prometheus endpoint and LLM metric smoke tests."""
from __future__ import annotations

import os
import sys

from fastapi.testclient import TestClient

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import main
from app.brain.llm_gateway import LlmGateway, LlmRequest, TemplateEngine


def test_metrics_endpoint_exposes_prometheus_text():
    with TestClient(main.app) as client:
        response = client.get("/metrics")
    assert response.status_code == 200
    assert "text/plain" in response.headers["content-type"]
    assert "python_gc_objects_collected_total" in response.text


def test_llm_gateway_records_degraded_business_metric():
    gateway = LlmGateway(engines=[TemplateEngine()], role_engines={})
    gateway.generate(LlmRequest(prompt="Question: metric", level="L2", role="generate"))
    with TestClient(main.app) as client:
        response = client.get("/metrics")
    assert response.status_code == 200
    assert "lifeform_llm_calls_total" in response.text
    assert 'outcome="degraded"' in response.text
    assert "lifeform_llm_latency_seconds" in response.text
