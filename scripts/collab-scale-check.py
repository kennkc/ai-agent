#!/usr/bin/env python3
"""MC-01 scale smoke: create collaboration domains and measure consumer activation.

Requires a running collab-bus with PostgreSQL + NATS JetStream.
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


def call(base_url: str, method: str, path: str, body: dict | None = None, timeout: float = 15.0):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    request = urllib.request.Request(
        base_url.rstrip("/") + path,
        data=data,
        method=method,
        headers={"Content-Type": "application/json", "X-Tenant-Id": "default"},
    )
    started = time.perf_counter()
    with urllib.request.urlopen(request, timeout=timeout) as response:
        payload = json.loads(response.read().decode("utf-8"))
    return payload, (time.perf_counter() - started) * 1000


def main() -> int:
    parser = argparse.ArgumentParser(description="MC-01 collaboration domain scale smoke")
    parser.add_argument("--base-url", default="http://127.0.0.1:8085")
    parser.add_argument("--domains", type=int, default=25)
    parser.add_argument("--concurrency", type=int, default=8)
    parser.add_argument("--cleanup", action="store_true", help="Close created domains after verification")
    parser.add_argument("--output", default="")
    args = parser.parse_args()

    if args.domains < 1 or args.domains > 5000:
        print("domains must be in [1, 5000]", file=sys.stderr)
        return 2
    if args.concurrency < 1:
        print("concurrency must be >= 1", file=sys.stderr)
        return 2

    health, health_ms = call(args.base_url, "GET", "/api/collab/health")
    active_before = int(health.get("consumer", {}).get("active_domains") or 0)
    bus = health.get("bus", {})
    if not (bus.get("nats_connected") or bus.get("connected")):
        print(f"FAIL collab-bus NATS is not connected: {health}", file=sys.stderr)
        return 1

    created: list[str] = []
    create_latencies: list[float] = []

    def create(index: int):
        payload, latency = call(args.base_url, "POST", "/api/collab/domains", {"name": f"scale-{index:04d}"})
        return payload["data"]["domain_id"], latency

    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = [pool.submit(create, i) for i in range(args.domains)]
        for future in as_completed(futures):
            domain_id, latency = future.result()
            created.append(domain_id)
            create_latencies.append(latency)
    create_wall_ms = (time.perf_counter() - started) * 1000

    for domain_id in created:
        call(args.base_url, "POST", f"/api/collab/domains/{domain_id}/heartbeats", {
            "member_id": "agent-scale", "progress": 50, "state": "working", "weight": 1.0,
        })

    health_after, _ = call(args.base_url, "GET", "/api/collab/health")
    active_domains = int(health_after.get("consumer", {}).get("active_domains") or 0)
    active_delta = max(0, active_domains - active_before)
    list_payload, list_ms = call(args.base_url, "GET", "/api/collab/domains")
    list_data = list_payload.get("data") or list_payload

    report = {
        "status": "pass" if active_delta >= len(created) else "fail",
        "domains_requested": args.domains,
        "domains_created": len(created),
        "concurrency": args.concurrency,
        "create_wall_ms": round(create_wall_ms, 2),
        "create_p50_ms": round(statistics.median(create_latencies), 2) if create_latencies else 0,
        "create_p95_ms": round(sorted(create_latencies)[max(0, int(len(create_latencies) * 0.95) - 1)], 2) if create_latencies else 0,
        "list_latency_ms": round(list_ms, 2),
        "list_total": int(list_data.get("total") or 0),
        "active_consumers_before": active_before,
        "active_consumers_after": active_domains,
        "active_consumer_delta": active_delta,
        "health_ms": round(health_ms, 2),
    }

    if args.cleanup:
        with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
            list(pool.map(lambda domain: call(args.base_url, "DELETE", f"/api/collab/domains/{domain}"), created))

    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(text, encoding="utf-8")
    return 0 if report["status"] == "pass" else 1


if __name__ == "__main__":
    raise SystemExit(main())