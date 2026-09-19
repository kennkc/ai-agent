"""大脑层的 Postgres 访问底座（IN3 审计日志 / IN-02 记忆图谱共用）。

设计约定（与全局一致）：

* **真实持久化**：优先 PG（复用 Phase 3 的 `lifeform` 库，凭据与 `docker-compose.yml` 一致）；
  PG 不可用时**降级为进程内存储**并如实标 `degraded=True` —— 降级影响的是
  "跨进程/重启后能否回放与查询"，不影响当前请求的正确性。
* **DDL 自举**：首次调用时建表（幂等 `CREATE TABLE IF NOT EXISTS`），不依赖外部迁移脚本。
* **失败不阻断**：任何 PG 异常都转成降级，不让问答主链路因审计/图谱写失败而 500。
"""
from __future__ import annotations

import logging
import os
import threading
from typing import Any, Iterable, Optional

logger = logging.getLogger("nlp-service.brain.pg")

PG_HOST = os.getenv("PG_HOST", "127.0.0.1")
PG_PORT = int(os.getenv("PG_PORT", "5432"))
PG_DB = os.getenv("PG_DB", "lifeform")
PG_USER = os.getenv("PG_USER", "agent")
PG_PASSWORD = os.getenv("PG_PASSWORD", "agent123")
PG_ENABLED = os.getenv("BRAIN_PG_ENABLED", "true").lower() != "false"

_lock = threading.Lock()
_state: dict[str, Any] = {"initialized": False, "degraded": True, "reason": "not initialized"}


def dsn() -> str:
    return f"host={PG_HOST} port={PG_PORT} dbname={PG_DB} user={PG_USER} password={PG_PASSWORD}"


def _connect():
    import psycopg2  # 延迟导入：未安装时也能以降级态启动

    connection = psycopg2.connect(dsn(), connect_timeout=2)
    connection.autocommit = False
    return connection


def ensure_schema(statements: Iterable[str]) -> bool:
    """建表（幂等）。成功返回 True；失败记录降级原因并返回 False。"""
    global _state
    if not PG_ENABLED:
        _state = {"initialized": True, "degraded": True, "reason": "brain pg disabled by config"}
        return False
    with _lock:
        if _state.get("initialized"):
            return not _state.get("degraded", True)
        try:
            connection = _connect()
        except Exception as exc:  # noqa: BLE001
            _state = {"initialized": True, "degraded": True, "reason": f"pg_unavailable: {exc}"}
            logger.warning("brain pg unavailable, degraded to in-process store: %s", exc)
            return False
        try:
            with connection.cursor() as cursor:
                for statement in statements:
                    cursor.execute(statement)
            connection.commit()
            _state = {"initialized": True, "degraded": False, "reason": ""}
            return True
        except Exception as exc:  # noqa: BLE001
            connection.rollback()
            _state = {"initialized": True, "degraded": True, "reason": f"pg_ddl_failed: {exc}"}
            logger.warning("brain pg ddl failed, degraded to in-process store: %s", exc)
            return False
        finally:
            connection.close()


def execute(statements: Iterable[str], fetch: bool = False) -> tuple[bool, Optional[list[tuple]]]:
    """执行一批语句（同一事务）。

    @param statements 形如 `[(sql, params), ...]`
    @return `(ok, rows)` —— `ok=False` 表示 PG 不可用/失败，调用方应走降级分支。
    """
    if not PG_ENABLED or _state.get("degraded", True):
        return False, None
    try:
        connection = _connect()
    except Exception as exc:  # noqa: BLE001
        logger.warning("brain pg connect failed: %s", exc)
        return False, None
    try:
        rows: Optional[list[tuple]] = None
        with connection.cursor() as cursor:
            for sql, params in statements:
                cursor.execute(sql, params)
                if fetch:
                    rows = cursor.fetchall()
        connection.commit()
        return True, rows
    except Exception as exc:  # noqa: BLE001
        connection.rollback()
        logger.warning("brain pg exec failed: %s", exc)
        return False, None
    finally:
        connection.close()


def status() -> dict:
    return {
        "backend": "postgres" if not _state.get("degraded", True) else "memory",
        "degraded": bool(_state.get("degraded", True)),
        "reason": str(_state.get("reason", "")),
        "dsn": f"{PG_HOST}:{PG_PORT}/{PG_DB}",
    }


def reset_for_test() -> None:
    """单测用：重置初始化状态，便于切换后端。"""
    global _state
    _state = {"initialized": False, "degraded": True, "reason": "not initialized"}
