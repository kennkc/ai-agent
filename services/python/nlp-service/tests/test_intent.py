"""Phase 2 意图识别测试（R2-07 / R2-08）

运行：
    cd services/python/nlp-service
    python -m pytest tests -v
    python tests/test_intent.py          # 不依赖 pytest 也可直接运行
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.intent import CASCADE, CORE_SCENARIOS, EVAL_CORPUS, TRAIN_CORPUS, RuleIntentEngine
from app.intent.l0_model import L0IntentClassifier


# ── R2-07 规则引擎 ──
def test_rule_engine_covers_at_least_ten_scenarios():
    engine = RuleIntentEngine()
    assert engine.scenario_count >= 10, "R2-07 要求扩展至 10+ 场景"


def test_rule_engine_hits_high_confidence():
    engine = RuleIntentEngine()
    hit = engine.recognize("计算 128 乘以 36")
    assert hit is not None
    assert hit.intent == "计算器"
    assert hit.confidence >= 0.8


def test_rule_engine_prefers_report_over_calculator():
    """多场景同时命中时取最高分，避免「算一下这批数据汇总」被误判为计算器"""
    engine = RuleIntentEngine()
    hit = engine.recognize("帮我算一下这批数据的汇总情况")
    assert hit is not None
    assert hit.intent == "汇总报告"


def test_rule_engine_returns_none_for_unknown_text():
    engine = RuleIntentEngine()
    assert engine.recognize("zzzz qqqq") is None


def test_rule_engine_uses_regex_patterns():
    engine = RuleIntentEngine()
    hit = engine.recognize("接口返回 HTTP 500，帮忙看下")
    assert hit is not None
    assert hit.intent == "故障排查"


# ── R2-08 L0 模型 ──
def test_l0_accuracy_on_holdout_corpus():
    """TC-04：留出集 ≥10 场景 × 5 样本，准确率 ≥ 85%"""
    accuracy, summary = L0IntentClassifier().accuracy(EVAL_CORPUS)
    assert summary["total"] >= 50, "样本量不足 50"
    assert accuracy >= 85.0, f"L0 准确率 {accuracy}% 未达 85% 门槛"


def test_l0_core_scenario_accuracy():
    model = L0IntentClassifier()
    core_eval = {k: v for k, v in EVAL_CORPUS.items() if k in CORE_SCENARIOS}
    accuracy, _summary = model.accuracy(core_eval)
    assert len(core_eval) == 10, "核心场景应为 10 个"
    assert accuracy >= 85.0, f"核心 10 场景准确率 {accuracy}% 未达 85%"


def test_l0_latency_below_50ms():
    """R2-08 验收：分类延迟 < 50ms"""
    _, summary = L0IntentClassifier().accuracy(EVAL_CORPUS)
    assert summary["latency_p99_ms"] < 50.0, f"P99 延迟 {summary['latency_p99_ms']}ms 超过 50ms"
    assert summary["latency_max_ms"] < 50.0, f"最大延迟 {summary['latency_max_ms']}ms 超过 50ms"


def test_l0_training_corpus_covers_all_eval_scenarios():
    assert set(TRAIN_CORPUS.keys()) == set(EVAL_CORPUS.keys())
    for scenario, samples in TRAIN_CORPUS.items():
        assert len(samples) >= 5, f"{scenario} 训练样本不足"
    for scenario, samples in EVAL_CORPUS.items():
        assert len(samples) >= 5, f"{scenario} 评测样本不足（TC-04 要求 5 条）"


# ── 级联 ──
def test_cascade_routes_to_rule_first():
    outcome = CASCADE.recognize("帮我计算 250 乘以 4")
    assert outcome.engine == "RULE"
    assert outcome.intent == "计算器"
    assert outcome.route == "rule_first"


def test_cascade_falls_back_to_l0_model():
    outcome = CASCADE.recognize("节点间的信号怎么传递")
    assert outcome.engine in {"L0", "FALLBACK"}
    assert outcome.route == "model_fallback"


def test_cascade_defaults_to_smalltalk_for_gibberish():
    outcome = CASCADE.recognize("qwerty asdfgh zxcvbn")
    assert outcome.intent in {"闲聊"}
    assert outcome.engine in {"L0", "FALLBACK"}


def test_cascade_outcome_serialisable():
    outcome = CASCADE.recognize("提醒我明天下午三点开会")
    payload = outcome.to_dict()
    for key in ("intent", "confidence", "engine", "intent_tag", "matched", "latency_ms", "route"):
        assert key in payload


def test_cascade_stats_reports_engine_profile():
    stats = CASCADE.stats()
    assert stats["rule_scenarios"] >= 10
    assert stats["l0"]["train_samples"] >= 100


def test_batch_recognition_returns_same_length():
    texts = ["你好", "计算 3 乘以 7", "今天北京天气", "催一下合同评审"]
    outcomes = CASCADE.recognize_batch(texts)
    assert len(outcomes) == len(texts)
    intents = [o.intent for o in outcomes]
    assert intents[0] == "情感闲聊"
    assert intents[1] == "计算器"
    assert intents[2] == "天气查询"


if __name__ == "__main__":
    failures = 0
    for name, fn in sorted(globals().items()):
        if not name.startswith("test_") or not callable(fn):
            continue
        try:
            fn()
            print(f"  PASS {name}")
        except AssertionError as exc:
            failures += 1
            print(f"  FAIL {name}: {exc}")
    accuracy, summary = L0IntentClassifier().accuracy(EVAL_CORPUS)
    print(f"\nL0 留出集准确率: {accuracy}% (样本 {summary['total']}) | P99 延迟 {summary['latency_p99_ms']}ms")
    for scenario, item in summary["per_scenario"].items():
        print(f"  {scenario}: {item['accuracy']}% ({item['correct']}/{item['total']})")
    sys.exit(1 if failures else 0)
