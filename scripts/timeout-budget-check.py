#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Timeout Budget Checker · 跨服务超时预算门禁
================================================================
用途: 校验《contracts/timeout-budget.yaml》登记表与实际代码一致，并检查
      「上游预算 >= 下游最坏耗时 x 最小余量比」这一不变量。
      闭合技术债台账中「文档基线数字无人守 / 数字级门禁未落地」的长期欠账。

背景（2026-09-19 缺陷）:
  沙箱冷探测 5.7s 撞上 wp-bff 的 5s 子请求预算 → 前端把「慢」显示成「沙箱不可用」，
  而直连该端点是 200。这类错配**不影响任何功能断言**（单测全绿、契约 0 FAIL），
  只在跨服务超时交界处静默降级。故必须有专门门禁守它。

运行:
  python scripts/timeout-budget-check.py                    # 默认：gap 仅列出，不算失败
  python scripts/timeout-budget-check.py --strict           # gap 也算失败（修复期用）
  python scripts/timeout-budget-check.py --list             # 打印全表摘要
  python scripts/timeout-budget-check.py --budget <path>    # 指定登记表路径

退出码: 0=通过 1=有违规（CI 门禁用）

--- 解析约定（重要）---
仓内不引入 PyYAML 依赖（与 contract-check.py 同一路线），本脚本自带**行式解析器**。
因此登记表必须遵守：
  1. `edges:` / `gaps:` 条目**保持扁平** —— 条目内只允许一层 `key: value`，不得嵌套子 map。
  2. 双引号字符串内的反斜杠**按字面保留、不做 unescape** —— 这样正则 pattern
     可以直接写成 "Duration\\.ofSeconds\\((\\d+)\\)" 而无需双重转义。
  3. 不支持锚点 / 别名 / 多行字面量（`>` `|`）；登记表中不使用这些语法。
