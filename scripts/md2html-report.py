#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把项目进度报告类 Markdown 转换为自包含 HTML（企业报告风格）。

用法（必须使用 anaconda Python，托管 Python 无 markdown 包）：

    # 单个文件（输出同名 .html）
    E:/software/anaconda3/python.exe scripts/md2html-report.py "docs/项目进度日志报告/Phase2-阶段性报告.md"

    # 整个目录批量转换
    E:/software/anaconda3/python.exe scripts/md2html-report.py --all "docs/项目进度日志报告"

    # 指定输出路径 / 标题
    ... md2html-report.py in.md -o out.html -t "自定义标题"

特性：
  - 自动从首个 H1 提取文档标题
  - 生成右侧/顶部目录（TOC）
  - 表格、代码块、引用块、复选框样式化
  - 自包含（无外部依赖），可直接双击打开或打印
"""

import argparse
import html
import os
import sys
from datetime import datetime

try:
    import markdown
except ImportError:  # pragma: no cover
    sys.stderr.write(
        "ERROR: 未找到 markdown 包。请使用 anaconda Python：\n"
        "  E:/software/anaconda3/python.exe scripts/md2html-report.py <file.md>\n"
    )
    raise SystemExit(2)

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

EXTENSIONS = ["tables", "fenced_code", "toc", "attr_list", "sane_lists", "md_in_html"]
EXTENSION_CONFIGS = {"toc": {"title": "目录", "toc_depth": "2-4", "permalink": True}}

TEMPLATE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<style>
  :root {{
    --brand: #185fa5;
    --brand-soft: #e8f0fa;
    --ink: #1f2329;
    --ink-2: #545a63;
    --ink-3: #8a9099;
    --line: #e3e6eb;
    --bg: #f6f7f9;
    --card: #ffffff;
    --ok: #1a7f43;
    --warn: #b26a00;
    --code-bg: #f3f4f6;
  }}
  * {{ box-sizing: border-box; }}
  body {{
    margin: 0; padding: 0; background: var(--bg); color: var(--ink);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei",
                 "PingFang SC", "Hiragino Sans GB", "Source Han Sans SC", sans-serif;
    font-size: 15px; line-height: 1.75;
    -webkit-font-smoothing: antialiased;
  }}
  .topbar {{
    position: sticky; top: 0; z-index: 20; background: rgba(255,255,255,.92);
    backdrop-filter: blur(8px); border-bottom: 1px solid var(--line);
    padding: 10px 28px; display: flex; align-items: center; gap: 14px;
  }}
  .topbar .dot {{ width: 10px; height: 10px; border-radius: 50%; background: var(--brand); }}
  .topbar .name {{ font-weight: 600; font-size: 14px; }}
  .topbar .meta {{ margin-left: auto; color: var(--ink-3); font-size: 12px; }}
  .layout {{ display: flex; gap: 26px; max-width: 1320px; margin: 0 auto; padding: 26px 28px 80px; }}
  .tocnav {{
    flex: 0 0 260px; position: sticky; top: 70px; align-self: flex-start;
    max-height: calc(100vh - 100px); overflow: auto;
    background: var(--card); border: 1px solid var(--line); border-radius: 10px;
    padding: 16px 18px; font-size: 13px;
  }}
  /* markdown 的 toc 扩展会自行输出一个 div.toc 包装，这里取消它的卡片样式避免双边框 */
  .tocnav .toc {{ background: none; border: none; padding: 0; margin: 0; }}
  .tocnav .toctitle {{ font-weight: 700; color: var(--brand); margin-bottom: 10px; font-size: 13px; display: block; }}
  .tocnav ul {{ list-style: none; margin: 0; padding-left: 12px; }}
  .tocnav > .toc > ul {{ padding-left: 0; }}
  .tocnav li {{ margin: 3px 0; }}
  .tocnav a {{ color: var(--ink-2); text-decoration: none; display: inline-block; padding: 2px 0; }}
  .tocnav a:hover {{ color: var(--brand); }}
  .doc {{
    flex: 1 1 auto; min-width: 0; background: var(--card);
    border: 1px solid var(--line); border-radius: 12px; padding: 38px 46px 60px;
  }}
  .doc h1 {{
    font-size: 27px; line-height: 1.35; margin: 0 0 6px;
    padding-bottom: 14px; border-bottom: 3px solid var(--brand);
  }}
  .doc h2 {{
    font-size: 20px; margin: 42px 0 14px; padding-left: 12px;
    border-left: 4px solid var(--brand); line-height: 1.4;
  }}
  .doc h3 {{ font-size: 16.5px; margin: 30px 0 10px; color: #2b3138; }}
  .doc h4 {{ font-size: 15px; margin: 22px 0 8px; color: var(--ink-2); }}
  .doc p {{ margin: 10px 0; }}
  .doc a {{ color: var(--brand); }}
  .doc hr {{ border: none; border-top: 1px solid var(--line); margin: 34px 0; }}
  .doc table {{
    border-collapse: collapse; width: 100%; margin: 16px 0; font-size: 13.5px;
    display: block; overflow-x: auto;
  }}
  .doc thead th {{
    background: var(--brand-soft); color: #123f6d; font-weight: 600;
    text-align: left; white-space: nowrap;
  }}
  .doc th, .doc td {{ border: 1px solid var(--line); padding: 8px 11px; vertical-align: top; }}
  .doc tbody tr:nth-child(even) {{ background: #fafbfc; }}
  .doc code {{
    background: var(--code-bg); padding: 1.5px 6px; border-radius: 4px;
    font-family: "Cascadia Mono", Consolas, "JetBrains Mono", Menlo, monospace; font-size: 13px;
  }}
  .doc pre {{
    background: #1f2329; color: #e6e8eb; padding: 15px 18px; border-radius: 8px;
    overflow-x: auto; font-size: 12.8px; line-height: 1.65;
  }}
  .doc pre code {{ background: none; color: inherit; padding: 0; font-size: inherit; }}
  .doc blockquote {{
    margin: 16px 0; padding: 12px 18px; background: #fffaf0;
    border-left: 4px solid var(--warn); color: #6b4a00; border-radius: 0 6px 6px 0;
  }}
  .doc blockquote p {{ margin: 5px 0; }}
  .doc ul, .doc ol {{ padding-left: 26px; }}
  .doc li {{ margin: 5px 0; }}
  .doc li input[type=checkbox] {{ margin-right: 7px; }}
  .doc img {{ max-width: 100%; }}
  .wikilink {{ color: var(--brand); }}
  .footer {{ max-width: 1320px; margin: 0 auto; padding: 0 28px 50px;
            color: var(--ink-3); font-size: 12px; text-align: center; }}
  @media (max-width: 980px) {{
    .layout {{ flex-direction: column; padding: 18px 16px 60px; }}
    .tocnav {{ position: static; flex: none; max-height: none; }}
    .doc {{ padding: 24px 20px 40px; }}
  }}
  @media print {{
    body {{ background: #fff; }}
    .topbar, .tocnav {{ display: none; }}
    .layout {{ display: block; padding: 0; }}
    .doc {{ border: none; padding: 0; }}
    .doc h2 {{ page-break-after: avoid; }}
    .doc table, .doc pre, .doc blockquote {{ page-break-inside: avoid; }}
  }}
</style>
</head>
<body>
<div class="topbar">
  <span class="dot"></span>
  <span class="name">Agent-Lifeform · 项目进度日志报告</span>
  <span class="meta">生成于 {generated} · 源文件 {source}</span>
</div>
<div class="layout">
  <nav class="tocnav">{toc}</nav>
  <main class="doc">{body}</main>
</div>
<div class="footer">本页由 scripts/md2html-report.py 自动生成 · 修改请编辑同名 .md 后重新生成</div>
</body>
</html>
"""


