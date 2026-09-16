#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验 docs/java-services/ 是否覆盖了 services/java/ 下的全部 Java 源文件。

用途：docs/java-services 的文件清单表必须与代码保持一致。新增/删除 Java 文件后
运行本脚本，即可发现文档缺口（而非依赖人工比对）。

用法（任意 Python 3.9+ 即可，无第三方依赖）：
    python scripts/java-doc-coverage.py            # 校验并输出报告
    python scripts/java-doc-coverage.py --list     # 额外列出已覆盖的文件

退出码：0 = 全部覆盖；1 = 存在未覆盖的文件（可用于 CI）。
"""
from __future__ import annotations

import argparse
import os
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_ROOT = os.path.join(REPO_ROOT, "services", "java")
DOC_ROOT = os.path.join(REPO_ROOT, "docs", "java-services")

# 生成代码不计入逐文件覆盖（由 02-proto-contracts.md 按契约维度统一说明）
GENERATED_MARKER = "proto-contracts"


def java_files() -> list[str]:
    found: list[str] = []
    for dirpath, dirnames, filenames in os.walk(SRC_ROOT):
        dirnames[:] = [d for d in dirnames if d not in ("target", ".git")]
        for name in filenames:
            if name.endswith(".java"):
                found.append(os.path.join(dirpath, name).replace("\\", "/"))
    return sorted(found)


def doc_text() -> str:
    if not os.path.isdir(DOC_ROOT):
        sys.stderr.write(f"ERROR: 文档目录不存在: {DOC_ROOT}\n")
        sys.exit(2)
    chunks: list[str] = []
    for name in sorted(os.listdir(DOC_ROOT)):
        if name.endswith(".md"):
            with open(os.path.join(DOC_ROOT, name), encoding="utf-8") as handle:
                chunks.append(handle.read())
    return "\n".join(chunks)


def module_of(path: str) -> str:
    rel = os.path.relpath(path, SRC_ROOT).replace("\\", "/")
    return rel.split("/")[0]


def main() -> int:
    parser = argparse.ArgumentParser(description="校验 Java 源码的文档覆盖率")
    parser.add_argument("--list", action="store_true", help="列出已覆盖的文件")
    args = parser.parse_args()

    docs = doc_text()
    files = java_files()
    generated = [f for f in files if GENERATED_MARKER in f]
    covered_targets = [f for f in files if GENERATED_MARKER not in f]

    missing = [f for f in covered_targets if os.path.basename(f) not in docs]

    by_module: dict[str, list[int]] = {}
    for path in covered_targets:
        mod = module_of(path)
        stat = by_module.setdefault(mod, [0, 0])
        stat[0] += 1
        if path not in missing:
            stat[1] += 1

    print("Java 源码文档覆盖率报告")
    print("=" * 56)
    for mod in sorted(by_module):
        total, done = by_module[mod]
        flag = "OK " if total == done else "!! "
        print(f"  {flag}{mod:18s} {done}/{total}")
    print("-" * 56)
    print(f"  业务源文件（含测试）   {len(covered_targets) - len(missing)}/{len(covered_targets)}")
    print(f"  生成代码（不逐文件展开） {len(generated)} 个，见 docs/java-services/02-proto-contracts.md")

    if args.list:
        print("\n已覆盖文件：")
        for path in covered_targets:
            if path not in missing:
                print(f"  {os.path.relpath(path, REPO_ROOT).replace(chr(92), '/')}")

    if missing:
        print(f"\n未覆盖 {len(missing)} 个文件（请在对应模块文档的文件清单表中登记）：")
        for path in missing:
            print(f"  - {os.path.relpath(path, REPO_ROOT).replace(chr(92), '/')}")
        return 1

    print("\n[OK] docs/java-services 已覆盖全部业务源文件。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
