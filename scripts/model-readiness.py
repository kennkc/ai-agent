#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""NL 模型就绪检查（DEBT-010 / DEBT-011 触发点）。

把「嵌入/重排是否走真实模型」变成可检查、可操作的状态，而不是靠人工记忆。

检查项：
  1. sentence-transformers 是否可导入（代码用 find_spec 探测，缺失即降级）
  2. HuggingFace 缓存中是否存在 BAAI/bge-m3 与 BAAI/bge-reranker-v2-m3

用法：
  python scripts/model-readiness.py
"""
from __future__ import annotations

import os
from importlib.util import find_spec
from pathlib import Path

MODELS = [
    ("BAAI/bge-m3", "嵌入 DEBT-010", "1024 维语义向量"),
    ("BAAI/bge-reranker-v2-m3", "重排 DEBT-011", "交叉编码器精排"),
]


def hf_cache_root() -> Path:
    env = os.environ.get("HF_HOME") or os.environ.get("HUGGINGFACE_HUB_CACHE")
    if env:
        return Path(env)
    return Path.home() / ".cache" / "huggingface"


def model_cached(name: str, root: Path) -> bool:
    slug = "models--" + name.replace("/", "--")
    return (root / "hub" / slug).exists() or (root / slug).exists()


def main() -> int:
    st_ok = find_spec("sentence_transformers") is not None
    root = hf_cache_root()
    print("=" * 70)
    print("NL 模型就绪检查 · DEBT-010 / DEBT-011 触发点")
    print("=" * 70)
    print("sentence-transformers : " + ("已安装" if st_ok else "未安装"))
    print("HuggingFace 缓存根目录 : " + str(root))
    print("HF_ENDPOINT           : " + (os.environ.get("HF_ENDPOINT") or "(默认 huggingface.co)"))
    print("-" * 70)

    missing = []
    for name, purpose, note in MODELS:
        cached = model_cached(name, root)
        ready = st_ok and cached
        print(f"{name:28} {purpose:14} {note:16} {'就绪' if ready else '缺失'}")
        if not ready:
            missing.append(name)

    print("-" * 70)
    if not missing:
        print("结论：嵌入与重排均可走真实模型。")
        print("注意：切换后向量维度 768 -> 1024，必须全量重索引并重跑评测集。")
        return 0

    print("结论：以下模型未就绪，服务走确定性降级后端（degraded=true，诚实上报，不伪造）：")
    for name in missing:
        print("  - " + name)
    print()
    print("获取方式 A · 官方源：")
    print("  pip install sentence-transformers")
    print("  python -c \"from huggingface_hub import snapshot_download as d; d('BAAI/bge-m3'); d('BAAI/bge-reranker-v2-m3')\"")
    print()
    print("获取方式 B · 国内镜像（网络受限时）：")
    print("  $env:HF_ENDPOINT='https://hf-mirror.com'   # PowerShell")
    print("  pip install sentence-transformers")
    print("  python -c \"from huggingface_hub import snapshot_download as d; d('BAAI/bge-m3'); d('BAAI/bge-reranker-v2-m3')\"")
    print()
    print("切换前必读：向量维度 768 -> 1024，需全量重索引 + 评测回归（见技术债台账 DEBT-010/011）。")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())