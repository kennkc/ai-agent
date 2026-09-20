#!/usr/bin/env python3
"""OpenRouter 非降级模型 E2E 验证（opt-in）。

安全约束：API Key 只从 OPENROUTER_API_KEY 读取，绝不写入文件、日志或报告。
运行：
  OPENROUTER_E2E=1 OPENROUTER_API_KEY=... python scripts/openrouter-e2e.py
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
DEFAULT_MODEL = "openrouter/free"


def _mask(key: str) -> str:
    return f"{key[:6]}...{key[-4:]}" if len(key) > 12 else "***"


def _call_openrouter(base_url: str, api_key: str, model: str, prompt: str, timeout: float) -> dict:
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": "你是连通性验证助手，只回复用户明确要求的文本。"},
            {"role": "user", "content": prompt},
        ],
        "max_tokens": 64,
        "temperature": 0,
    }
    request = urllib.request.Request(
        base_url.rstrip("/") + "/chat/completions",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
            "HTTP-Referer": "http://127.0.0.1:3001",
            "X-Title": "Agent-Lifeform OpenRouter E2E",
        },
        method="POST",
    )
    started = time.perf_counter()
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = json.loads(response.read().decode("utf-8"))
    latency_ms = round((time.perf_counter() - started) * 1000, 2)
    choices = body.get("choices") or []
    content = str((choices[0].get("message") or {}).get("content", "")) if choices else ""
    if not content.strip():
        raise RuntimeError("OpenRouter 返回成功但 choices[0].message.content 为空")
    return {
        "model": body.get("model") or model,
        "content": content,
        "latency_ms": latency_ms,
        "expected_token_present": "OPENROUTER_OK" in content,
        "usage": body.get("usage") or {},
    }


def _call_gateway(base_url: str, api_key: str, model: str, prompt: str, timeout: float) -> dict:
    repo_root = Path(__file__).resolve().parents[1]
    nlp_root = repo_root / "services" / "python" / "nlp-service"
    sys.path.insert(0, str(nlp_root))
    from app.brain.llm_gateway import HttpLlmEngine, LlmGateway, LlmRequest  # noqa: PLC0415

    engine = HttpLlmEngine(
        base_url=base_url,
        api_key=api_key,
        model=model,
        level="L2",
        role="generate",
        name="openrouter-e2e",
        provider="openrouter",
        max_tokens=64,
        temperature=0.0,
        timeout_ms=int(timeout * 1000),
    )
    if not engine.available():
        raise RuntimeError("HttpLlmEngine.available() == false")
    gateway = LlmGateway(engines=[engine])
    response = gateway.generate(
        LlmRequest(prompt=prompt, level="L2", role="generate", max_tokens=64, temperature=0.0),
        deadline_ms=int(timeout * 1000),
    )
    if response.degraded:
        raise RuntimeError(f"LlmGateway 返回 degraded=true: {response}")
    if response.generator != "model":
        raise RuntimeError(f"LlmGateway 未使用真实模型: generator={response.generator}")
    return {
        "model": response.model,
        "content": response.text,
        "latency_ms": response.latency_ms,
        "degraded": response.degraded,
        "generator": response.generator,
        "prompt_tokens": response.prompt_tokens,
        "completion_tokens": response.completion_tokens,
        "expected_token_present": "OPENROUTER_OK" in response.text,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="OpenRouter 非降级模型验证")
    parser.add_argument("--base-url", default=os.getenv("OPENROUTER_BASE_URL", DEFAULT_BASE_URL))
    parser.add_argument("--model", default=os.getenv("OPENROUTER_MODEL", DEFAULT_MODEL))
    parser.add_argument("--timeout", type=float, default=float(os.getenv("OPENROUTER_TIMEOUT_SECONDS", "90")))
    parser.add_argument("--skip-gateway", action="store_true")
    parser.add_argument("--output", default="")
    args = parser.parse_args()

    if os.getenv("OPENROUTER_E2E") != "1":
        print("SKIP 外部模型 E2E 默认关闭；设置 OPENROUTER_E2E=1 后执行", file=sys.stderr)
        return 2
    api_key = os.getenv("OPENROUTER_API_KEY", "").strip()
    if not api_key:
        print("FAIL 缺少 OPENROUTER_API_KEY", file=sys.stderr)
        return 2

    prompt = "请只回复：OPENROUTER_OK"
    report: dict = {
        "status": "pass",
        "base_url": args.base_url,
        "model": args.model,
        "api_key_hint": _mask(api_key),
        "checks": {},
    }
    try:
        report["checks"]["direct"] = _call_openrouter(args.base_url, api_key, args.model, prompt, args.timeout)
        if not args.skip_gateway:
            report["checks"]["gateway"] = _call_gateway(args.base_url, api_key, args.model, prompt, args.timeout)
        if any(not item.get("expected_token_present", False) for item in report["checks"].values()):
            report["status"] = "pass_with_warning"
            report["warning"] = "非降级链路成功，但 openrouter/free 路由到的上游模型未返回预期 OPENROUTER_OK；评测时应固定具体上游模型。"
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, RuntimeError) as exc:
        report["status"] = "fail"
        report["error"] = f"{type(exc).__name__}: {exc}"
        print(json.dumps(report, ensure_ascii=False, indent=2), file=sys.stderr)
        return 1

    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(text, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())