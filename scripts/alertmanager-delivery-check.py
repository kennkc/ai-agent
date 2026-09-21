#!/usr/bin/env python3
"""Verify Alertmanager -> wp-bff webhook delivery end to end."""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


def request_json(url: str, method: str = "GET", payload=None, timeout: float = 10.0):
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={"Content-Type": "application/json"} if data is not None else {},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        raw = response.read().decode("utf-8")
        return json.loads(raw) if raw.strip() else {}


def contains_delivery(payload: dict, run_id: str) -> bool:
    body = payload.get("data", payload) if isinstance(payload, dict) else {}
    for item in body.get("items", []):
        for alert in item.get("alerts", []):
            if alert.get("labels", {}).get("run_id") == run_id:
                return True
    return False


def main() -> int:
    parser = argparse.ArgumentParser(description="Check real Alertmanager webhook delivery")
    parser.add_argument("--alertmanager-url", default="http://127.0.0.1:19093")
    parser.add_argument("--wp-bff-url", default="http://127.0.0.1:8090")
    parser.add_argument("--timeout-seconds", type=float, default=45)
    parser.add_argument("--output", default="")
    args = parser.parse_args()

    now = datetime.now(timezone.utc)
    fingerprint = f"lifeform-delivery-{int(now.timestamp())}"
    alert = {
        "labels": {
            "alertname": "LifeformAlertDeliveryCheck",
            "severity": "info",
            "job": "alertmanager-delivery-check",
            "instance": "local",
            "run_id": fingerprint,
        },
        "annotations": {"summary": "Alertmanager to wp-bff delivery verification"},
        "startsAt": now.isoformat().replace("+00:00", "Z"),
        "endsAt": (now + timedelta(minutes=5)).isoformat().replace("+00:00", "Z"),
    }

    posted = request_json(args.alertmanager_url.rstrip("/") + "/api/v2/alerts", "POST", [alert])
    deadline = time.time() + args.timeout_seconds
    delivered = False
    last_error = ""
    while time.time() < deadline:
        try:
            recent = request_json(args.wp_bff_url.rstrip("/") + "/api/wp/alerts?limit=20")
            delivered = contains_delivery(recent, fingerprint)
            if delivered:
                posted = {"responses": posted} if isinstance(posted, list) else posted
                break
        except Exception as error:  # noqa: BLE001 - report the actual transport failure
            last_error = f"{type(error).__name__}: {error}"
        time.sleep(2)

    report = {
        "status": "pass" if delivered else "fail",
        "alertmanager_url": args.alertmanager_url,
        "wp_bff_url": args.wp_bff_url,
        "alertname": "LifeformAlertDeliveryCheck",
        "fingerprint_hint": fingerprint,
        "delivered": delivered,
        "last_error": last_error,
    }
    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(text, encoding="utf-8")
    return 0 if delivered else 1


if __name__ == "__main__":
    raise SystemExit(main())
