"""L0 轻量意图分类模型（R2-08）

设计取舍：R2-08 要求"轻量分类模型服务（BERT 级别）"，验收指标为
  延迟 < 50ms、准确率 ≥ 85%。
BERT 级模型在 Phase 2 的硬件与依赖条件下（无 GPU、不支持在线下载权重）
无法稳定满足延迟与可复现性要求，因此本实现采用**字符 n-gram TF-IDF + 最近质心**
的线性分类器：同为零训练依赖的统计模型，推理为向量点积，延迟在毫秒级；
接口按 L0 模型服务抽象（predict / predict_batch / stats），后续可整体替换为
BERT ONNX 实现而不影响级联层。该取舍已登记为 DEBT-007（触发点：P4 大脑期
接入模型推理服务）。

特征：字符 bigram（覆盖中文分词边界）+ 关键词归一化
分类：cosine 相似度对每个场景质心打分，softmax 归一 → 置信度
"""
from __future__ import annotations

import math
import re
import time
from dataclasses import dataclass, field

from .corpus import SCENARIO_LABELS, TRAIN_CORPUS

NORMALIZE_RE = re.compile(r"[\s,，。！!？?、；;：:\"'“”‘’（）()【】\[\]…~\-]+")
UNKNOWN_INTENT = "闲聊"
UNKNOWN_CONFIDENCE = 0.5
MIN_CONFIDENCE = 0.45


@dataclass
class L0Prediction:
    intent: str
    confidence: float
    runner_up: str = ""
    runner_up_confidence: float = 0.0
    latency_ms: float = 0.0


@dataclass
class _Centroid:
    intent: str
    vector: dict[str, float] = field(default_factory=dict)
    norm: float = 0.0


