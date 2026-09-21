#!/usr/bin/env python3
"""Validate that all expected Lifeform Prometheus targets are UP."""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.request
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

EXPECTED = {
    "gateway-service", "session-manager", "sense-service", "body-service",
    "tool-executor", "collab-bus", "nlp-service", "wp-bff",
}


def fetch(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=5) as response:
        return json.loads(response.read().decode("utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(description="Check Prometheus Lifeform target health")
    parser.add_argument("--prometheus-url", default="http://127.0.0.1:9090")
    parser.add_argument("--timeout-seconds", type=float, default=90)
    parser.add_argument("--output", default="")
    args = parser.parse_args()

    deadline = time.time() + args.timeout_seconds
    targets = []
    while time.time() < deadline:
        payload = fetch(args.prometheus_url.rstrip("/") + "/api/v1/targets?state=any")
        targets = payload.get("data", {}).get("activeTargets", [])
        health = {item.get("labels", {}).get("job"): item.get("health") for item in targets}
        if all(health.get(job) == "up" for job in EXPECTED):
            break
        time.sleep(2)

    health = {item.get("labels", {}).get("job"): item.get("health") for item in targets}
    down = [{"job": job, "health": health.get(job), "lastError": next((t.get("lastError") for t in targets if t.get("labels", {}).get("job") == job), "")}
            for job in sorted(EXPECTED) if health.get(job) != "up"]
    report = {"status": "pass" if not down else "fail", "expected_jobs": sorted(EXPECTED), "down": down}
    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(text, encoding="utf-8")
    return 0 if report["status"] == "pass" else 1


if __name__ == "__main__":
    raise SystemExit(main())