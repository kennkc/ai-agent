#!/usr/bin/env python3
"""MC-01 协作总线传输时延门禁（R-MC01-04）。

口径固定为：单机 NATS 请求 / 回应往返，预热后统计 P50/P95/P99。
JetStream 的持久化能力由 `--require-jetstream` 做可用性自检；本脚本不把
JetStream ack 的磁盘等待混入 P99 数字，避免把“持久化成本”和“消息传输成本”
混成一个无法定位的指标。

用法：
    python scripts/collab-bus-check.py --require-jetstream
    python scripts/collab-bus-check.py --count 5000 --p99-ms 10
"""
from __future__ import annotations

import argparse
import asyncio
import contextlib
import json
import math
import sys
import time
from dataclasses import dataclass

DEFAULT_NATS_URL = "nats://127.0.0.1:4222"


@dataclass(frozen=True)
class LatencyResult:
    count: int
    failures: int
    p50_ms: float
    p95_ms: float
    p99_ms: float
    min_ms: float
    max_ms: float
    histogram: dict[str, int]


def percentile(values: list[float], q: float) -> float:
    """返回最近秩百分位；空列表返回 0，避免调用方再写一次边界分支。"""
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(q * len(ordered)) - 1))
    return ordered[index]


def histogram(values: list[float]) -> dict[str, int]:
    buckets = {
        "0-1ms": 0,
        "1-2ms": 0,
        "2-5ms": 0,
        "5-10ms": 0,
        "10-20ms": 0,
        ">=20ms": 0,
    }
    for value in values:
        if value < 1:
            buckets["0-1ms"] += 1
        elif value < 2:
            buckets["1-2ms"] += 1
        elif value < 5:
            buckets["2-5ms"] += 1
        elif value < 10:
            buckets["5-10ms"] += 1
        elif value < 20:
            buckets["10-20ms"] += 1
        else:
            buckets[">=20ms"] += 1
    return buckets


def summarize(values: list[float], failures: int) -> LatencyResult:
    return LatencyResult(
        count=len(values),
        failures=failures,
        p50_ms=round(percentile(values, 0.50), 3),
        p95_ms=round(percentile(values, 0.95), 3),
        p99_ms=round(percentile(values, 0.99), 3),
        min_ms=round(min(values), 3) if values else 0.0,
        max_ms=round(max(values), 3) if values else 0.0,
        histogram=histogram(values),
    )


async def run_check(args: argparse.Namespace) -> LatencyResult:
    try:
        import nats
        from nats.errors import TimeoutError as NatsTimeoutError
    except ImportError as exc:  # pragma: no cover - 运行环境缺依赖时给出可执行指引
        raise RuntimeError(
            "缺少 nats-py，请先执行 `pip install -r services/python/nlp-service/requirements-dev.txt`"
        ) from exc

    client = await nats.connect(args.nats_url, connect_timeout=3, max_reconnect_attempts=3)
    latencies: list[float] = []
    failures = 0
    subject = f"{args.subject}.{time.time_ns()}"
    echo_task: asyncio.Task[None] | None = None
    try:
        if args.require_jetstream:
            # jsm 可用即说明服务端启用了 JetStream；持久化目录由部署侧自检。
            await client.jsm().streams_info()

        subscription = await client.subscribe(subject)
        async def echo() -> None:
            async for message in subscription.messages:
                if message.reply:
                    await client.publish(message.reply, message.data)

        echo_task = asyncio.create_task(echo())
        await client.flush()

        for _ in range(args.warmup):
            await client.request(subject, b"warmup", timeout=args.timeout)

        for _ in range(args.count):
            started = time.perf_counter_ns()
            try:
                await client.request(subject, b"gate", timeout=args.timeout)
            except NatsTimeoutError:
                failures += 1
                continue
            latencies.append((time.perf_counter_ns() - started) / 1_000_000)
    finally:
        if echo_task is not None:
            echo_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await echo_task
        await client.close()
    return summarize(latencies, failures)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="MC-01 NATS 请求/回应 P99 门禁")
    parser.add_argument("--nats-url", default=DEFAULT_NATS_URL)
    parser.add_argument("--count", type=int, default=10000, help="计入统计的请求数")
    parser.add_argument("--warmup", type=int, default=500, help="不计入统计的预热请求数")
    parser.add_argument("--timeout", type=float, default=1.0, help="单次请求超时秒数")
    parser.add_argument("--p99-ms", type=float, default=10.0, help="P99 门禁上限（毫秒）")
    parser.add_argument("--require-jetstream", action="store_true", help="确认服务端 JetStream 可用")
    parser.add_argument("--subject", default="_gate.collab.latency")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.count <= 0 or args.warmup < 0 or args.timeout <= 0 or args.p99_ms <= 0:
        print("FAIL 参数必须为正数（warmup 可为 0）", file=sys.stderr)
        return 2
    try:
        result = asyncio.run(run_check(args))
    except (OSError, RuntimeError) as exc:
        print(f"FAIL 无法执行协作总线时延门禁：{exc}", file=sys.stderr)
        return 1

    payload = {
        "status": "pass" if result.p99_ms < args.p99_ms else "fail",
        "p99_limit_ms": args.p99_ms,
        "count": result.count,
        "failures": result.failures,
        "p50_ms": result.p50_ms,
        "p95_ms": result.p95_ms,
        "p99_ms": result.p99_ms,
        "min_ms": result.min_ms,
        "max_ms": result.max_ms,
        "histogram": result.histogram,
    }
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    if result.count == 0 or result.failures:
        print("FAIL 存在超时或无有效样本，不能判定 P99 门禁通过", file=sys.stderr)
        return 1
    if result.p99_ms >= args.p99_ms:
        print(f"FAIL P99 {result.p99_ms}ms ≥ 门禁 {args.p99_ms}ms", file=sys.stderr)
        return 1
    print(f"PASS P99 {result.p99_ms}ms < {args.p99_ms}ms（{result.count} 次往返）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
