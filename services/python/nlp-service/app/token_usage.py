"""Token 用量计量（WB-10 「Token 用量看板」的真相源）。

这个模块补上的是一个**具体缺口**：前台能配模型（`llm_model_config`），但
「配的模型到底用了多少」在库里没有任何记录 —— 于是看板只能放演示数据。
演示数据在页面上和真实数据长得一模一样，这是最容易被误读的一种失真。

设计约定：

* **聚合落库、不是逐次落库**：按 `(租户, 日, 角色, 模型, 供应商, 计量来源)` 做 UPSERT 累加。
  逐次落库在问答量上来后会让这张表变成最大的表，而看板只需要聚合值。
* **`token_source` 进主键**：`provider`（供应商自报）与 `estimated`（字符估算，DEBT-016）
  **分开累计**，永不合并。合并之后"这周的用量准不准"就再也答不上来了。
* **失败也记账**：`failures` 与 `calls` 分开计数。只统计成功调用会让
  「一直在失败的重试」在看板上表现为"没有用量"，从而掩盖故障。
* **计量永不阻断主链路**：全部写入都在 try/except 内，失败只 warn。
  旁路信息没有资格让一次成功的问答变成 500。
* **PG 不可用则降级到进程内**，并由 `storage.degraded` 如实暴露 ——
  降级期的用量是**不完整**的，这一点必须能被看见，而不是显示成"用量就是这些"。
"""
from __future__ import annotations

import logging
import threading
from datetime import date, datetime, timedelta
from typing import Any, Optional

from app.brain import pg

logger = logging.getLogger("nlp-service.token_usage")

# 用量表的持有者是本模块；`pg.ensure_schema()` 按**语句级**去重，
# 因此多个模块各注册自己的 DDL 是安全的（2026-09-20 修的那个缺陷所支撑的能力）。
DDL: tuple[str, ...] = (
    """
    CREATE TABLE IF NOT EXISTS llm_token_usage (
        id                BIGSERIAL PRIMARY KEY,
        tenant_id         VARCHAR(64)  NOT NULL DEFAULT 'default',
        bucket_date       DATE         NOT NULL,
        role              VARCHAR(64)  NOT NULL DEFAULT '',
        model             VARCHAR(160) NOT NULL DEFAULT '',
        provider          VARCHAR(64)  NOT NULL DEFAULT '',
        token_source      VARCHAR(16)  NOT NULL DEFAULT 'estimated',
        calls             INTEGER      NOT NULL DEFAULT 0,
        failures          INTEGER      NOT NULL DEFAULT 0,
        prompt_tokens     BIGINT       NOT NULL DEFAULT 0,
        completion_tokens BIGINT       NOT NULL DEFAULT 0,
        latency_ms_sum    BIGINT       NOT NULL DEFAULT 0,
        updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
    )
    """,
    # UPSERT 的唯一键。必须与 `_KEY_COLUMNS` 完全一致 —— 不一致时
    # `ON CONFLICT` 会找不到匹配的唯一索引而整条写入失败（且只在写入时才暴露）。
    "CREATE UNIQUE INDEX IF NOT EXISTS uq_llm_token_usage_bucket "
    "ON llm_token_usage (tenant_id, bucket_date, role, model, provider, token_source)",
    "CREATE INDEX IF NOT EXISTS idx_llm_token_usage_tenant_date "
    "ON llm_token_usage (tenant_id, bucket_date)",
)

_KEY_COLUMNS: tuple[str, ...] = (
    "tenant_id", "bucket_date", "role", "model", "provider", "token_source",
)
_COUNTER_COLUMNS: tuple[str, ...] = (
    "calls", "failures", "prompt_tokens", "completion_tokens", "latency_ms_sum",
)

_schema_ready: dict[str, Any] = {"done": False}
_memory_lock = threading.Lock()
# 降级期的进程内聚合：key = 唯一键元组
_memory: dict[tuple, dict[str, Any]] = {}


def ensure_ready() -> bool:
    """自举表结构。PG 不可用时返回 False（调用方据此走进程内降级）。"""
    if _schema_ready["done"]:
        return True
    ok = pg.ensure_schema(DDL)
    if ok:
        _schema_ready["done"] = True
    return ok


def reset_for_test() -> None:
    _schema_ready["done"] = False
    with _memory_lock:
        _memory.clear()


