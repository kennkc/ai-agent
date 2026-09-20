"""跨服务调用的**预算（deadline）**单点定义与执行工具。

为什么需要它
────────────
超时值天然分散在每个服务里，各自看都合理，**相遇即错**：

* 上游给 5s、下游内部读超时 10s → 上游先把「慢」说成「不可用」；
* 下游自己不设总上限、重试再放大 → 上游无论给多少都可能不够。

2026-09-19 实测：wp-bff 给 `/api/tool/sandbox` 的预算是 5s，服务侧冷探测 5.7s，
结果前端把「慢」显示成「沙箱不可用」。权威登记表见 `contracts/timeout-budget.yaml`，
门禁 `scripts/timeout-budget-check.py`。

本模块承担两件事
────────────────
1. **总预算（total deadline）** —— 一次请求从进入到返回的最长时间上限。
   级联 / 重试**只在预算内调度**，而不是「各级超时 × 重试次数」的乘积：
   后者会随层级与重试数增长而膨胀，让上游无论调多大都可能不够，而且会把
   「快速失败并降级」变成「长时间等待后仍降级」（REC-01）。
2. **按预算执行** —— :func:`run_with_budget` 在预算内跑一个同步可调用对象，
   超时如实抛 :class:`BudgetExceeded`，由调用方转成 504 + `AGENT_TIMEOUT`
   （与 Java 四服务的 `ErrorCode.AGENT_TIMEOUT` 同名，全平台一个口径），
   **绝不静默返回空结果**冒充「没有数据」。

诚实边界
────────
Python 无法强杀线程：超时后工作线程仍会跑完，结果被丢弃。这是**有意的取舍** ——
保证调用方按时返回并如实告知，代价是后端可能空转。
真正需要强杀的场景应由子进程 + kill 承担（见 tool-executor 的沙箱后端）。
"""
from __future__ import annotations

import logging
import os
import time
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from concurrent.futures import TimeoutError as FuturesTimeout
from typing import Any

logger = logging.getLogger("nlp-service.budget")

# ─────────── 具名预算（毫秒）───────────
#
# 与 `contracts/timeout-budget.yaml` 的 edges 一一对应；改这里必须同改表并跑门禁。
# 命名规则：<调用方视角的用途>_BUDGET_MS。

# R4-06 问答端到端总预算：检索 + 生成**共享**这一个上限。
# 2026-09-19 GAP-01 闭合（fix_hint ②）：25s → 20s。原 25s 大于级联推导最坏 22s，
# 预算从不构成约束，等于没有；收紧后它成为**真正的硬上限** —— 级联（L2 8s×2 + L1 3s×2 = 22s）
# 在预算内调度，超限即被截断。下游最坏耗时因此从「推导值 22s」变成**常量 20s**，
# session 侧 30s 读超时（BRAIN_TIMEOUT）对它恰好 1.5x（登记表 TB-11）。
# 检索单步另受 RETRIEVAL_BUDGET_MS 约束；检索若吃满 15s，生成仍剩 5s（够 L1 单次 3s），
# 这正是 deadline 的本意：下游病态变慢时**更快失败**，而不是把「慢」传染给上游。
BRAIN_TOTAL_BUDGET_MS = int(os.getenv("BRAIN_TOTAL_BUDGET_MS", "20000"))

# /api/nlp/embed：体层入库/检索的向量化通道。原为「无独立超时」，
# 挂起时只能靠 body-service 的 30s 读超时兜底（GAP-03）。
EMBED_BUDGET_MS = int(os.getenv("EMBED_BUDGET_MS", "12000"))

# /api/nlp/ocr：感官层图片识别通道。原为「无独立超时」（GAP-04）。
OCR_BUDGET_MS = int(os.getenv("OCR_BUDGET_MS", "12000"))

# RAG 检索单步预算（body-service）。2026-09-19 GAP-05 闭合：8s → 15s（下游最坏
# 由 body 侧端到端预算 RETRIEVAL_TOTAL_BUDGET_MS = 10s 收口，15s / 10s = 1.5x）。
# **这是检索超时的唯一口径** —— retrieval.py 的 DEFAULT_TIMEOUT 由它派生（秒），
# 不再保留第二套 RETRIEVAL_TIMEOUT_SECONDS 环境变量（两套口径是潜在漂移源）。
RETRIEVAL_BUDGET_MS = int(os.getenv("RETRIEVAL_BUDGET_MS", "15000"))


