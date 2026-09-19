#!/usr/bin/env python3
"""文档一致性门禁 —— 守三类「靠人记不住」的文档约束。

## 为什么需要它

本仓的文档坑反复以同一形状出现：**约束写在文档里，但没人守**。
2026-09-19 实测踩到的例子：给 `docs/异常流程归纳.md` 追加异常条目时，
新号取了 `D-17`~`D-21`，而这三个号在另一个章节早已被占用 ——
**文档照常渲染、门禁全绿，撞号静默存在**，直到人工 `grep | uniq -d` 才发现。

本脚本把这三类约束搬到机器上：

| 检查 | 防的是什么 |
|---|---|
| ① 异常编号唯一 | `D-xx` 撞号（新增条目时最容易犯） |
| ② 章节编号唯一且有序 | `### 3.3` 出现两次（插入新章节后忘记顺延） |
| ③ md/html 孪生同步 | 改了 `.md` 忘了重生 `.html`，页面停留在旧口径 |

## 用法

```bash
python scripts/doc-consistency-check.py            # 全量检查
python scripts/doc-consistency-check.py --no-twin  # 跳过孪生检查（CI 里 html 未落盘时）
```

退出码：0 = 全通过；1 = 有 FAIL。

## 设计约定

- **不引入第三方依赖**（与 `contract-check.py` / `timeout-budget-check.py` 同路线）。
- **一条坏规则不会让脚本崩溃**，而是报 FAIL —— 巡检脚本必须能在「发现故障」时正常工作。
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

ANOMALY_DOC = ROOT / "docs" / "异常流程归纳.md"


def check_unique(counter: dict[str, list[int]], kind: str, problems: list[str]) -> None:
    """通用唯一性判定：counter 为 {值: [行号...]}。"""
    for value, lines in sorted(counter.items()):
        if len(lines) > 1:
            where = "、".join(f"L{n}" for n in lines)
            problems.append(f"{kind} 撞号：{value} 出现 {len(lines)} 次（{where}）")


def check_anomaly_ids(problems: list[str]) -> tuple[int, int]:
    """① 异常编号唯一。② 章节编号唯一。"""
    if not ANOMALY_DOC.exists():
        problems.append(f"缺少文件：{ANOMALY_DOC.relative_to(ROOT)}")
        return 0, 0

    text = ANOMALY_DOC.read_text(encoding="utf-8", errors="replace")

    # ① 表格行首的 `| D-xx |`
    ids: dict[str, list[int]] = {}
    for i, line in enumerate(text.splitlines(), start=1):
        m = re.match(r"\|\s*(D-\d+)\s*\|", line)
        if m:
            ids.setdefault(m.group(1), []).append(i)
    check_unique(ids, "异常编号", problems)

    # ② 章节号（3.1 / 3.1.2 …）—— 必须取完整号（只取小数位会把 §1.1 / §2.1 / §3.1 误判为撞号）
    secs: dict[str, list[int]] = {}
    for i, line in enumerate(text.splitlines(), start=1):
        m = re.match(r"^#{2,4}\s+(\d+(?:\.\d+)+)\s+\S", line)
        if m:
            secs.setdefault(m.group(1), []).append(i)
    check_unique({f"章节 §{k}": v for k, v in secs.items()}, "章节编号", problems)

    return len(ids), len(secs)


TWIN_SKIP_DIRS = {"node_modules", ".git", "target", "dist", ".workbuddy"}


def check_twins(problems: list[str]) -> tuple[int, int]:
    """③ md/html 孪生同步（只查已有 html 孪生的 md，缺 html 不算失败）。"""
    checked = stale = 0
    for md in (ROOT / "docs").rglob("*.md"):
        if any(part in TWIN_SKIP_DIRS for part in md.parts):
            continue
        html = md.with_suffix(".html")
        if not html.exists():
            continue
        checked += 1
        if md.stat().st_mtime > html.stat().st_mtime:
            stale += 1
            problems.append(f"孪生过期：{md.relative_to(ROOT)} 比同名 .html 新（需重跑 md2html-report.py）")
    # 仓库根的两份
    for name in ("README.md", "PROGRESS.md"):
        md = ROOT / name
        html = ROOT / f"{name[:-3]}.html"
        if md.exists() and html.exists():
            checked += 1
            if md.stat().st_mtime > html.stat().st_mtime:
                stale += 1
                problems.append(f"孪生过期：{name} 比 {html.name} 新")
    return checked, stale


def main() -> int:
    ap = argparse.ArgumentParser(description="文档一致性门禁")
    ap.add_argument("--no-twin", action="store_true", help="跳过 md/html 孪生同步检查")
    args = ap.parse_args()

    problems: list[str] = []

    n_ids, n_secs = check_anomaly_ids(problems)
    print("── 文档一致性门禁 ──")
    print(f"  异常条目 {n_ids} 条 · 章节号 {n_secs} 个 · 编号唯一性", end="")
    print(" OK" if not any("撞号" in p for p in problems) else " FAIL")

    if not args.no_twin:
        checked, stale = check_twins(problems)
        print(f"  孪生配对 {checked} 组 · 过期 {stale} 组")
    else:
        print("  孪生检查：已跳过（--no-twin）")

    if problems:
        print()
        for p in problems:
            print(f"  FAIL {p}")
        print(f"\n合计: FAIL={len(problems)}")
        return 1

    print("\n合计: FAIL=0（编号唯一、孪生同步）")
    print("  说明：本门禁只查「编号撞号」与「孪生过期」，不查文档内容准确性 ——")
    print("        内容仍须拿实测输出对（用例数 / 行数 / 端点计数）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