def record_usage(
    *,
    tenant_id: str = "default",
    role: str = "",
    model: str = "",
    provider: str = "",
    token_source: str = "estimated",
    prompt_tokens: int = 0,
    completion_tokens: int = 0,
    latency_ms: int = 0,
    ok: bool = True,
    bucket_date: Optional[date] = None,
) -> bool:
    """累加一次调用的用量。返回是否**落库成功**（False = 走了进程内降级）。

    **本函数不抛异常** —— 它是旁路计量，任何失败都不得影响主调用。
    返回值只用于让调用方/测试区分"记账成功"与"记账降级"。
    """
    try:
        row = _row(
            tenant_id=tenant_id, role=role, model=model, provider=provider,
            token_source=token_source, prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens, latency_ms=latency_ms, ok=ok,
            bucket_date=bucket_date,
        )
        if ensure_ready():
            if _upsert(row):
                return True
        _accumulate_memory(row)
        return False
    except Exception as exc:  # noqa: BLE001 - 计量失败绝不冒泡
        logger.warning("token usage record failed: %s: %s", type(exc).__name__, exc)
        return False


def usage_summary(tenant_id: str = "default", days: int = 7) -> dict[str, Any]:
    """按天窗口聚合用量。返回结构直接对应看板需要的几块。"""
    # 收敛窗口：`days=0` 这类显式非法值不能靠 `or` 回落到默认（0 是 falsy，
    # 会被静默换成 7 天）—— 那会让"传了 0 想只看今天"变成"看了一周"。
    window = 7 if days is None else int(days)
    days = max(1, min(window, 90))
    end = date.today()
    start = end - timedelta(days=days - 1)
    ensure_ready()
    sql = (
        f"SELECT bucket_date, role, model, provider, token_source, "
        f"{', '.join(_COUNTER_COLUMNS)} FROM llm_token_usage "
        f"WHERE tenant_id = %s AND bucket_date >= %s AND bucket_date <= %s "
        f"ORDER BY bucket_date"
    )
    ok, rows = pg.execute([(sql, (tenant_id, start, end))], fetch=True)
    degraded = not ok
    items: list[dict[str, Any]] = []
    if ok and rows is not None:
        for row in rows:
            items.append({
                "date": _as_date(row[0]),
                "role": str(row[1] or ""),
                "model": str(row[2] or ""),
                "provider": str(row[3] or ""),
                "token_source": str(row[4] or ""),
                "calls": int(row[5] or 0),
                "failures": int(row[6] or 0),
                "prompt_tokens": int(row[7] or 0),
                "completion_tokens": int(row[8] or 0),
                "latency_ms_sum": int(row[9] or 0),
            })
    else:
        items = [dict(item) for item in _memory_items(tenant_id, start, end)]
    return _shape(items, tenant_id=tenant_id, days=days, start=start, end=end, degraded=degraded)


# ─────────── 内部 ───────────


def _row(**kwargs: Any) -> dict[str, Any]:
    """一次调用的增量。**`calls` 只记成功**，失败走 `failures` ——

    两者合在一起数（失败也算一次 call）会让 `success_rate` 恒等于 1，
    这个指标就废了；而分开数才能回答"这个模型是在用，还是在一直失败重试"。
    """
    ok = bool(kwargs.get("ok", True))
    bucket: date = kwargs.get("bucket_date") or date.today()
    return {
        "tenant_id": str(kwargs.get("tenant_id") or "default"),
        "bucket_date": bucket,
        "role": str(kwargs.get("role") or "")[:64],
        "model": str(kwargs.get("model") or "")[:160],
        "provider": str(kwargs.get("provider") or "")[:64],
        "token_source": str(kwargs.get("token_source") or "estimated")[:16],
        "calls": 1 if ok else 0,
        "failures": 0 if ok else 1,
        "prompt_tokens": max(0, int(kwargs.get("prompt_tokens") or 0)),
        "completion_tokens": max(0, int(kwargs.get("completion_tokens") or 0)),
        "latency_ms_sum": max(0, int(kwargs.get("latency_ms") or 0)),
    }


def _upsert(row: dict[str, Any]) -> bool:
    placeholders = ", ".join(["%s"] * (len(_KEY_COLUMNS) + len(_COUNTER_COLUMNS)))
    # 累加语义写在 SQL 里（`llm_token_usage.x + EXCLUDED.x`）而不是"先读后写"：
    # 后者在多进程/多线程下会丢计数，而用量恰恰是并发最高的写入点之一。
    increments = ", ".join(f"{col} = llm_token_usage.{col} + EXCLUDED.{col}"
                           for col in _COUNTER_COLUMNS)
    sql = (
        f"INSERT INTO llm_token_usage ({', '.join(_KEY_COLUMNS + _COUNTER_COLUMNS)}) "
        f"VALUES ({placeholders}) "
        f"ON CONFLICT (tenant_id, bucket_date, role, model, provider, token_source) "
        f"DO UPDATE SET {increments}, updated_at = NOW()"
    )
    params = tuple(row[col] for col in _KEY_COLUMNS + _COUNTER_COLUMNS)
    ok, _ = pg.execute([(sql, params)])
    return bool(ok)


