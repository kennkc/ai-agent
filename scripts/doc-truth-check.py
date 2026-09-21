#!/usr/bin/env python3
"""校验当前进度文档没有回退到已废弃口径。

只检查“可机器验证的事实”，不替代人工评审：
  * OpenAPI implemented / planned 路径数；
  * 当前分支拓扑、MC-01、生产路由、模型配置 fail-closed；
  * 前端 JUnit 用例数与覆盖率报告是否一致；
  * DEBT-006/009 状态、Prometheus/Playwright 资产与废弃描述。
"""
from __future__ import annotations

import importlib.util
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OPENAPI = ROOT / "contracts" / "work-platform-bff-openapi.yaml"
WP_BFF_SERVER = ROOT / "services" / "node" / "wp-bff" / "server.js"
OVERVIEW = ROOT / "docs" / "项目进度总览.md"
DEBT = ROOT / "docs" / "技术债台账.md"
PROGRESS = ROOT / "PROGRESS.md"
FRONTEND_REPORT = ROOT / "docs" / "test-reports" / "frontend" / "2026-09-20" / "frontend-functional-report.md"
GENERATED_JUNIT = ROOT / "web" / "work-platform" / "test-results" / "junit.xml"
GENERATED_COVERAGE = ROOT / "web" / "work-platform" / "coverage" / "coverage-summary.json"
JUNIT = GENERATED_JUNIT if GENERATED_JUNIT.exists() else ROOT / "docs" / "test-reports" / "frontend" / "2026-09-20" / "junit.xml"
COVERAGE = GENERATED_COVERAGE if GENERATED_COVERAGE.exists() else ROOT / "docs" / "test-reports" / "frontend" / "2026-09-20" / "coverage-summary.json"

FORBIDDEN = (
    "Phase 5 的改动（`tool-executor` 新服务 + 文档）**尚未提交**",
    "BFF 契约 30 个 planned 端点",
    "历史分支 `codex/main` / `workbuddy/main` 在本机已不存在",
    "R-MC01-05 仍是真实协作域 UI 后续项",
    "真实 LLM 引擎未接入",
    "92a46c1",
    "63 端点，implemented **35 路径 / 38 方法** / planned 28",
)


def main() -> int:
    failures: list[str] = []
    spec = importlib.util.spec_from_file_location("contract_check", ROOT / "scripts" / "contract-check.py")
    if spec is None or spec.loader is None:
        print("FAIL 无法加载 contract-check.py", file=sys.stderr)
        return 1
    contract_check = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(contract_check)
    paths, _ws_events, status = contract_check.parse_openapi_paths(str(OPENAPI))
    implemented = sorted(path for path in paths if status.get(path) == "implemented")
    planned = sorted(path for path in paths if status.get(path) == "planned")
    methods = contract_check.count_js_implemented_methods(str(WP_BFF_SERVER))

    overview = OVERVIEW.read_text(encoding="utf-8")
    debt = DEBT.read_text(encoding="utf-8")
    progress = PROGRESS.read_text(encoding="utf-8")
    report = FRONTEND_REPORT.read_text(encoding="utf-8")

    required = {
        f"{len(implemented)} 个路径 / {methods} 个方法": "BFF 实现规模",
        f"{len(planned)} 个 planned 端点": "BFF planned 数量",
        "codex/main": "当前主分支登记",
        "workbuddy/main": "协作分支登记",
        "R-MC01-05 已闭合": "MC-01 收口状态",
        "WP_BFF_URI": "生产 wp-bff 路由变量",
        "MODEL_CONFIG_REQUIRE_PERSISTENCE": "模型配置生产 fail-closed 变量",
        "DEBT-006": "五渠道测试债登记",
        "DEBT-009": "消息链路测试债登记",
        "Prometheus": "可观测性登记",
        "Playwright": "E2E 登记",
    }
    for needle, label in required.items():
        if needle not in overview:
            failures.append(f"{label} 缺失：{needle}")

    for stale in FORBIDDEN:
        if stale in overview or stale in progress or stale in report:
            failures.append(f"进度文档出现废弃表述：{stale}")

    if "31 个 planned 端点" in debt:
        failures.append("技术债台账仍写 31 个 planned 端点")
    if "DEBT-006 / DEBT-009，两者状态标" in debt:
        failures.append("技术债台账仍把 DEBT-006/009 标为待验")
    for needle in ("DEBT-006", "DEBT-009", "✅ **已闭合（2026-09-20）**"):
        if needle not in debt:
            failures.append(f"技术债台账缺少关闭证据：{needle}")

    required_paths = (
        ROOT / "infra" / "prometheus" / "prometheus.yml",
        ROOT / "infra" / "prometheus" / "alerts.yml",
        ROOT / "contracts" / "phase6-endpoint-priority.yaml",
        ROOT / "web" / "work-platform" / "playwright.config.ts",
        ROOT / "web" / "work-platform" / "playwright.live.config.ts",
        ROOT / "docs" / "test-reports" / "collab" / "2026-09-21" / "scale-report.md",
        ROOT / "web" / "work-platform" / "e2e" / "platform-smoke.spec.ts",
    )
    for path in required_paths:
        if not path.exists():
            failures.append(f"缺少文件：{path.relative_to(ROOT)}")

    root = ET.parse(JUNIT).getroot()
    tests = int(root.attrib.get("tests", "0"))
    failures_count = int(root.attrib.get("failures", "0"))
    if tests <= 0 or failures_count != 0:
        failures.append(f"前端 JUnit 异常：tests={tests}, failures={failures_count}")
    if f"{tests} passed / 0 failed" not in report:
        failures.append(f"前端报告未登记 {tests} passed / 0 failed")

    coverage = json.loads(COVERAGE.read_text(encoding="utf-8"))["total"]
    lines = coverage["lines"]["pct"]
    branches = coverage["branches"]["pct"]
    for value, label in ((lines, "行覆盖率"), (branches, "分支覆盖率")):
        if f"{value:.2f}%" not in report:
            failures.append(f"前端报告未同步{label}：{value:.2f}%")

    if failures:
        print("── 文档真相源校验 ──")
        for item in failures:
            print(f"  FAIL {item}")
        print(f"合计: FAIL={len(failures)}")
        return 1
    print("── 文档真相源校验 ──")
    print(f"  implemented={len(implemented)} paths / {methods} methods · planned={len(planned)}")
    print(f"  前端 {tests} passed · 行 {lines:.2f}% · 分支 {branches:.2f}%")
    print("合计: FAIL=0")
    return 0


if __name__ == "__main__":
    sys.exit(main())