def extract_title(md_text: str, fallback: str) -> str:
    for line in md_text.splitlines():
        if line.startswith("# "):
            return line[2:].strip()
    return fallback


def convert_one(md_path: str, out_path: str = None, title: str = None) -> str:
    md_path = os.path.abspath(md_path)
    if not os.path.isfile(md_path):
        raise FileNotFoundError(md_path)
    with open(md_path, encoding="utf-8") as fh:
        md_text = fh.read()

    doc_title = title or extract_title(md_text, os.path.basename(md_path))
    md = markdown.Markdown(extensions=EXTENSIONS, extension_configs=EXTENSION_CONFIGS)
    body = md.convert(md_text)
    toc_html = getattr(md, "toc", "") or "<div class='toctitle'>目录</div><p>（本文档无二级以上标题）</p>"

    page = TEMPLATE.format(
        title=html.escape(doc_title),
        body=body,
        toc=toc_html,
        generated=datetime.now().strftime("%Y-%m-%d %H:%M"),
        source=html.escape(os.path.basename(md_path)),
    )

    if out_path is None:
        out_path = os.path.splitext(md_path)[0] + ".html"
    with open(out_path, "w", encoding="utf-8") as fh:
        fh.write(page)
    return out_path


def main() -> int:
    parser = argparse.ArgumentParser(description="Markdown -> 自包含 HTML 报告")
    parser.add_argument("target", help="Markdown 文件路径，或配合 --all 传入目录")
    parser.add_argument("--all", action="store_true", help="把目标目录下所有 .md（不含 README）批量转换")
    parser.add_argument("-o", "--output", help="输出 HTML 路径（仅单文件模式）")
    parser.add_argument("-t", "--title", help="覆盖文档标题")
    args = parser.parse_args()

    if args.all:
        target = os.path.abspath(args.target)
        if not os.path.isdir(target):
            sys.stderr.write(f"ERROR: 不是目录：{target}\n")
            return 2
        names = sorted(n for n in os.listdir(target) if n.endswith(".md") and n.lower() != "readme.md")
        if not names:
            sys.stderr.write("ERROR: 目录下没有可转换的 .md 文件（README.md 已排除，可单文件模式转换）\n")
            return 2
        for name in names:
            out = convert_one(os.path.join(target, name))
            print(f"[OK] {name} -> {os.path.basename(out)}")
    else:
        out = convert_one(args.target, args.output, args.title)
        print(f"[OK] {os.path.basename(args.target)} -> {os.path.basename(out)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
