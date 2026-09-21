#!/usr/bin/env python3
"""Exercise the Phase 6 Blocking endpoints against a live local stack."""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


def request_json(url: str, *, method: str = "GET", payload=None, headers=None, timeout: float = 10.0):
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request_headers = dict(headers or {})
    if body is not None:
        request_headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=body, method=method, headers=request_headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
            return response.status, json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8")
        return error.code, json.loads(raw) if raw.strip() else {}


def main() -> int:
    parser = argparse.ArgumentParser(description="Check Phase 6 Blocking endpoint minimal closure")
    parser.add_argument("--wp-bff-url", default="http://127.0.0.1:8090")
    parser.add_argument("--collab-url", default="http://127.0.0.1:8085")
    parser.add_argument("--control-token-file", default="services/node/wp-bff/logs/wp-bff-control-token")
    parser.add_argument("--output", default="")
    args = parser.parse_args()

    token_path = Path(args.control_token_file)
    if not token_path.is_absolute():
        token_path = Path.cwd() / token_path
    token = token_path.read_text(encoding="utf-8").strip()
    bff = args.wp_bff_url.rstrip("/")
    collab = args.collab_url.rstrip("/")
    tenant_headers = {"X-Tenant-Id": "default"}
    control_headers = {**tenant_headers, "Origin": "http://127.0.0.1:3001", "X-WP-Control-Token": token}
    title = f"Phase6 Blocking Check {int(time.time())}"
    task_id = ""
    checks = {}
    errors = []

    try:
        status, created = request_json(
            bff + "/api/wp/tasks", method="POST", payload={"title": title, "type": "collab_domain"},
            headers=control_headers,
        )
        checks["create_task"] = {"status": status, "code": created.get("code")}
        task = created.get("data", {}) if isinstance(created, dict) else {}
        task_id = str(task.get("task_id") or "")
        if status != 201 or not task_id:
            errors.append(f"create_task failed: HTTP {status} {created}")

        if task_id:
            status, heartbeat = request_json(
                f"{collab}/api/collab/domains/{task_id}/heartbeats", method="POST",
                payload={"member_id": "phase6-checker", "progress": 42, "state": "working", "weight": 1},
                headers=tenant_headers,
            )
            checks["heartbeat"] = {"status": status, "code": heartbeat.get("code") if isinstance(heartbeat, dict) else None}
            if status != 200:
                errors.append(f"heartbeat failed: HTTP {status} {heartbeat}")

        status, tasks = request_json(bff + "/api/wp/tasks?state=running", headers=tenant_headers)
        task_items = tasks.get("data", {}).get("items", []) if isinstance(tasks, dict) else []
        checks["tasks"] = {"status": status, "matched": any(item.get("task_id") == task_id for item in task_items)}
        if status != 200 or not checks["tasks"]["matched"]:
            errors.append(f"tasks list did not return created task: HTTP {status} {tasks}")

        if task_id:
            status, detail = request_json(bff + f"/api/wp/tasks/{task_id}", headers=tenant_headers)
            data = detail.get("data", {}) if isinstance(detail, dict) else {}
            checks["task_detail"] = {"status": status, "agents": len(data.get("agents", []))}
            if status != 200 or not data.get("task"):
                errors.append(f"task detail invalid: HTTP {status} {detail}")

            status, results = request_json(bff + f"/api/wp/results/{task_id}", headers=tenant_headers)
            data = results.get("data", {}) if isinstance(results, dict) else {}
            checks["results"] = {
                "status": status,
                "items": len(data.get("items", [])),
                "artifact_source_connected": data.get("artifact_source_connected"),
                "reason": data.get("reason"),
            }
            if status != 200 or data.get("artifact_source_connected") is not False:
                errors.append(f"results closure invalid: HTTP {status} {results}")

        status, agents = request_json(bff + "/api/wp/agents/online", headers=tenant_headers)
        agent_items = agents.get("data", {}).get("items", []) if isinstance(agents, dict) else []
        checks["agents_online"] = {
            "status": status,
            "matched": any(item.get("agent_id") == f"{task_id}:phase6-checker" for item in agent_items),
        }
        if status != 200 or not checks["agents_online"]["matched"]:
            errors.append(f"agents online did not return checker agent: HTTP {status} {agents}")

        status, experts = request_json(bff + "/api/wp/experts", headers=tenant_headers)
        expert_data = experts.get("data", {}) if isinstance(experts, dict) else {}
        checks["experts"] = {"status": status, "available": expert_data.get("available")}
        if status != 200 or expert_data.get("available") is not False:
            errors.append(f"experts should be explicitly unavailable: HTTP {status} {experts}")

        status, approvals = request_json(bff + "/api/wp/approvals", headers=tenant_headers)
        approval_data = approvals.get("data", {}) if isinstance(approvals, dict) else {}
        checks["approvals"] = {"status": status, "available": approval_data.get("available")}
        if status != 200 or approval_data.get("available") is not False:
            errors.append(f"approvals should be explicitly unavailable: HTTP {status} {approvals}")

        status, decision = request_json(
            bff + "/api/wp/approvals/A-CHECK/decision", method="POST", payload={"decision": "approved"},
            headers=control_headers,
        )
        decision_details = decision.get("details", {}) if isinstance(decision, dict) else {}
        checks["approval_decision"] = {
            "status": status,
            "code": decision.get("code") if isinstance(decision, dict) else None,
            "side_effects": decision_details.get("side_effects"),
        }
        if status != 503 or decision_details.get("side_effects") is not False:
            errors.append(f"approval decision must fail closed: HTTP {status} {decision}")
    finally:
        if task_id:
            status, _ = request_json(
                f"{collab}/api/collab/domains/{task_id}", method="DELETE", headers=tenant_headers,
            )
            checks["cleanup"] = {"status": status}

    report = {"status": "pass" if not errors else "fail", "task_id": task_id, "checks": checks, "errors": errors}
    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(text, encoding="utf-8")
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
