#!/usr/bin/env python3
"""Verify one-owner-per-domain and takeover behavior across two collab-bus instances."""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.request
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


def get_json(url: str, path: str) -> dict:
    with urllib.request.urlopen(url.rstrip("/") + path, timeout=5) as response:
        return json.loads(response.read().decode("utf-8"))


def post_json(url: str, path: str, body: dict) -> dict:
    request = urllib.request.Request(
        url.rstrip("/") + path,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        method="POST",
        headers={"Content-Type": "application/json", "X-Tenant-Id": "default"},
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.loads(response.read().decode("utf-8"))


def owned(url: str) -> set[str]:
    health = get_json(url, "/api/collab/health")
    return set(health.get("consumer", {}).get("domain_ids") or [])


def main() -> int:
    parser = argparse.ArgumentParser(description="MC-01 multi-instance ownership check")
    parser.add_argument("--owner-url", default="http://127.0.0.1:8085")
    parser.add_argument("--peer-url", default="http://127.0.0.1:8086")
    parser.add_argument("--takeover-url", default="", help="verify all created domains on this URL after owner is stopped")
    parser.add_argument("--domains", type=int, default=20)
    parser.add_argument("--wait-seconds", type=float, default=8.0)
    parser.add_argument("--domains-file", default="", help="reuse created_domains from a previous report")
    parser.add_argument("--output", default="")
    args = parser.parse_args()

    created: list[str] = []
    if args.domains_file:
        created = list(json.loads(Path(args.domains_file).read_text(encoding="utf-8")).get("created_domains") or [])
    else:
        for index in range(args.domains):
            payload = post_json(args.owner_url, "/api/collab/domains", {"name": f"multi-{index:03d}"})
            created.append(payload["data"]["domain_id"])

    deadline = time.time() + args.wait_seconds
    while time.time() < deadline:
        first = owned(args.owner_url)
        second = owned(args.peer_url)
        covered = set(created) & (first | second)
        if len(covered) == len(created):
            break
        time.sleep(0.5)
    else:
        first = owned(args.owner_url)
        second = owned(args.peer_url)

    first = owned(args.owner_url)
    second = owned(args.peer_url)
    created_set = set(created)
    duplicates = sorted((first & second) & created_set) if args.owner_url != args.peer_url else []
    missing = sorted(created_set - (first | second))
    takeover_missing: list[str] = []
    if args.takeover_url:
        takeover_owned = owned(args.takeover_url)
        takeover_missing = sorted(created_set - takeover_owned)

    report = {
        "status": "pass" if not duplicates and not missing and not takeover_missing else "fail",
        "domains_created": len(created),
        "created_domains": created,
        "owner_only": len(created_set & first),
        "peer_only": len(created_set & second),
        "duplicate_owners": duplicates,
        "missing_owners": missing,
        "takeover_missing": takeover_missing,
        "owner_lease_owner": get_json(args.owner_url, "/api/collab/health").get("consumer", {}).get("lease_owner"),
        "peer_lease_owner": get_json(args.peer_url, "/api/collab/health").get("consumer", {}).get("lease_owner"),
    }
    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(text, encoding="utf-8")
    return 0 if report["status"] == "pass" else 1


if __name__ == "__main__":
    raise SystemExit(main())