# -*- coding: utf-8 -*-
"""Phase 4 评测集（开发设计 §10：50 条问答对，意图 + 回答质量）。

评测口径（**不美化**：数字来自实跑，不是设计值）

1. **意图准确率** —— 取**级联引擎**（规则 → L0）的生产口径，门槛 ≥90%（需求 §DoD）。
   注意不要拿 L0 单独准确率当生产指标：L0 只是兜底，线上走的是级联。
2. **有据率** —— 注入检索替身（给定资料片段）后，回答中必须出现资料原文关键词的占比，门槛 ≥90%。
   它测的是「回答是否基于资料」而不是「回答是否漂亮」，因为当前生成器是模板降级后端。
3. **来源完整性** —— 产生了回答就必须有来源，100%（R4-08 硬要求，否则是幻觉的温床）。

评测集与分类器同源产出，故另有**独立留出集**（`app.intent.corpus.EVAL_CORPUS`，
60 条、与本次修复无交集）作为交叉验证，避免"自己出的题自己考满分"。
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.brain.pipeline import RagPipeline
from app.brain.retrieval import RetrievalOutcome
from app.intent import CASCADE, EVAL_CORPUS

EVALSET = json.loads((Path(__file__).with_name("evalset_phase4.json")).read_text(encoding="utf-8"))

INTENT_FLOOR = 90.0
GROUNDED_FLOOR = 0.90


class _StubRetriever:
    """检索替身：按评测项给定资料片段返回，用于隔离"回答是否基于资料"。

    相似度给 0.9（高于缺口阈值 0.35），确保走生成分支而不是缺口拒答分支。
    """

    def __init__(self, passage: str, title: str = "评测资料"):
        self.passage = passage
        self.title = title

    def retrieve(self, query: str, tenant_id: str = "default", top_k: int = 5,
                 timeout: float | None = None) -> RetrievalOutcome:
        return RetrievalOutcome(chunks=[{
            "chunk_id": "eval-1", "doc_id": "eval-doc", "title": self.title,
            "content": self.passage, "score": 0.9, "rerank_score": 0.9,
        }])


def test_evalset_size_meets_design():
    """开发设计 §10 要求 50 条问答对。"""
    assert len(EVALSET) == 50, f"评测集规模 {len(EVALSET)} 条，未达设计要求的 50 条"
    for item in EVALSET:
        assert item["question"].strip(), f"{item['id']} 缺 question"
        assert item["intent"], f"{item['id']} 缺 intent"
        assert item["passage"].strip(), f"{item['id']} 缺 passage"
        assert item["must_contain"], f"{item['id']} 缺 must_contain"


def test_intent_accuracy_above_90_percent():
    """意图准确率 ≥90%（级联生产口径）。"""
    hit = [i for i in EVALSET if CASCADE.recognize(i["question"]).intent == i["intent"]]
    accuracy = 100.0 * len(hit) / len(EVALSET)
    missed = [(i["id"], i["intent"], CASCADE.recognize(i["question"]).intent)
              for i in EVALSET if CASCADE.recognize(i["question"]).intent != i["intent"]]
    assert accuracy >= INTENT_FLOOR, f"意图准确率 {accuracy:.2f}% 低于 {INTENT_FLOOR}%，漏判：{missed}"


def test_intent_accuracy_cross_checked_on_holdout_corpus():
    """交叉验证：留出集（60 条，与评测集不重叠）同样需 ≥90%，防止评测集过拟合。"""
    hit = total = 0
    for label, texts in EVAL_CORPUS.items():
        for text in texts:
            total += 1
            hit += 1 if CASCADE.recognize(text).intent == label else 0
    accuracy = 100.0 * hit / total
    assert accuracy >= INTENT_FLOOR, f"留出集准确率 {accuracy:.2f}%（{hit}/{total}）低于 {INTENT_FLOOR}%"


def test_answer_grounded_and_sources_annotated():
    """回答有据率 ≥90% 且来源标注 100%（R4-08）。"""
    grounded = 0
    failures: list[str] = []
    for item in EVALSET:
        pipeline = RagPipeline(retriever=_StubRetriever(item["passage"]), cache=_NoCache())
        result = pipeline.run(item["question"], use_cache=False)
        answer = result.answer or ""
        if any(token in answer for token in item["must_contain"]):
            grounded += 1
        else:
            failures.append(f"{item['id']} 回答未含资料关键词 {item['must_contain']}：{answer[:60]}")
        assert result.sources, f"{item['id']} 产生了回答却没有来源标注（R4-08 硬要求）"
        # 缺口判定与生成结果必须自洽：给了高分资料就不该报「信息不足」
        assert "information_gap" not in result.degraded_reasons, f"{item['id']} 有资料却判缺口，口径不自洽"
    ratio = grounded / len(EVALSET)
    assert ratio >= GROUNDED_FLOOR, f"有据率 {ratio:.2%} 低于 {GROUNDED_FLOOR:.0%}：{failures[:5]}"


def test_template_backend_honestly_marked_degraded():
    """模板后端必须标 degraded（不冒充真实模型输出）。"""
    pipeline = RagPipeline(retriever=_StubRetriever("大脑层负责意图识别与规划。"), cache=_NoCache())
    result = pipeline.run("大脑层负责什么", use_cache=False)
    assert result.generator in {"template", "model"}
    if result.generator == "template":
        assert result.degraded is True
        assert "llm_template_backend" in result.degraded_reasons


def test_decision_chain_has_six_steps_and_verify():
    """决策链必须 6 步且含自校验（X4 要求），并落审计可回放。"""
    pipeline = RagPipeline(retriever=_StubRetriever("躯体层提供检索与存储。"), cache=_NoCache())
    result = pipeline.run("躯体层提供什么能力", use_cache=False)
    steps = [node.get("step") for node in result.chain]
    for required in ("intent", "plan", "retrieve", "generate", "verify", "annotate"):
        assert required in steps, f"决策链缺 {required} 步：{steps}"
    assert result.decision_id, "决策链必须带 decision_id 供回放"
    record = pipeline.audit.replay(result.decision_id, result.tenant_id)
    assert record is not None, "决策未落审计：无法回放"
    assert record["chain"], "审计记录缺链步"


class _NoCache:
    """评测用空缓存：避免命中历史答案而绕过真实生成链路。"""

    def lookup(self, *args, **kwargs):
        return None

    def store(self, *args, **kwargs):
        return None


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-q"]))