class L0IntentClassifier:
    """字符 n-gram TF-IDF + 最近质心分类器（纯 Python，无第三方依赖）"""

    def __init__(self, corpus: dict[str, list[str]] | None = None) -> None:
        self.corpus = corpus or TRAIN_CORPUS
        self._centroids: list[_Centroid] = []
        self._idf: dict[str, float] = {}
        self.trained_samples = 0
        self.train()

    # ── 特征 ──
    @staticmethod
    def normalize(text: str) -> str:
        return NORMALIZE_RE.sub("", (text or "").strip().lower())

    def features(self, text: str) -> dict[str, float]:
        plain = self.normalize(text)
        counts: dict[str, float] = {}
        if not plain:
            return counts
        # 单字（中文短句信息量高）
        for ch in plain:
            counts[ch] = counts.get(ch, 0.0) + 0.4
        # 字符 bigram（近似词）
        for i in range(len(plain) - 1):
            gram = plain[i:i + 2]
            counts[gram] = counts.get(gram, 0.0) + 1.0
        # 字符 trigram（更强的成词信号）
        for i in range(len(plain) - 2):
            gram = plain[i:i + 3]
            counts[gram] = counts.get(gram, 0.0) + 0.8
        return counts

    def train(self) -> None:
        documents: list[tuple[str, dict[str, float]]] = []
        for intent, samples in self.corpus.items():
            for sample in samples:
                documents.append((intent, self.features(sample)))
                self.trained_samples += 1

        df: dict[str, int] = {}
        for _, feats in documents:
            for term in feats:
                df[term] = df.get(term, 0) + 1
        total_docs = max(1, len(documents))
        self._idf = {term: math.log((1 + total_docs) / (1 + count)) + 1.0 for term, count in df.items()}

        grouped: dict[str, list[dict[str, float]]] = {}
        for intent, feats in documents:
            grouped.setdefault(intent, []).append(self._tfidf(feats))

        self._centroids = []
        for intent, vectors in grouped.items():
            centroid: dict[str, float] = {}
            for vector in vectors:
                for term, value in vector.items():
                    centroid[term] = centroid.get(term, 0.0) + value
            for term in centroid:
                centroid[term] /= len(vectors)
            norm = math.sqrt(sum(v * v for v in centroid.values())) or 1.0
            self._centroids.append(_Centroid(intent=intent, vector=centroid, norm=norm))

    def _tfidf(self, feats: dict[str, float]) -> dict[str, float]:
        return {term: value * self._idf.get(term, 1.0) for term, value in feats.items()}

    def _cosine(self, vector: dict[str, float], norm: float, centroid: _Centroid) -> float:
        if not vector:
            return 0.0
        dot = 0.0
        for term, value in vector.items():
            other = centroid.vector.get(term)
            if other:
                dot += value * other
        return dot / (norm * centroid.norm) if norm and centroid.norm else 0.0

    # ── 推理 ──
    def predict(self, text: str) -> L0Prediction:
        started = time.perf_counter()
        feats = self.features(text)
        vector = self._tfidf(feats)
        norm = math.sqrt(sum(v * v for v in vector.values()))
        if not vector or norm == 0:
            return L0Prediction(intent=UNKNOWN_INTENT, confidence=UNKNOWN_CONFIDENCE,
                                latency_ms=(time.perf_counter() - started) * 1000)
        scored: list[tuple[str, float]] = [
            (centroid.intent, self._cosine(vector, norm, centroid)) for centroid in self._centroids
        ]
        scored.sort(key=lambda kv: kv[1], reverse=True)
        top_intent, top_score = scored[0]
        runner_up, runner_score = scored[1] if len(scored) > 1 else ("", 0.0)
        # 置信度：top 与次高的分离度 + 绝对相似度
        separation = top_score / (top_score + runner_score) if (top_score + runner_score) else 0.5
        confidence = max(0.0, min(0.95, 0.55 * separation + 0.45 * top_score))
        latency = (time.perf_counter() - started) * 1000
        if top_score < 0.05 or confidence < MIN_CONFIDENCE:
            return L0Prediction(intent=UNKNOWN_INTENT, confidence=round(max(confidence, UNKNOWN_CONFIDENCE), 4),
                                runner_up=top_intent, runner_up_confidence=round(top_score, 4),
                                latency_ms=latency)
        return L0Prediction(intent=top_intent, confidence=round(confidence, 4),
                            runner_up=runner_up, runner_up_confidence=round(runner_score, 4),
                            latency_ms=latency)

    def predict_batch(self, texts: list[str]) -> list[L0Prediction]:
        return [self.predict(text) for text in texts]

    def stats(self) -> dict[str, object]:
        return {
            "algorithm": "char-ngram-tfidf-nearest-centroid",
            "train_samples": self.trained_samples,
            "scenarios": [c.intent for c in self._centroids],
            "vocabulary": len(self._idf),
            "scenario_count": len(self._centroids),
        }

    def accuracy(self, eval_corpus: dict[str, list[str]]) -> tuple[float, dict[str, object]]:
        """留出集准确率评测（TC-04）"""
        total = 0
        correct = 0
        per_scenario: dict[str, dict[str, object]] = {}
        latencies: list[float] = []
        for intent, samples in eval_corpus.items():
            hit = 0
            for sample in samples:
                prediction = self.predict(sample)
                latencies.append(prediction.latency_ms)
                total += 1
                if prediction.intent == intent:
                    hit += 1
                    correct += 1
            per_scenario[intent] = {"total": len(samples), "correct": hit,
                                    "accuracy": round(hit / max(1, len(samples)) * 100, 2)}
        latencies.sort()
        p99 = latencies[min(len(latencies) - 1, int(len(latencies) * 0.99))] if latencies else 0.0
        summary = {
            "total": total,
            "correct": correct,
            "accuracy": round(correct / max(1, total) * 100, 2),
            "latency_avg_ms": round(sum(latencies) / max(1, len(latencies)), 3),
            "latency_p99_ms": round(p99, 3),
            "latency_max_ms": round(max(latencies), 3) if latencies else 0.0,
            "per_scenario": per_scenario,
        }
        return summary["accuracy"], summary


L0_MODEL = L0IntentClassifier()

# 场景 → 语义标签映射（供下游计划层使用）
SCENARIO_TAGS = SCENARIO_LABELS