def _accumulate_memory(row: dict[str, Any]) -> None:
    key = tuple(row[col] for col in _KEY_COLUMNS)
    with _memory_lock:
        current = _memory.get(key)
        if current is None:
            current = {col: row[col] for col in _KEY_COLUMNS}
            _memory[key] = current
        for col in _COUNTER_COLUMNS:
            current[col] = int(current.get(col, 0)) + int(row[col])


def _memory_items(tenant_id: str, start: date, end: date) -> list[dict[str, Any]]:
    """降级期的进程内明细。**键名与 PG 分支完全一致**（`date` 而非 `bucket_date`）。

    两个分支返回不同形状是最容易埋雷的写法：`_shape()` 会只在其中一条路径上崩，
    于是"降级时才出问题"，而测试默认走降级路径、生产走 PG 路径 —— 正好互相掩盖。
    """
    with _memory_lock:
        snapshot = [dict(item) for item in _memory.values()]
    return [
        {**{key: item[key] for key in ("role", "model", "provider", "token_source")},
         "date": _as_date(item["bucket_date"]),
         **{col: int(item.get(col) or 0) for col in _COUNTER_COLUMNS}}
        for item in snapshot
        if item["tenant_id"] == tenant_id and start <= item["bucket_date"] <= end
    ]


def _shape(items: list[dict[str, Any]], *, tenant_id: str, days: int,
           start: date, end: date, degraded: bool) -> dict[str, Any]:
    """把明细行整理成看板需要的分块，并把**口径成色**一并算出。"""
    totals = {col: sum(int(item.get(col) or 0) for item in items) for col in _COUNTER_COLUMNS}
    by_role: dict[str, dict[str, int]] = {}
    by_model: dict[str, dict[str, int]] = {}
    by_source: dict[str, int] = {}
    daily: dict[str, dict[str, int]] = {}
    for item in items:
        _merge(by_role, item["role"] or "(未标注)", item)
        _merge(by_model, item["model"] or "(未标注)", item)
        by_source[item["token_source"]] = (
            by_source.get(item["token_source"], 0) + item["calls"] + item["failures"]
        )
        _merge(daily, _as_date(item["date"]), item)
    total_tokens = totals["prompt_tokens"] + totals["completion_tokens"]
    attempts = totals["calls"] + totals["failures"]
    return {
        "tenant_id": tenant_id,
        "window": {"days": days, "start": start.isoformat(), "end": end.isoformat()},
        "items": items,
        "totals": {
            **totals,
            "tokens": total_tokens,
            # 含失败的**总尝试次数** —— 与 `calls`（成功）分开，避免"失败被算成没发生"
            "attempts": attempts,
            "success_rate": round(totals["calls"] / attempts, 4) if attempts else None,
        },
        "by_role": by_role,
        "by_model": by_model,
        # 按**尝试次数**分源（不是成功次数）—— 这样它与分母同口径，
        # `estimated_share` 才算得对；否则会出现"占比之和不为 1"的账。
        "by_token_source": by_source,
        # 估算值占比。这一项是**诚实度指标**：它不为 0 时，
        # 上面那些 token 数字就只能当趋势看，不能当账单看。
        "estimated_share": round(by_source.get("estimated", 0) / attempts, 4) if attempts else 0.0,
        "daily": [{"date": key, **value} for key, value in sorted(daily.items())],
        "storage": {**pg.status(), "degraded": degraded,
                    "note": "计量降级期间（PG 不可用）的用量不计入本表" if degraded else ""},
    }


def _merge(target: dict[str, dict[str, int]], key: str, item: dict[str, Any]) -> None:
    bucket = target.setdefault(key, {col: 0 for col in _COUNTER_COLUMNS})
    for col in _COUNTER_COLUMNS:
        bucket[col] += int(item.get(col) or 0)


def _as_date(value: Any) -> str:
    if isinstance(value, datetime):
        return value.date().isoformat()
    if isinstance(value, date):
        return value.isoformat()
    return str(value)[:10]
