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

### 2026-09-21 修复（孪生判定曾整体失效）

同一天 27 组孪生对同时报 FAIL，逐条核实**全是本脚本的假告警**（文档内容并未过期）。三处缺陷：

| # | 缺陷 | 后果 | 修法 |
|---|---|---|---|
| 1 | git 输出**未解码非 ASCII 路径** | 中文文档名被输出成 `"docs/\346\226\207..."`，与 `Path` 拼出的相对路径对不上 → 提交时间查不到 | 所有 git 调用加 `-c core.quotepath=false` |
| 2 | `git log` 的 pathspec **漏了根目录 `.html`** | `README.html` / `PROGRESS.html` 永远查不到提交时间 | pathspec 补上两个 `.html` |
| 3 | 时间查不到时**直接判过期**（`return True`） | 「查不到」被当成「过期」，真问题被噪声埋掉 | 改为三态 `ok / stale / unknown`，`unknown` 只告警不计 FAIL，并做**逐文件 git 兜底查询** |

> 教训：门禁的**假阳性比漏检更危险** —— 一旦长期飘红，人就开始忽略它。
> 所以「无法判定」必须与「判定为坏」分开报告。

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
    """执行 git 子命令，返回 strip 后的 stdout；git 不可用/非仓库/出错时返回 None。

    **必须带 `-c core.quotepath=false`**：否则非 ASCII 路径（本项目文档大量中文名）会被 git
    输出成 `"docs/\\346\\226\\207..."` 形式（引号 + 八进制转义），与 `Path` 拼出的相对路径
    永远对不上 —— 表现为「所有中文文档的提交时间都查不到」（2026-09-21 实测的假 FAIL 根因）。
    """
    try:
        done = subprocess.run(
            ["git", "-c", "core.quotepath=false", *args],
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

    #: 孪生判定关心的路径 —— **`.html` 必须显式列出**：`git log -- README.md` 不匹配 README.html，
    #: 漏了它会让根目录两组孪生永远查不到提交时间（2026-09-21 修复的缺陷之一）。
    PATHS = ("docs", "README.md", "README.html", "PROGRESS.md", "PROGRESS.html")

    def __init__(self) -> None:
        self.dirty: set[str] | None = None
        self.commit_ts: dict[str, int] = {}
        self._fallback: dict[str, int | None] = {}
        self.git_available = False

        out = _git("status", "--porcelain", "--", *self.PATHS)
        if out is not None:
            self.git_available = True
            self.dirty = {line[3:].strip().strip('"') for line in out.splitlines() if len(line) > 3}

        log = _git(
            "log",
            f"-n{self.LOG_LIMIT}",
            "--format=@@%ct",
            "--name-only",
            "--no-renames",
            "--",
            *self.PATHS,
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
        """文件最后一次提交时间（unix 秒）；查不到返回 None。

        批量 `git log -n800` 未覆盖到的文件做**逐文件兜底** —— 只在 miss 时付 git 启动成本，
        且结果缓存，避免「老文件一律 unknown」把告警刷成噪声。
        """
        rel = rel.replace("\\", "/")
        if rel in self.commit_ts:
            return self.commit_ts[rel]
        if rel not in self._fallback:
            out = _git("log", "-1", "--format=%ct", "--no-renames", "--", rel) if self.git_available else None
            self._fallback[rel] = int(out) if out and out.isdigit() else None
        return self._fallback[rel]


def twin_status(md: Path, html: Path, state: TwinGitState) -> str:
    """判定 `.md` 与同名 `.html` 的同步状态，返回 **`ok` / `stale` / `unknown`**。

    为什么不直接比 `st_mtime`：git 在 rebase / checkout / stash 时会**按任意顺序**重写工作区
    文件，md 与 html 的 mtime 先后与「谁的内容更新」无关。2026-09-20 实测：一轮 rebase 之后
    三组「已提交且内容同步」的孪生对全部被判过期，真正的过期问题反而被噪声掩盖。

    判定顺序（快路径优先，避免为每组文件付出 git 启动成本）：

    1. md 不比 html 新 → `ok`（绝大多数文件走这条，零 git 查询）。
    2. md 更新且**有未提交改动** → `stale`：确实是刚改的 md、html 还没重生。
    3. md 更新、两侧都已提交 → 比「最后一次提交时间」：提交更晚才是 `stale`；
       同一次提交（孪生同批入库）视为同步 —— 这正是 rebase 后的情形。
    4. 其余情形（提交时间查不到、git 不可用、只有 html 脏）→ `unknown`：
       **「查不到」不等于「过期」**。历史上这里 `return True`，制造了 27 组假 FAIL。
    """
    if md.stat().st_mtime <= html.stat().st_mtime:
        return "ok"

    md_rel, html_rel = str(md.relative_to(ROOT)), str(html.relative_to(ROOT))
    md_dirty, html_dirty = state.is_dirty(md_rel), state.is_dirty(html_rel)
    if md_dirty is True:
        return "stale"
    if md_dirty is False and html_dirty is False:
        md_ts, html_ts = state.last_commit_ts(md_rel), state.last_commit_ts(html_rel)
        if md_ts is not None and html_ts is not None:
            return "stale" if md_ts > html_ts else "ok"
    return "unknown"


def check_twins(problems: list[str], warnings: list[str]) -> tuple[int, int, int]:
    """③ md/html 孪生同步（只查已有 html 孪生的 md，缺 html 不算失败）。

    返回 `(配对数, 过期数, 无法判定数)`。**无法判定只进 warnings，不计 FAIL** ——
    告警与失败必须分开，否则门禁一旦长期飘红就没人看了。
    """
    checked = stale = unknown = 0
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
    if not state.git_available:
        warnings.append("git 不可用：孪生判定退化为 unknown，本次只做 mtime 快筛（结果不可作准）")
    for md, html in pairs:
        checked += 1
        status = twin_status(md, html, state)
        if status == "stale":
            stale += 1
            problems.append(f"孪生过期：{md.relative_to(ROOT)} 比同名 .html 新（需重跑 md2html-report.py）")
        elif status == "unknown":
            unknown += 1
            warnings.append(
                f"孪生无从判定：{md.relative_to(ROOT)} 新于 .html，但提交时间未知 —— 请重跑 md2html-report.py 或确认内容已同步"
            )
    return checked, stale, unknown


def main() -> int:
    ap = argparse.ArgumentParser(description="文档一致性门禁")
    ap.add_argument("--no-twin", action="store_true", help="跳过 md/html 孪生同步检查")
    args = ap.parse_args()

    problems: list[str] = []
    warnings: list[str] = []

    n_ids, n_secs = check_anomaly_ids(problems)
    print("── 文档一致性门禁 ──")
    print(f"  异常条目 {n_ids} 条 · 章节号 {n_secs} 个 · 编号唯一性", end="")
    print(" OK" if not any("撞号" in p for p in problems) else " FAIL")

    if not args.no_twin:
        checked, stale, unknown = check_twins(problems, warnings)
        print(f"  孪生配对 {checked} 组 · 过期 {stale} 组 · 无从判定 {unknown} 组")
    else:
        print("  孪生检查：已跳过（--no-twin）")

    if problems:
        print()
        for p in problems:
            print(f"  FAIL {p}")
    if warnings:
        print()
        for w in warnings:
            print(f"  WARN {w}")

    if problems:
        print(f"\n合计: FAIL={len(problems)} · WARN={len(warnings)}")
        return 1

    print(f"\n合计: FAIL=0（编号唯一、孪生同步）" + (f" · WARN={len(warnings)}" if warnings else ""))
    print("  说明：本门禁只查「编号撞号」与「孪生过期」，不查文档内容准确性 ——")
    print("        内容仍须拿实测输出对（用例数 / 行数 / 端点计数）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