class BudgetExceeded(RuntimeError):
    """操作超出其预算 —— **这是「慢」，不是「没有」**，调用方必须如实区分。"""

    def __init__(self, operation: str, budget_ms: int, elapsed_ms: int = 0) -> None:
        self.operation = operation
        self.budget_ms = budget_ms
        self.elapsed_ms = elapsed_ms
        super().__init__(f"{operation} exceeded budget {budget_ms}ms (elapsed {elapsed_ms}ms)")


def run_with_budget(
    fn: Callable[[], Any],
    budget_ms: int | None,
    *,
    operation: str = "operation",
) -> Any:
    """在预算内执行 `fn`；超时抛 :class:`BudgetExceeded`（不返回值、不返回空）。

    `budget_ms` 为 None 或 <= 0 时不设限（用于「未配置」的显式情形）。
    异常按原样向上传播 —— 本函数只负责**时间**，不改写业务错误语义。
    """
    if budget_ms is None or budget_ms <= 0:
        return fn()

    started = time.monotonic()
    executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix=f"budget-{operation}")
    try:
        future = executor.submit(fn)
        return future.result(timeout=budget_ms / 1000.0)
    except FuturesTimeout as exc:
        elapsed = int((time.monotonic() - started) * 1000)
        logger.warning("budget exceeded: %s budget=%sms elapsed=%sms", operation, budget_ms, elapsed)
        raise BudgetExceeded(operation, budget_ms, elapsed) from exc
    finally:
        # 不 wait：工作线程可能仍在跑（Python 无法强杀），等待只会把超时重新变成阻塞。
        executor.shutdown(wait=False, cancel_futures=True)


class Deadline:
    """一次请求的总预算；级联 / 重试每一步都来问它「还能用多久」。

    与「给每一级各配一个超时」的区别：那些超时**互相独立**，最坏耗时是它们的和
    （再乘重试次数）；本对象给出的是**共享上限**，谁先用掉就是谁的，用尽即止。
    """

    def __init__(self, budget_ms: int | None, operation: str = "operation") -> None:
        self.budget_ms = int(budget_ms) if budget_ms else None
        self.operation = operation
        self._started = time.monotonic()
        self._deadline = (
            self._started + self.budget_ms / 1000.0 if self.budget_ms else None
        )
        self.exhausted = False

    def elapsed_ms(self) -> int:
        return int((time.monotonic() - self._started) * 1000)

    def remaining_ms(self) -> int | None:
        """剩余预算；未设预算时返回 None（表示"不限"）。"""
        if self._deadline is None:
            return None
        return max(0, int((self._deadline - time.monotonic()) * 1000))

    def expired(self) -> bool:
        remaining = self.remaining_ms()
        return remaining is not None and remaining <= 0

    def clamp(self, timeout_s: float) -> float | None:
        """把某个「单步超时」收进剩余预算内，返回实际可用秒数。

        返回 <= 0 表示预算已耗尽 —— 调用方**不应再发起调用**，直接走降级。
        """
        if self._deadline is None:
            return timeout_s
        remaining = (self._deadline - time.monotonic())
        if remaining <= 0:
            self.exhausted = True
            return 0.0
        return min(float(timeout_s), remaining)

    def note_exhausted(self) -> None:
        self.exhausted = True

    def to_dict(self) -> dict:
        return {
            "operation": self.operation,
            "budget_ms": self.budget_ms,
            "elapsed_ms": self.elapsed_ms(),
            "remaining_ms": self.remaining_ms(),
            "exhausted": self.exhausted,
        }


def deadline_at(budget_ms: int | None) -> float | None:
    """把「预算毫秒」换算成 `time.monotonic()` 时间轴上的绝对截止点。

    管线需要把截止点放进 LangGraph 的 state 里跨节点传递 ——
    放在 state 而非 `self` 上，是因为**同一条管线会被并发调用**，
    挂在实例上就成了共享可变状态（并发问答会互相缩短彼此的预算）。
    """
    if not budget_ms:
        return None
    return time.monotonic() + int(budget_ms) / 1000.0


def remaining_from(deadline_at_ts: float | None) -> int | None:
    """从绝对截止点算剩余毫秒；未设截止则返回 None。"""
    if deadline_at_ts is None:
        return None
    return max(0, int((deadline_at_ts - time.monotonic()) * 1000))


def budget_status() -> dict:
    """health 端点用：当前生效的具名预算（便于运维一眼看到口径）。"""
    return {
        "BRAIN_TOTAL_BUDGET_MS": BRAIN_TOTAL_BUDGET_MS,
        "EMBED_BUDGET_MS": EMBED_BUDGET_MS,
        "OCR_BUDGET_MS": OCR_BUDGET_MS,
        "RETRIEVAL_BUDGET_MS": RETRIEVAL_BUDGET_MS,
        "registry": "contracts/timeout-budget.yaml",
    }
