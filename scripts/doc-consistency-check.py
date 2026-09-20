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

③ 的「过期」判定不看裸 mtime：文件已提交时比**最后一次提交时间**，仅在有未提交改动时才比
mtime —— 否则 git 在 rebase / checkout 后按任意顺序重写工作区，会把内容同步的孪生对误判为过期
（2026-09-20 实测踩到，三组同步孪生对同时报 FAIL）。

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
import subprocess
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


def _git(*args: str) -> str | None:
    """执行 git 子命令，返回 strip 后的 stdout；git 不可用/非仓库/出错时返回 None。"""
    try:
        done = subprocess.run(
            ["git", *args],
            cwd=ROOT,
            capture_output=True,
            text=True,
            timeout=30,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    return done.stdout.strip() if done.returncode == 0 else None


class TwinGitState:
    """一次性抓取孪生判定需要的 git 信息（**不用逐文件调 git**）。

    Windows 上每次 `git` 启动约 1~2 秒，60 组孪生对逐文件查询会把门禁拖到 3 分钟以上
    （实测超时）。因此这里固定只跑两条命令：

    - `git status --porcelain` → 未提交改动集合（含未跟踪）
    - `git log --name-only`（限定 docs / README.md / PROGRESS.md）→ 每个文件最后一次提交时间，
      只取首次出现（即最近一次），映射不完整时按「未知」处理并回退 mtime。
    """

    LOG_LIMIT = 800

    def __init__(self) -> None:
        self.dirty: set[str] | None = None
        self.commit_ts: dict[str, int] = {}

        out = _git("status", "--porcelain", "--", "docs", "README.md", "PROGRESS.md")
        if out is not None:
            self.dirty = {line[3:].strip().strip('"') for line in out.splitlines() if len(line) > 3}

        log = _git(
            "log",
            f"-n{self.LOG_LIMIT}",
            "--format=@@%ct",
            "--name-only",
            "--no-renames",
            "--",
            "docs",
            "README.md",
            "PROGRESS.md",
        )
        if log:
            ts: int | None = None
            for line in log.splitlines():
                line = line.strip()
                if line.startswith("@@"):
                    ts = int(line[2:]) if line[2:].isdigit() else None
                elif line and ts is not None:
                    self.commit_ts.setdefault(line.replace("\\", "/"), ts)

    def is_dirty(self, rel: str) -> bool | None:
        return None if self.dirty is None else rel.replace("\\", "/") in self.dirty

    def last_commit_ts(self, rel: str) -> int | None:
        return self.commit_ts.get(rel.replace("\\", "/"))


def twin_is_stale(md: Path, html: Path, state: TwinGitState) -> bool:
    """判定 `.md` 是否比同名 `.html` 新（孪生过期）。

    为什么不直接比 `st_mtime`：git 在 rebase / checkout / stash 时会**按任意顺序**重写工作区
    文件，md 与 html 的 mtime 先后与「谁的内容更新」无关。2026-09-20 实测：一轮 rebase 之后
    三组「已提交且内容同步」的孪生对全部被判过期，真正的过期问题反而被噪声掩盖。

    判定顺序（快路径优先，避免为每组文件付出 git 启动成本）：
    1. md 不比 html 新 → 直接放行（绝大多数文件走这条，零 git 查询）。
    2. md 更新 → 若有未提交改动，说明确实是刚改的，维持 mtime 结论。
    3. md 更新且两侧都已提交 → 比「最后一次提交时间」，提交更晚才算过期；
       同一次提交（孪生同批入库）视为同步 —— 这正是 rebase 后的情形。
    """
    if md.stat().st_mtime <= html.stat().st_mtime:
        return False

    md_rel, html_rel = str(md.relative_to(ROOT)), str(html.relative_to(ROOT))
    md_dirty, html_dirty = state.is_dirty(md_rel), state.is_dirty(html_rel)
    if md_dirty is False and html_dirty is False:
        md_ts, html_ts = state.last_commit_ts(md_rel), state.last_commit_ts(html_rel)
        if md_ts is not None and html_ts is not None:
            return md_ts > html_ts
    return True


def check_twins(problems: list[str]) -> tuple[int, int]:
    """③ md/html 孪生同步（只查已有 html 孪生的 md，缺 html 不算失败）。"""
    checked = stale = 0
    pairs: list[tuple[Path, Path]] = []

    for md in (ROOT / "docs").rglob("*.md"):
        if any(part in TWIN_SKIP_DIRS for part in md.parts):
            continue
        html = md.with_suffix(".html")
        if html.exists():
            pairs.append((md, html))
    for name in ("README.md", "PROGRESS.md"):
        md = ROOT / name
        html = ROOT / f"{name[:-3]}.html"
        if md.exists() and html.exists():
            pairs.append((md, html))

    state = TwinGitState()
    for md, html in pairs:
        checked += 1
        if twin_is_stale(md, html, state):
            stale += 1
            problems.append(f"孪生过期：{md.relative_to(ROOT)} 比同名 .html 新（需重跑 md2html-report.py）")
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
