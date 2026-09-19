#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Contract Checker · 数据字典 ↔ proto 双向校验 + work-platform 契约分层校验（E2/W2/X3）
================================================================
用途: 校验 proto 契约字段与《D5-2 核心数据字典》字段定义一致，防止字段漂移；
      校验《work-platform-bff-openapi.yaml》端点分层（P1 启用层 / 阶段层）完整性。

运行:
  python scripts/contract-check.py [--proto-dir proto]
  python scripts/contract-check.py --work-platform [--openapi <yaml路径>]

规则:
  1. proto message 字段名必须为 snake_case（D5-2 命名规则）
  2. 通用字段 (id/tenant_id/created_at/updated_at/version/status/source) 必须全模型覆盖
  3. work-platform 端点必须包含 P1 启用层全部端点；路径命名 snake_case（X3 分层校验）
  4. 校验结果输出报告，exit code 0=通过 1=有漂移（CI 门禁用）
"""
import argparse
import json
import os
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]

# D5-2 通用字段（所有模型必须包含，status/source 允许枚举差异）
COMMON_FIELDS = ["id", "tenant_id", "created_at", "updated_at", "version"]
# D5-2 命名规则：snake_case（允许下划线，禁止驼峰/大写）
SNAKE_RE = re.compile(r"^[a-z][a-z0-9_]*$")
# work-platform 路径段命名：小写字母/数字/下划线/连字符/花括号
PATH_SEG_RE = re.compile(r"^[a-z0-9_{}.-]+$")

# 预期核心模型 → proto 文件映射（校验目标）
MODEL_EXPECT = {
    "Session":  {"file": "session/v1/session.proto", "extra": ["agent_id", "title", "message_count", "last_activity_at", "model"]},
    "Message":  {"file": "session/v1/session.proto", "extra": ["session_id", "role", "content", "intent", "confidence", "source_citations", "latency_ms", "source"]},
}

# X3 · work-platform 契约分层：P1 启用层（数据源已就绪，P1 必须可接入）
WP_P1_ENDPOINTS = {
    "/overview", "/vitals", "/tasks", "/tasks/{task_id}",
    "/tasks/{task_id}/retry", "/chat/{task_id}", "/results/{task_id}", "/search", "/preferences",
}
# 阶段层端点（P2-P8 随阶段点亮）
WP_STAGE_ENDPOINTS = {
    "/organs", "/senses", "/brain/{decision_id}", "/evolution", "/collab/{domain_id}",
    "/experts", "/experts/{expert_id}", "/skills", "/skills/{skill_id}/install",
    "/connectors", "/connectors/{connector_id}/authorize",
    "/automations", "/automations/{automation_id}",
    "/models", "/models/{model_id}", "/remote-im/channels", "/remote-im/command", "/agents/online",
    "/cases", "/cases/{case_id}/reuse",
    "/approvals", "/approvals/{approval_id}/decision",
}
WP_WS_EVENTS = {
    "wp.task.progress", "wp.approval.pending", "wp.vitals.update", "wp.model.metrics", "wp.optimization.suggestion",
    "wp.collab.heartbeat", "wp.collab.result", "wp.notification",
}


def parse_proto_messages(path):
    """提取 proto 中所有 message 定义的字段名"""
    messages = {}
    with open(path, encoding="utf-8") as f:
        text = f.read()
    # 按 message 块切分
    for m in re.finditer(r"message\s+(\w+)\s*\{([^}]*)\}", text):
        name, body = m.group(1), m.group(2)
        fields = []
        for fm in re.finditer(r"^\s*(?:repeated\s+)?(?:map<\s*\w+,\s*\w+\s*>|[\w.]+)\s+(\w+)\s*=\s*\d+", body, re.MULTILINE):
            fields.append(fm.group(1))
        messages[name] = fields
    return messages


def parse_openapi_paths(path):
    """行解析提取 OpenAPI 端点、WS 事件与分层状态（避免 yaml 依赖）

    返回 (paths, ws_events, status)：status 为 路径 -> x-wp-status 取值
    （implemented / planned / unmarked）。
    """
    paths, ws_events, status = set(), set(), {}
    in_ws = False
    current = None
    with open(path, encoding="utf-8") as f:
        for line in f:
            s = line.strip()
            if s == "ws:":
                in_ws = True
                continue
            if in_ws:
                m = re.search(r"name:\s*([\w.]+)", s)
                if m and s.startswith("-"):
                    ws_events.add(m.group(1))
                continue
            m = re.match(r"^  (/[a-z0-9_{}/.-]+):\s*$", line)
            if m:
                current = m.group(1)
                paths.add(current)
                status.setdefault(current, "unmarked")
                continue
            m2 = re.match(r"^    x-wp-status:\s*(\w+)\s*$", line)
            if m2 and current:
                status[current] = m2.group(1)
    return paths, ws_events, status


def parse_js_implemented_endpoints(path):
    """提取 wp-bff server.js 中 IMPLEMENTED_ENDPOINTS 的路径清单（按路径去重）

    去重口径说明：登记表按 (method, path) 逐个登记，同一路径可能出现在多个方法下
    （例如 `/knowledge` 同时有 GET 统计与 POST 入库）。而契约侧的 `x-wp-status`
    是挂在**路径**上的，因此这里必须按路径去重，两侧口径才可比——
    否则会打印出 "implemented 8 | wp-bff 实现 9" 这种看似矛盾的计数。
    方法级明细由 count_js_implemented_methods 单独给出。
    """
    if not os.path.exists(path):
        return []
    text = open(path, encoding="utf-8").read()
    block = re.search(r"const IMPLEMENTED_ENDPOINTS = \[(.*?)\]", text, re.S)
    if not block:
        return []
    return sorted(set(re.findall(r"path:\s*'([^']+)'", block.group(1))))


def count_js_implemented_methods(path):
    """IMPLEMENTED_ENDPOINTS 的 (method, path) 条目数——方法级口径"""
    if not os.path.exists(path):
        return 0
    text = open(path, encoding="utf-8").read()
    block = re.search(r"const IMPLEMENTED_ENDPOINTS = \[(.*?)\]", text, re.S)
    if not block:
        return 0
    return len(re.findall(r"method:\s*'[A-Z]+'\s*,\s*path:\s*'[^']+'", block.group(1)))


def parse_frontend_endpoints(path):
    """提取 provider.ts 中调用的 BFF 路径（含模板变量）"""
    if not os.path.exists(path):
        return []
    text = open(path, encoding="utf-8").read()
    found = set()
    for m in re.finditer(r"api\.(?:get|post|put|patch|delete)\(\s*[`'\"]([^`'\"]+)", text):
        found.add(m.group(1))
    for m in re.finditer(r"\[\s*'[a-z_]+'\s*,\s*'([^']+)'\]", text):
        found.add(m.group(1))
    return sorted(found)


def normalize_frontend_path(p):
    """前端模板变量 ${x} 归一化为 {param}，并确保以 / 开头。

    查询串（`?turns=6`）**不属于路径身份**：契约里登记的是 `/session/{id}/context`
    这样一条路径，查询参数是它的可变入参。若把查询串留在路径里参与比对，
    就会出现「前端正常调用、契约也已登记，却报未登记」的假失败。
    """
    p = re.sub(r"\$\{[^}]+\}", "{param}", p)
    p = p.split("?", 1)[0]
    return p if p.startswith("/") else "/" + p


def path_matches_template(template, actual):
    """段级匹配：契约模板中的 {xxx} 段可匹配任意非空段"""
    t = [x for x in template.strip("/").split("/") if x]
    a = [x for x in actual.strip("/").split("/") if x]
    if len(t) != len(a):
        return False
    for ts, as_ in zip(t, a):
        if ts.startswith("{") and ts.endswith("}"):
            if not as_:
                return False
            continue
        if ts != as_:
            return False
    return True


def check_model(messages, model, spec):
    if model not in messages:
        return [f"FAIL 模型缺失: {model} 未在 {spec['file']} 中定义"]
    fields = messages[model]
    issues = []
    for cf in COMMON_FIELDS:
        if cf not in fields:
            issues.append(f"WARN 通用字段缺失: {model}.{cf}（若为精简场景可忽略，需字典登记）")
    for ef in spec.get("extra", []):
        if ef not in fields:
            issues.append(f"FAIL 字典字段缺失: {model}.{ef}")
    for fld in fields:
        if not SNAKE_RE.match(fld):
            issues.append(f"FAIL 命名违规: {model}.{fld} 非 snake_case")
    return issues


def check_tool_schema_baseline():
    """IN-06：工具 schema 契约基线的存在性与「工具名集合」比对。

    指纹真值只能由 JVM 算出（`ToolRegistry.fingerprint`：SHA-256 前 8 字节），
    所以这里**刻意不复制哈希实现**——复制一份就等于制造第二份真相。
    本函数只做跨语言可做的两件事：

      1. 基线文件存在且结构合法；
      2. 基线登记的工具名 与 `ToolBootstrap` 注册的工具名 **双向一致**
         （新增/改名工具却忘记登记基线 → 红灯）。

    指纹与版本的一致性由 tool-executor 的 `ToolSchemaBaselineTest` 强制
    （schema 一改就红，逼出「重跑 L1 契约测试 + 更新基线」的动作）。
    """
    baseline_path = REPO_ROOT / "contracts" / "tool-schema-baseline.json"
    bootstrap_path = (REPO_ROOT / "services" / "java" / "tool-executor" / "src" / "main"
                      / "java" / "com" / "agent" / "tool" / "registry" / "ToolBootstrap.java")
    if not baseline_path.exists():
        return ["FAIL 工具 schema 契约基线缺失: contracts/tool-schema-baseline.json（IN-06）」"]
    try:
        data = json.loads(baseline_path.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001 - 门禁脚本，任何解析失败都算 FAIL
        return [f"FAIL 工具 schema 契约基线不是合法 JSON: {exc}"]

    issues = []
    baseline_tools = set((data.get("tools") or {}).keys())
    if not baseline_tools:
        issues.append("FAIL 工具 schema 契约基线未登记任何工具")
    if not bootstrap_path.exists():
        issues.append("FAIL 未找到 ToolBootstrap.java，无法比对内置工具集合")
        return issues

    text = bootstrap_path.read_text(encoding="utf-8")
    declared = set(re.findall(r'new ToolMeta\("([a-z0-9_-]+)"', text))
    for name in sorted(declared - baseline_tools):
        issues.append(f"FAIL 内置工具未登记契约基线: {name}"
                      "（IN-06：请更新 contracts/tool-schema-baseline.json）")
    for name in sorted(baseline_tools - declared):
        issues.append(f"FAIL 契约基线登记了已不存在的工具: {name}"
                      "（ToolBootstrap 不再注册，基线过期）")
    print(f"工具契约基线: {len(baseline_tools)} 个工具 | ToolBootstrap 注册 {len(declared)} 个")
    return issues


def check_work_platform(openapi_path):
    """X3 · work-platform 契约分层校验"""
    if not os.path.exists(openapi_path):
        return [f"FAIL 契约文件缺失: {openapi_path}"]
    paths, ws_events, wp_status = parse_openapi_paths(openapi_path)
    issues = []
    # 1) P1 启用层必须全覆盖
    for ep in sorted(WP_P1_ENDPOINTS):
        if ep not in paths:
            issues.append(f"FAIL P1 层端点缺失: {ep}")
    # 2) 阶段层端点抽查
    for ep in sorted(WP_STAGE_ENDPOINTS):
        if ep not in paths:
            issues.append(f"WARN 阶段层端点缺失: {ep}（若尚未开发可忽略，需登记）")
    # 3) 路径命名规范
    for ep in paths:
        for seg in ep.strip("/").split("/"):
            if not PATH_SEG_RE.match(seg):
                issues.append(f"FAIL 路径命名违规: {seg}（应小写 snake_case）")
    # 4) WS 事件完整性
    for ev in sorted(WP_WS_EVENTS):
        if ev not in ws_events:
            issues.append(f"FAIL WS 事件缺失: {ev}")
    # 5) 实现端点必须在契约中登记，且状态必须为 implemented
    impl_paths = parse_js_implemented_endpoints(
        str(REPO_ROOT / "services" / "node" / "wp-bff" / "server.js"))
    for ep in impl_paths:
        if ep not in paths:
            issues.append(f"FAIL 实现端点未登记契约: {ep}（wp-bff IMPLEMENTED_ENDPOINTS）")
        elif wp_status.get(ep) != "implemented":
            issues.append(f"FAIL 实现端点状态不符: {ep}（契约标记 {wp_status.get(ep)}，应为 implemented）")

    # 6) 前端调用的端点必须能匹配到契约模板
    fe_paths = parse_frontend_endpoints(
        str(REPO_ROOT / "web" / "work-platform" / "src" / "api" / "provider.ts"))
    for raw in fe_paths:
        actual = normalize_frontend_path(raw)
        if not any(path_matches_template(tpl, actual) for tpl in paths):
            issues.append(f"FAIL 前端调用未登记契约: {actual}（provider.ts）")

    # 7) 所有端点都应有分层标记
    unmarked = sorted(p for p, v in wp_status.items() if v == "unmarked")
    if unmarked:
        issues.append(f"WARN 端点缺少 x-wp-status 标记: {len(unmarked)} 个（{', '.join(unmarked[:3])} …）")

    # 8) IN-06 工具 schema 契约基线
    issues += check_tool_schema_baseline()

    impl_n = sum(1 for v in wp_status.values() if v == "implemented")
    impl_methods = count_js_implemented_methods(
        str(REPO_ROOT / "services" / "node" / "wp-bff" / "server.js"))
    print(f"扫描端点: {len(paths)} 个 | WS 事件: {len(ws_events)} 个 | P1 层: {len(WP_P1_ENDPOINTS)} | 阶段层: {len(WP_STAGE_ENDPOINTS)}")
    print(f"实现分层: implemented {impl_n} 路径 | planned {len(paths) - impl_n} 路径 | "
          f"wp-bff 实现 {len(impl_paths)} 路径（{impl_methods} 个方法） | 前端调用 {len(fe_paths)} 路径")
    return issues


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--proto-dir", default=str(REPO_ROOT / "proto"), help="proto 根目录")
    ap.add_argument("--work-platform", action="store_true", help="校验 work-platform 契约分层（X3）")
    ap.add_argument("--openapi", default="", help="work-platform OpenAPI 契约路径")
    args = ap.parse_args()

    all_issues = []
    print("=" * 60)

    if args.work_platform:
        print("Contract Check · work-platform BFF 契约分层校验（X3）")
        print("=" * 60)
        openapi = args.openapi or os.path.join(
            os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
            "contracts", "work-platform-bff-openapi.yaml",
        )
        all_issues = check_work_platform(openapi)
    else:
        print("Contract Check · 数据字典 ↔ proto 双向校验")
        print("=" * 60)
        proto_messages = {}
        for root, _, files in os.walk(args.proto_dir):
            for fn in files:
                if fn.endswith(".proto"):
                    proto_messages.update(parse_proto_messages(os.path.join(root, fn)))
        print(f"扫描 proto 文件: {len(proto_messages)} 个 message 定义")
        for model, spec in MODEL_EXPECT.items():
            all_issues += check_model(proto_messages, model, spec)

    if not all_issues:
        print("✅ 全部通过：契约完整、命名符合规范")
        return 0
    for i in all_issues:
        print(f"  {i}")
    print("-" * 60)
    fails = [i for i in all_issues if i.startswith("FAIL")]
    warns = [i for i in all_issues if i.startswith("WARN")]
    print(f"FAIL: {len(fails)} | WARN: {len(warns)}")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