若登记表违反以上约定，本脚本会报「解析异常」而不是静默跳过。
"""
import argparse
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_BUDGET = REPO_ROOT / "contracts" / "timeout-budget.yaml"


# ─────────────── 极简行式 YAML 解析（仅覆盖本登记表所需子集）───────────────
def _scalar(raw: str):
    """标量解析：数字 / 布尔 / 引号字符串（引号内不做 unescape）/ 裸字符串。"""
    s = raw.strip()
    if len(s) >= 2 and s[0] == '"' and s[-1] == '"':
        return s[1:-1]          # 字面保留，正则 pattern 依赖此行为
    if s.lower() in ("true", "false"):
        return s.lower() == "true"
    try:
        return int(s)
    except ValueError:
        pass
    try:
        return float(s)
    except ValueError:
        pass
    return s


def parse_budget(text: str):
    """把登记表解析为 {section: {"scalars": {...}, "items": [{...}]}}。

    缩进约定：section 顶格；item 起始 `  - key: value`；item 字段 `    key: value`；
    section 级标量 `  key: value`（与 item 字段靠「是否在 item 内」区分）。
    """
    out = {}
    section = None
    current_item = None
    for lineno, line in enumerate(text.splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        indent = len(line) - len(line.lstrip())
        body = line.strip()

        if indent == 0:
            if not body.endswith(":"):
                # 顶格 `key: value`：root 级标量（如 version: 1），不是新 section
                if ":" not in body:
                    raise ValueError(f"第 {lineno} 行：顶格行非法 —— {body!r}")
                k, _, v = body.partition(":")
                out.setdefault("_root", {})[k.strip()] = _scalar(v)
                section = None
                current_item = None
                continue
            # 顶格且以 : 结尾 => 新 section
            section = body[:-1].strip()
            out[section] = {"scalars": {}, "items": []}
            current_item = None
            continue

        if section is None:
            raise ValueError(f"第 {lineno} 行：出现在任何 section 之前 —— {body!r}")

        if body.startswith("- "):
            # 新 item
            current_item = {}
            out[section]["items"].append(current_item)
            rest = body[2:]
            if ":" not in rest:
                raise ValueError(f"第 {lineno} 行：item 必须是 `- key: value`，实际为 {rest!r}")
            k, _, v = rest.partition(":")
            current_item[k.strip()] = _scalar(v) if v.strip() else ""
            continue

        if ":" not in body:
            raise ValueError(f"第 {lineno} 行：`key: value` 形式非法 —— {body!r}")
        key, _, val = body.partition(":")
        key, val = key.strip(), val.strip()
        if not val:
            # 空值 => 嵌套结构的开始，本登记表禁止
            raise ValueError(
                f"第 {lineno} 行：`{key}:` 后无值 —— 登记表要求 edges/gaps 条目保持扁平，"
                f"不允许嵌套子 map"
            )
        target = current_item if current_item is not None and indent >= 4 else out[section]["scalars"]
        target[key] = _scalar(val)
    return out


# ─────────────── 校验 ───────────────
def anchor_extract(edge_id, role, file_rel, pattern, scale):
    """按代码锚点提取实值。返回 (value, error_message)。"""
    if not file_rel or not pattern:
        return None, None
    path = REPO_ROOT / file_rel
    if not path.exists():
        return None, f"{role} 锚点文件不存在：{file_rel}"
    content = path.read_text(encoding="utf-8", errors="replace")
    try:
        m = re.search(pattern, content)
    except re.error as exc:
        # 一条坏 pattern 不应该让整个门禁崩掉 —— 那正是「巡检脚本在故障时失效」的老毛病
        return None, f"{role} 锚点正则非法：{exc}（pattern={pattern[:80]}…）"
    if not m:
        return None, f"{role} 锚点正则未匹配（{file_rel}）：{pattern[:70]}…"
    raw = m.group(1)
    try:
        value = float(raw) * scale
    except ValueError:
        return None, f"{role} 锚点捕获值非数字：{raw!r}"
    return int(round(value)), None


def check(budget_path: Path, strict: bool, show_list: bool):
    if not budget_path.exists():
        return [f"FAIL 登记表缺失：{budget_path}"], {}

    try:
        doc = parse_budget(budget_path.read_text(encoding="utf-8"))
    except ValueError as exc:
        return [f"FAIL 登记表解析异常：{exc}"], {}

    for required in ("meta", "budget_tiers", "edges"):
        if required not in doc:
            return [f"FAIL 登记表缺少必需段：{required}"], {}

    ratio = doc["meta"]["scalars"].get("min_headroom_ratio")
    if not isinstance(ratio, (int, float)):
        return ["FAIL meta.min_headroom_ratio 缺失或非数字"], {}

    tiers = doc["budget_tiers"]["scalars"]
    edges = doc["edges"]["items"]
    gaps = doc.get("gaps", {}).get("items", [])

    issues = []
    summary = {"ok": 0, "gap": 0, "fail": 0}

    # 缺口与边的双向一致性
    gap_by_edge = {}
    gap_ids = {g.get("id") for g in gaps}
    for gap in gaps:
        if not gap.get("id") or not gap.get("edge"):
            issues.append(f"FAIL gaps 条目缺少 id 或 edge：{gap}")
            continue
        gap_by_edge[gap["edge"]] = gap["id"]

    # ── 逐条校验 ──
    for edge in edges:
        eid = edge.get("id", "<无 id>")
        problems = []

        # 1) tier 与预算档位一致
        tier = edge.get("tier")
        if tier:
            if tier not in tiers:
                problems.append(f"tier `{tier}` 未在 budget_tiers 中定义")
            elif edge.get("upstream_budget_ms") != tiers[tier]:
                problems.append(
                    f"upstream_budget_ms={edge.get('upstream_budget_ms')} "
                    f"与档位 {tier}={tiers[tier]} 不一致"
                )

        # 2) 余量不变量
        up = edge.get("upstream_budget_ms")
        down = edge.get("downstream_worst_case_ms")
        if not isinstance(up, int) or not isinstance(down, int):
            problems.append("upstream_budget_ms / downstream_worst_case_ms 必须是整数")
        elif not edge.get("exempt_headroom"):
            if down * ratio > up:
                problems.append(
                    f"预算不足：上游 {up}ms < 下游最坏 {down}ms x {ratio} = {int(down * ratio)}ms"
                    f"（实际余量 {up / down:.2f}x）"
                )

        # 3) 锚定校验：代码实值 == 表中声明的期望值
        #
        # 为什么锚点校验的目标值与 upstream_budget_ms 可能不同：
        # 有些边的最坏耗时是**推导值**（如 LLM 级联总最坏 = 各级超时 × 重试次数），
        # 它在代码里并不存在对应常量。此时用 `*_anchor_expected_ms` 声明锚点处
        # 那个**真实存在的常量值**，与推导值分开校验 —— 既防漂移，也不假装能自动推导。
        expected_up = edge.get("upstream_anchor_expected_ms", up)
        expected_down = edge.get("downstream_anchor_expected_ms", down)
        got, err = anchor_extract(
            eid, "upstream", edge.get("upstream_anchor_file"),
            edge.get("upstream_anchor_pattern"), edge.get("upstream_anchor_scale", 1),
        )
        if err:
            problems.append(err)
        elif got is not None and got != expected_up:
            problems.append(f"上游锚点漂移：代码实值 {got}ms != 表中期望 {expected_up}ms")

        got_d, err_d = anchor_extract(
            eid, "downstream", edge.get("downstream_anchor_file"),
            edge.get("downstream_anchor_pattern"), edge.get("downstream_anchor_scale", 1),
        )
        if err_d:
            problems.append(err_d)
        elif got_d is not None and got_d != expected_down:
            problems.append(f"下游锚点漂移：代码实值 {got_d}ms != 表中期望 {expected_down}ms")

        # 4) status 与 gaps 段双向一致
        status = edge.get("status", "ok")
        if status == "gap":
            if edge.get("gap_id") not in gap_ids:
                problems.append(f"status=gap 但 gaps 段无对应条目（gap_id={edge.get('gap_id')}）")
        elif eid in gap_by_edge:
            problems.append(f"status=ok 但 gaps 段仍登记为 {gap_by_edge[eid]} —— 修复后请同步删除缺口条目")

        if problems:
            if status == "gap" and not strict:
                # 缺口本身在登记表中已声明，只统计不判失败
                summary["gap"] += 1
                for p in problems:
                    issues.append(f"GAP  {eid} — {p}")
            else:
                summary["fail"] += 1
                for p in problems:
                    issues.append(f"FAIL {eid} — {p}")
        else:
            summary["ok"] += 1

    # 5) 未被任何 edge 引用的 gap
    referenced = {e.get("id") for e in edges}
    for gap in gaps:
        if gap.get("edge") not in referenced:
            issues.append(f"FAIL {gap.get('id')} — 引用了不存在的 edge：{gap.get('edge')}")
            summary["fail"] += 1

    # 6) 魔法数字扫描（锚点校验的盲区补丁）
    #
    # 锚点校验锚的是**常量定义**，看不见调用点就地写死的数字。2026-09-19 实测踩过：
    # `POST /api/body/knowledge` 的调用点写 `timeoutMs: 30000`（= 下游最坏耗时，余量 0），
    # 而登记表记的是常量 60000 —— 门禁全绿，缺陷仍在。故必须单独扫调用点。
    scans = doc.get("magic_number_scan", {}).get("items", [])
    scan_hit = 0
    for entry in scans:
        file_rel = entry.get("file")
        if not file_rel:
            issues.append("FAIL magic_number_scan 条目缺少 file")
            summary["fail"] += 1
            continue
        path = REPO_ROOT / file_rel
        if not path.exists():
            issues.append(f"FAIL magic_number_scan 文件不存在：{file_rel}")
            summary["fail"] += 1
            continue
        pattern = entry.get("pattern") or r"timeoutMs:\s*(\d{2,})"
        allow = entry.get("allow_literals") or []
        allow = allow if isinstance(allow, list) else [allow]
        try:
            rx = re.compile(pattern)
        except re.error as exc:
            issues.append(f"FAIL magic_number_scan 正则非法（{file_rel}）：{exc}")
            summary["fail"] += 1
            continue
        for lineno, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            for m in rx.finditer(line):
                literal = m.group(1)
                if int(literal) in allow:
                    continue
                scan_hit += 1
                issues.append(
                    f"FAIL MAGIC {file_rel}:{lineno} — 调用点写死 `{m.group(0).strip()}`；"
                    f"应改用 budget_tiers 命名档位（否则该值不受本表约束，门禁看不见）"
                )
        # 记录扫描过的文件，便于确认「扫了但没命中」而非「忘了扫」
        summary.setdefault("scanned", 0)
        summary["scanned"] = summary.get("scanned", 0) + 1
    if scan_hit:
        summary["fail"] += scan_hit

    if show_list:
        print("── 超时预算登记表摘要 ──")
        print(f"{'ID':<7} {'调用方':<16} {'被调方':<16} {'上游':>8} {'下游最坏':>9} {'余量':>7} {'状态':<5}")
        print("─" * 78)
        for edge in edges:
            up, down = edge.get("upstream_budget_ms"), edge.get("downstream_worst_case_ms")
            head = f"{up / down:.2f}x" if isinstance(up, int) and isinstance(down, int) and down else "—"
            print(
                f"{edge.get('id', ''):<7} {str(edge.get('caller', ''))[:16]:<16} "
                f"{str(edge.get('callee', ''))[:16]:<16} {up:>8} {down:>9} {head:>7} "
                f"{edge.get('status', 'ok'):<5}"
            )
        print()

    return issues, summary


def main():
    ap = argparse.ArgumentParser(description="跨服务超时预算门禁")
    ap.add_argument("--budget", default="", help="登记表路径（默认 contracts/timeout-budget.yaml）")
    ap.add_argument("--strict", action="store_true", help="把已登记的 gap 也视为失败")
    ap.add_argument("--list", action="store_true", help="打印全表摘要")
    args = ap.parse_args()

    path = Path(args.budget) if args.budget else DEFAULT_BUDGET
    issues, summary = check(path, args.strict, args.list)

    print(f"── 超时预算门禁：{path.relative_to(REPO_ROOT) if path.is_absolute() else path} ──")
    for line in issues:
        print("  " + line)
    if not issues:
        print("  全部通过")

    if summary:
        print(
            f"\n合计: ok={summary.get('ok', 0)} · gap={summary.get('gap', 0)} · "
            f"fail={summary.get('fail', 0)}"
        )
        if summary.get("gap") and not args.strict:
            print("（gap 为登记表中已声明的已知倒挂，不计失败；修复后请改为 status=ok 并删除 gaps 条目）")

    return 0 if summary.get("fail", 1) == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
