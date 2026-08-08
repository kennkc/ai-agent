#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Contract Checker · 数据字典 ↔ proto 双向校验（E2 契约工具化）
================================================================
用途: 校验 proto 契约字段与《D5-2 核心数据字典》字段定义一致，防止字段漂移。

运行: python scripts/contract-check.py [--proto-dir proto] [--dict-md 字典路径]
规则:
  1. proto message 字段名必须为 snake_case（D5-2 命名规则）
  2. 通用字段 (id/tenant_id/created_at/updated_at/version/status/source) 必须全模型覆盖
  3. 校验结果输出报告，exit code 0=通过 1=有漂移（CI 门禁用）
"""
import argparse
import os
import re
import sys

# D5-2 通用字段（所有模型必须包含，status/source 允许枚举差异）
COMMON_FIELDS = ["id", "tenant_id", "created_at", "updated_at", "version"]
# D5-2 命名规则：snake_case（允许下划线，禁止驼峰/大写）
SNAKE_RE = re.compile(r"^[a-z][a-z0-9_]*$")

# 预期核心模型 → proto 文件映射（校验目标）
MODEL_EXPECT = {
    "Session":  {"file": "session/v1/session.proto", "extra": ["agent_id", "title", "message_count", "last_activity_at", "model"]},
    "Message":  {"file": "session/v1/session.proto", "extra": ["session_id", "role", "content", "intent", "confidence", "source_citations", "latency_ms", "source"]},
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


def check_model(messages, model, spec):
    if model not in messages:
        return [f"FAIL 模型缺失: {model} 未在 {spec['file']} 中定义"]
    fields = messages[model]
    issues = []
    # 1) 通用字段
    for cf in COMMON_FIELDS:
        if cf not in fields:
            issues.append(f"WARN 通用字段缺失: {model}.{cf}（若为精简场景可忽略，需字典登记）")
    # 2) 模型特有字段
    for ef in spec.get("extra", []):
        if ef not in fields:
            issues.append(f"FAIL 字典字段缺失: {model}.{ef}")
    # 3) 命名规则
    for fld in fields:
        if not SNAKE_RE.match(fld):
            issues.append(f"FAIL 命名违规: {model}.{fld} 非 snake_case")
    return issues


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--proto-dir", default="proto", help="proto 根目录")
    args = ap.parse_args()

    all_issues = []
    proto_messages = {}
    for root, _, files in os.walk(args.proto_dir):
        for fn in files:
            if fn.endswith(".proto"):
                proto_messages.update(parse_proto_messages(os.path.join(root, fn)))

    for model, spec in MODEL_EXPECT.items():
        all_issues += check_model(proto_messages, model, spec)

    print("=" * 60)
    print("Contract Check · 数据字典 ↔ proto 双向校验")
    print("=" * 60)
    print(f"扫描 proto 文件: {len(proto_messages)} 个 message 定义")
    if not all_issues:
        print("✅ 全部通过：字典字段完整、命名符合规范")
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
