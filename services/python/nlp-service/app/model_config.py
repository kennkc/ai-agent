"""模型接入配置（WB-10）—— 前台可配置的大模型接口注册表。

这个模块解决一个具体问题：**「换一个模型」不该意味着「改环境变量并重启服务」**。
配置从环境变量搬进 `llm_model_config` 表，前端可增删改查，服务端按功能角色组装引擎。

设计约定（与全局一致）：

* **唯一真相源**：PostgreSQL `llm_model_config`。PG 不可用时降级为进程内存储并
  在 `status()` 里**如实标 `degraded=True`** —— 此时配置"改得动、重启即丢"，必须让人看见。
* **凭据只以密文落库**：`api_key` 用 AES-256-GCM 加密后存 `api_key_cipher`，
  对外接口一律只回 `api_key_hint`（`sk-***last4`）与 `has_api_key`。
  **解密只发生在构造引擎的那一刻**，不经过任何响应体。
* **密钥来源可追溯**：优先 `MODEL_CONFIG_MASTER_KEY`（生产必须）；
  缺省退回本地密钥文件（0600，已 gitignore），便于本地开发**且重启后仍能解密**。
  刻意不做"每次启动随机生成"—— 那会让已存密文在重启后静默变成一堆不可解的字节。
* **功能角色是绑定的唯一维度**：`intent / embed / rerank / generate / plan / code`。
  按"这个活由谁干"而不是"哪个服务调"来分，因为同一个服务里混着多种任务
  （`nlp-service` 既有意图分类又有长文生成），按服务分粒度太粗。
* **失败要分类**：角色没配模型 → 回落 `TemplateEngine` 并标 `degraded`
  （可接受）；凭据加解密失败 → **拒绝写入**（不可接受的静默降级）。
"""
from __future__ import annotations

import base64
import json
import logging
import os
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from app.brain import pg

logger = logging.getLogger("nlp-service.model_config")

# ─────────── 功能角色字典（字段命名 snake_case，对齐 D5-2）───────────


@dataclass(frozen=True)
class Role:
    key: str
    label: str
    default_tier: str
    default_timeout_ms: int
    description: str


ROLES: tuple[Role, ...] = (
    Role("intent", "意图识别", "L1", 3000, "会话入口的意图分类（量小、要求快）"),
    Role("embed", "向量嵌入", "L1", 12000, "知识入库与检索的向量化"),
    Role("rerank", "检索重排", "L1", 10000, "检索结果重排"),
    Role("generate", "内容生成", "L2", 20000, "问答与摘要的自然语言生成"),
    Role("plan", "复杂规划", "L3", 20000, "多步任务拆解（Phase 6 小脑期）"),
    Role("code", "代码生成", "L2", 20000, "代码类工具调用"),
)
ROLE_MAP: dict[str, Role] = {role.key: role for role in ROLES}
ROLE_KEYS: tuple[str, ...] = tuple(role.key for role in ROLES)

TIERS: tuple[str, ...] = ("L1", "L2", "L3")
PROVIDERS: tuple[str, ...] = (
    "deepseek", "openai", "openrouter", "qwen", "anthropic", "moonshot", "zhipu", "local", "custom",
)
# 各供应商的 OpenAI 兼容默认地址（仅作表单预填，**不参与运行**）
PROVIDER_DEFAULT_BASE_URL: dict[str, str] = {
    "deepseek": "https://api.deepseek.com/v1",
    "openai": "https://api.openai.com/v1",
    "openrouter": "https://openrouter.ai/api/v1",
    "qwen": "https://dashscope.aliyuncs.com/compatible-mode/v1",
    "anthropic": "https://api.anthropic.com/v1",
    "moonshot": "https://api.moonshot.cn/v1",
    "zhipu": "https://open.bigmodel.cn/api/paas/v4",
    "local": "http://127.0.0.1:11434/v1",
    "custom": "",
}

# ─────────── 表结构（DDL 自举，幂等）───────────

DDL: tuple[str, ...] = (
    """
    CREATE TABLE IF NOT EXISTS llm_model_config (
        id                    BIGSERIAL PRIMARY KEY,
        tenant_id             VARCHAR(64)  NOT NULL DEFAULT 'default',
        config_key            VARCHAR(64)  NOT NULL,
        name                  VARCHAR(128) NOT NULL,
        provider              VARCHAR(64)  NOT NULL DEFAULT 'custom',
        base_url              VARCHAR(512) NOT NULL DEFAULT '',
        model                 VARCHAR(160) NOT NULL DEFAULT '',
        api_key_cipher        TEXT         NOT NULL DEFAULT '',
        api_key_hint          VARCHAR(64)  NOT NULL DEFAULT '',
        tier                  VARCHAR(4)   NOT NULL DEFAULT 'L2',
        max_tokens            INTEGER      NOT NULL DEFAULT 512,
        temperature           NUMERIC(3,2) NOT NULL DEFAULT 0.20,
        timeout_ms            INTEGER      NOT NULL DEFAULT 8000,
        routing_weight        INTEGER      NOT NULL DEFAULT 100,
        enabled               BOOLEAN      NOT NULL DEFAULT FALSE,
        extra                 JSONB        NOT NULL DEFAULT '{}'::jsonb,
        last_probe_at         TIMESTAMPTZ,
        last_probe_ok         BOOLEAN,
        last_probe_latency_ms INTEGER,
        last_probe_error      VARCHAR(512) NOT NULL DEFAULT '',
        created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
        updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
        updated_by            VARCHAR(64)  NOT NULL DEFAULT ''
    )
    """,
    ("CREATE UNIQUE INDEX IF NOT EXISTS uq_llm_model_config_tenant_role_name "
    "ON llm_model_config (tenant_id, config_key, name)"),
    ("CREATE INDEX IF NOT EXISTS idx_llm_model_config_tenant_role "
    "ON llm_model_config (tenant_id, config_key, enabled)"),
    # 序列校正（幂等）。历史版本用「进程内计数器 + 显式 id」写入，**从不推进 SERIAL 序列**，
    # 于是 `nextval` 会落在 `MAX(id)` 之后。改为由序列分配 id 之前必须先对齐，
    # 否则第一次插入就撞主键（2026-09-20 实测：表里已有 id=10，序列仍停在 1）。
    ("SELECT setval(pg_get_serial_sequence('llm_model_config', 'id'), "
    "GREATEST(COALESCE((SELECT MAX(id) FROM llm_model_config), 0), 1))"),
)

COLUMNS: tuple[str, ...] = (
    "id", "tenant_id", "config_key", "name", "provider", "base_url", "model",
    "api_key_cipher", "api_key_hint", "tier", "max_tokens", "temperature", "timeout_ms",
    "routing_weight", "enabled", "extra",
    "last_probe_at", "last_probe_ok", "last_probe_latency_ms", "last_probe_error",
    "created_at", "updated_at", "updated_by",
)

SECRET_AAD = b"lifeform.llm_model_config.v1"
DEFAULT_KEY_FILE = os.getenv(
    "MODEL_CONFIG_KEY_FILE",
    str(Path(__file__).resolve().parent.parent / ".model-config-key"),
)


class ModelConfigError(RuntimeError):
    """配置层错误。`code` 会被端点转成统一信封，`status` 决定 HTTP 状态码。"""

    def __init__(self, code: str, message: str, status: int = 400, details: dict | None = None) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status
        self.details = details or {}


# ─────────── 凭据加解密 ───────────

_key_state: dict[str, Any] = {"key": None, "source": "unresolved", "error": ""}
_key_lock = threading.Lock()


def _decode_key(raw: str) -> bytes:
    """接受 64 位十六进制 / base64 / 32 字节原串三种写法。"""
    text = raw.strip()
    if len(text) == 64:
        try:
            return bytes.fromhex(text)
        except ValueError:
            pass
    try:
        decoded = base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))
        if len(decoded) == 32:
            return decoded
    except Exception:  # noqa: BLE001 - 落到原串分支
        pass
    if len(text.encode("utf-8")) == 32:
        return text.encode("utf-8")
    raise ModelConfigError(
        "AGENT_CONFIG_INVALID_KEY",
        "MODEL_CONFIG_MASTER_KEY 必须为 32 字节（64 位 hex / base64 / 32 字符原串）",
        status=500,
    )


def resolve_master_key() -> tuple[bytes | None, str]:
    """返回 `(key, source)`；无法取得时 key 为 None 且 source 说明原因。"""
    with _key_lock:
        if _key_state["key"] is not None or _key_state["source"] not in ("unresolved", "error"):
            return _key_state["key"], str(_key_state["source"])
        raw = os.getenv("MODEL_CONFIG_MASTER_KEY", "").strip()
        if raw:
            try:
                _key_state.update(key=_decode_key(raw), source="env", error="")
            except ModelConfigError as exc:
                _key_state.update(key=None, source="error", error=exc.message)
            return _key_state["key"], _key_state["source"]
        # 本地开发兜底：与 wp-bff 控制令牌同构的"文件即密钥"策略。
        # 关键点：**持久化**。若每次启动随机生成，已落库的密文重启后全部不可解，
        # 而错误会以"解密失败"的形式在很久以后才暴露 —— 那是不可接受的静默数据损坏。
        path = Path(DEFAULT_KEY_FILE)
        try:
            if path.exists():
                _key_state.update(key=_decode_key(path.read_text(encoding="utf-8")), source="file", error="")
            else:
                generated = os.urandom(32)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(base64.urlsafe_b64encode(generated).decode("ascii"), encoding="utf-8")
                try:
                    path.chmod(0o600)
                except OSError:  # Windows 上 chmod 语义有限，记日志即可
                    logger.info("model config key file chmod not supported on this platform: %s", path)
                _key_state.update(key=generated, source="file:generated", error="")
                logger.warning(
                    "MODEL_CONFIG_MASTER_KEY 未设置，已生成本地密钥文件 %s（仅供开发；生产必须使用环境变量）",
                    path,
                )
        except Exception as exc:  # noqa: BLE001
            _key_state.update(key=None, source="error", error=f"key file unusable: {exc}")
            logger.warning("model config key file unusable: %s", exc)
        return _key_state["key"], _key_state["source"]


def _aesgcm():
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    return AESGCM


def seal_secret(plaintext: str) -> str:
    """明文 → 密文（base64(nonce || ciphertext)）。空串返回空串（表示"未配置凭据"）。"""
    if not plaintext:
        return ""
    key, source = resolve_master_key()
    if key is None:
        # 宁可拒绝写入，也不退化成明文落库 —— 那是把"加密"变成一句空话
        raise ModelConfigError("AGENT_CONFIG_KEY_UNAVAILABLE", f"加密密钥不可用（{source}）", status=503)
    nonce = os.urandom(12)
    sealed = _aesgcm()(key).encrypt(nonce, plaintext.encode("utf-8"), SECRET_AAD)
    return base64.urlsafe_b64encode(nonce + sealed).decode("ascii")


def open_secret(cipher_text: str) -> str:
    """密文 → 明文。仅用于构造引擎，**绝不用于响应序列化**。"""
    if not cipher_text:
        return ""
    key, source = resolve_master_key()
    if key is None:
        raise ModelConfigError("AGENT_CONFIG_KEY_UNAVAILABLE", f"解密密钥不可用（{source}）", status=503)
    try:
        raw = base64.urlsafe_b64decode(cipher_text.encode("ascii"))
        return _aesgcm()(key).decrypt(raw[:12], raw[12:], SECRET_AAD).decode("utf-8")
    except ModelConfigError:
        raise
    except Exception as exc:
        raise ModelConfigError(
            "AGENT_CONFIG_DECRYPT_FAILED",
            f"凭据解密失败（密钥已变更或密文损坏）：{type(exc).__name__}",
            status=500,
        ) from exc


def mask_secret(plaintext: str) -> str:
    """脱敏展示：保留可辨识前缀与后 4 位，其余打码。

    前 4 位若形如 `sk-`/`Bearer ` 这类**公开前缀**则原样保留 —— 它们不是秘密，
    保留能让运维一眼看出是哪家格式；真正的秘密部分一律替换。
    """
    if not plaintext:
        return ""
    if len(plaintext) <= 8:
        return "*" * len(plaintext)
    prefix = ""
    if "-" in plaintext[:8]:
        prefix = plaintext[: plaintext.index("-") + 1]
    elif plaintext.lower().startswith("bearer "):
        prefix = plaintext[:7]
    return f"{prefix}***{plaintext[-4:]}"


# ─────────── 存储（PG + 进程内降级）──────────

_memory_lock = threading.Lock()
_memory: dict[int, dict[str, Any]] = {}
_memory_seq = {"next": 1}
_schema_ready = {"done": False}


def _now_iso() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime())


def ensure_ready() -> bool:
    """建表（幂等）。返回 True 表示 PG 可用；False 表示已降级为进程内存储。"""
    if not _schema_ready["done"]:
        pg.ensure_schema(DDL)
        _schema_ready["done"] = True
    return not pg.status().get("degraded", True)


def reset_for_test() -> None:
    with _memory_lock:
        _memory.clear()
        _memory_seq["next"] = 1
    _schema_ready["done"] = False
    _key_state.update(key=None, source="unresolved", error="")


def _jsonable(value: Any) -> Any:
    if value is None:
        return None
    if hasattr(value, "isoformat"):
        return value.isoformat()
    if isinstance(value, (float, int)):
        return value
    try:
        from decimal import Decimal

        if isinstance(value, Decimal):
            return float(value)
    except ImportError:  # pragma: no cover
        pass
    return value


def _to_public(row: dict[str, Any]) -> dict[str, Any]:
    """行 → 对外结构。**这一步是唯一的对外出口，密文在这里被摘掉**。"""
    out = {key: _jsonable(row.get(key)) for key in COLUMNS if key != "api_key_cipher"}
    out["id"] = int(row.get("id") or 0)
    out["enabled"] = bool(row.get("enabled"))
    out["temperature"] = float(row.get("temperature") or 0.0)
    out["has_api_key"] = bool(str(row.get("api_key_cipher") or ""))
    role = ROLE_MAP.get(str(row.get("config_key") or ""))
    out["role_label"] = role.label if role else str(row.get("config_key") or "")
    return out


def _row_from_pg(values: tuple) -> dict[str, Any]:
    return dict(zip(COLUMNS, values, strict=False))


# ─────────── 校验 ───────────

def _require_role(config_key: Any) -> str:
    key = str(config_key or "").strip()
    if key not in ROLE_MAP:
        raise ModelConfigError(
            "AGENT_BAD_REQUEST",
            f"未知功能角色：{key or '(空)'}",
            details={"field": "config_key", "supported": list(ROLE_KEYS)},
        )
    return key


def _require_tier(tier: Any, fallback: str) -> str:
    value = str(tier or fallback).strip().upper()
    if value not in TIERS:
        raise ModelConfigError("AGENT_BAD_REQUEST", f"未知模型层级：{value}",
                               details={"field": "tier", "supported": list(TIERS)})
    return value


def _require_provider(provider: Any) -> str:
    value = str(provider or "custom").strip().lower()
    if value not in PROVIDERS:
        raise ModelConfigError("AGENT_BAD_REQUEST", f"未知供应商：{value}",
                               details={"field": "provider", "supported": list(PROVIDERS)})
    return value


def _optional_base_url(value: Any) -> str:
    url = str(value or "").strip().rstrip("/")
    if url and not url.startswith(("http://", "https://")):
        raise ModelConfigError("AGENT_BAD_REQUEST", "base_url 必须以 http:// 或 https:// 开头",
                               details={"field": "base_url"})
    return url


def _int_in(value: Any, field: str, low: int, high: int, default: int) -> int:
    if value in (None, ""):
        return default
    try:
        number = int(value)
    except (TypeError, ValueError) as exc:
        raise ModelConfigError("AGENT_BAD_REQUEST", f"{field} 必须为整数",
                               details={"field": field}) from exc
    if not low <= number <= high:
        raise ModelConfigError("AGENT_BAD_REQUEST", f"{field} 需在 [{low}, {high}] 范围内",
                               details={"field": field, "value": number})
    return number


def _float_in(value: Any, field: str, low: float, high: float, default: float) -> float:
    if value in (None, ""):
        return default
    try:
        number = float(value)
    except (TypeError, ValueError) as exc:
        raise ModelConfigError("AGENT_BAD_REQUEST", f"{field} 必须为数字",
                               details={"field": field}) from exc
    if not low <= number <= high:
        raise ModelConfigError("AGENT_BAD_REQUEST", f"{field} 需在 [{low}, {high}] 范围内",
                               details={"field": field, "value": number})
    return round(number, 2)


# ─────────── CRUD ───────────

def list_configs(tenant_id: str = "default") -> dict[str, Any]:
    """按功能角色分组返回配置。密钥只出现 `api_key_hint`。"""
    ensure_ready()
    rows = _select(tenant_id)
    items = [_to_public(row) for row in rows]
    grouped: dict[str, list[dict[str, Any]]] = {key: [] for key in ROLE_KEYS}
    for item in items:
        grouped.setdefault(str(item["config_key"]), []).append(item)
    return {
        "items": items,
        "by_role": grouped,
        "total": len(items),
        "enabled": sum(1 for item in items if item["enabled"]),
        "roles": [role.__dict__ for role in ROLES],
        "providers": [
            {"key": key, "default_base_url": PROVIDER_DEFAULT_BASE_URL.get(key, "")} for key in PROVIDERS
        ],
        "storage": status(),
    }


def get_config(tenant_id: str, config_id: int) -> dict[str, Any]:
    ensure_ready()
    for row in _select(tenant_id):
        if int(row.get("id") or 0) == int(config_id):
            return _to_public(row)
    raise ModelConfigError("AGENT_NOT_FOUND", f"模型配置不存在：{config_id}", status=404,
                           details={"model_id": config_id})


def create_config(tenant_id: str, payload: dict[str, Any], actor: str = "") -> dict[str, Any]:
    ensure_ready()
    role_key = _require_role(payload.get("config_key"))
    role = ROLE_MAP[role_key]
    name = str(payload.get("name") or "").strip()
    if not name:
        raise ModelConfigError("AGENT_BAD_REQUEST", "name 不能为空", details={"field": "name"})
    provider = _require_provider(payload.get("provider"))
    base_url = _optional_base_url(payload.get("base_url"))
    model_name = str(payload.get("model") or "").strip()
    api_key = str(payload.get("api_key") or "")
    enabled = bool(payload.get("enabled"))
    if enabled and not (base_url and model_name):
        # **这是本模块最重要的一条校验**：把"启用了但没有任何地址/模型名"的配置
        # 放进路由，运行期只会表现为"引擎不可用"，排查成本极高。
        raise ModelConfigError(
            "AGENT_BAD_REQUEST",
            "启用配置必须同时提供 base_url 与 model",
            details={"field": "enabled"},
        )
    if _exists(tenant_id, role_key, name):
        raise ModelConfigError("AGENT_CONFLICT", f"角色 {role_key} 下已存在同名配置：{name}",
                               status=409, details={"field": "name", "name": name})
    row = {
        "tenant_id": tenant_id,
        "config_key": role_key,
        "name": name,
        "provider": provider,
        "base_url": base_url,
        "model": model_name,
        "api_key_cipher": seal_secret(api_key),
        "api_key_hint": mask_secret(api_key),
        "tier": _require_tier(payload.get("tier"), role.default_tier),
        "max_tokens": _int_in(payload.get("max_tokens"), "max_tokens", 1, 32768, 512),
        "temperature": _float_in(payload.get("temperature"), "temperature", 0.0, 2.0, 0.2),
        "timeout_ms": _int_in(payload.get("timeout_ms"), "timeout_ms", 500, 120000, role.default_timeout_ms),
        "routing_weight": _int_in(payload.get("routing_weight"), "routing_weight", 0, 100, 100),
        "enabled": enabled,
        "extra": payload.get("extra") if isinstance(payload.get("extra"), dict) else {},
        "last_probe_at": None,
        "last_probe_ok": None,
        "last_probe_latency_ms": None,
        "last_probe_error": "",
        "created_at": _now_iso(),
        "updated_at": _now_iso(),
        "updated_by": actor,
    }
    row = _insert(row)          # id 由序列分配（或降级路径的内存分配）在此落定
    return _to_public(row)


def update_config(tenant_id: str, config_id: int, payload: dict[str, Any], actor: str = "") -> dict[str, Any]:
    """局部更新。`api_key` 传空串 = 不动原有凭据；传非空 = 覆盖。"""
    ensure_ready()
    current = _find_row(tenant_id, config_id)
    if current is None:
        raise ModelConfigError("AGENT_NOT_FOUND", f"模型配置不存在：{config_id}", status=404,
                               details={"model_id": config_id})
    role_key = _require_role(payload.get("config_key", current["config_key"]))
    role = ROLE_MAP[role_key]
    name = str(payload.get("name", current["name"]) or "").strip()
    if not name:
        raise ModelConfigError("AGENT_BAD_REQUEST", "name 不能为空", details={"field": "name"})
    renamed = name != current["name"] or role_key != current["config_key"]
    if renamed and _exists(tenant_id, role_key, name, exclude_id=config_id):
        raise ModelConfigError("AGENT_CONFLICT", f"角色 {role_key} 下已存在同名配置：{name}",
                               status=409, details={"field": "name", "name": name})
    base_url = _optional_base_url(payload.get("base_url", current["base_url"]))
    model_name = str(payload.get("model", current["model"]) or "").strip()
    enabled = bool(payload.get("enabled", current["enabled"]))
    if enabled and not (base_url and model_name):
        raise ModelConfigError("AGENT_BAD_REQUEST", "启用配置必须同时提供 base_url 与 model",
                               details={"field": "enabled"})
    provided_key = payload.get("api_key")
    if provided_key is None:
        cipher, hint = current["api_key_cipher"], current["api_key_hint"]
    elif str(provided_key) == "":
        cipher, hint = "", ""      # 显式清空
    else:
        cipher, hint = seal_secret(str(provided_key)), mask_secret(str(provided_key))
    updated = {
        **current,
        "config_key": role_key,
        "name": name,
        "provider": _require_provider(payload.get("provider", current["provider"])),
        "base_url": base_url,
        "model": model_name,
        "api_key_cipher": cipher,
        "api_key_hint": hint,
        "tier": _require_tier(payload.get("tier", current["tier"]), role.default_tier),
        "max_tokens": _int_in(payload.get("max_tokens", current["max_tokens"]), "max_tokens", 1, 32768, 512),
        "temperature": _float_in(payload.get("temperature", current["temperature"]),
                                 "temperature", 0.0, 2.0, 0.2),
        "timeout_ms": _int_in(payload.get("timeout_ms", current["timeout_ms"]),
                              "timeout_ms", 500, 120000, role.default_timeout_ms),
        "routing_weight": _int_in(payload.get("routing_weight", current["routing_weight"]),
                                  "routing_weight", 0, 100, 100),
        "enabled": enabled,
        "extra": payload.get("extra") if isinstance(payload.get("extra"), dict) else current["extra"],
        "updated_at": _now_iso(),
        "updated_by": actor,
    }
    _persist(updated)
    return _to_public(updated)


def delete_config(tenant_id: str, config_id: int) -> dict[str, Any]:
    ensure_ready()
    row = _find_row(tenant_id, config_id)
    if row is None:
        raise ModelConfigError("AGENT_NOT_FOUND", f"模型配置不存在：{config_id}", status=404,
                               details={"model_id": config_id})
    _remove(tenant_id, config_id)
    return {"deleted": True, "model_id": int(config_id), "name": row["name"]}


def resolve_engines(tenant_id: str = "default") -> list[dict[str, Any]]:
    """给 LlmGateway 用的引擎规格（**这里才解密**）。仅返回 enabled 的配置。"""
    ensure_ready()
    specs: list[dict[str, Any]] = []
    for row in sorted(_select(tenant_id), key=lambda item: -int(item.get("routing_weight") or 0)):
        if not row.get("enabled"):
            continue
        if not (row.get("base_url") and row.get("model")):
            continue
        specs.append({
            "id": int(row["id"]),
            "role": str(row["config_key"]),
            "name": str(row["name"]),
            "provider": str(row["provider"]),
            "base_url": str(row["base_url"]),
            "model": str(row["model"]),
            "api_key": open_secret(str(row.get("api_key_cipher") or "")),
            "tier": str(row["tier"]),
            "max_tokens": int(row["max_tokens"]),
            "temperature": float(row["temperature"]),
            "timeout_ms": int(row["timeout_ms"]),
            "routing_weight": int(row["routing_weight"]),
        })
    return specs


# ─────────── 连通性探测 ───────────

def probe_config(tenant_id: str, config_id: int, timeout_ms: int = 5000) -> dict[str, Any]:
    """轻量连通性探测：`GET {base_url}/models`（不消耗 token）。

    **口径要说清**：这只验证"地址可达 + 凭据被接受"，不验证模型名是否可用。
    部分供应商不暴露 `/models`，此时返回 `supported=False` 并如实说明"无法据此判定"，
    而不是把 404 笼统报成"不可用" —— 那会让人去查一个并不存在的问题。
    """
    ensure_ready()
    row = _find_row(tenant_id, config_id)
    if row is None:
        raise ModelConfigError("AGENT_NOT_FOUND", f"模型配置不存在：{config_id}", status=404,
                               details={"model_id": config_id})
    base_url = str(row.get("base_url") or "")
    result: dict[str, Any] = {"model_id": int(config_id), "name": row["name"], "probed_at": _now_iso()}
    if not base_url:
        result.update(ok=False, latency_ms=0, supported=True,
                      error="未配置 base_url，无法探测")
        _record_probe(tenant_id, config_id, result)
        return result
    try:
        api_key = open_secret(str(row.get("api_key_cipher") or ""))
    except ModelConfigError as exc:
        result.update(ok=False, latency_ms=0, supported=True, error=exc.message)
        _record_probe(tenant_id, config_id, result)
        return result
    request = urllib.request.Request(
        f"{base_url}/models",
        headers={"Accept": "application/json", **({"Authorization": f"Bearer {api_key}"} if api_key else {})},
        method="GET",
    )
    started = time.time()
    try:
        with urllib.request.urlopen(request, timeout=max(1, timeout_ms) / 1000.0) as resp:
            body = resp.read(4096).decode("utf-8", errors="replace")
        models = _model_ids(body)
        result.update(ok=True, latency_ms=int((time.time() - started) * 1000), supported=True,
                      error="", discovered_models=models,
                      model_present=(str(row["model"]) in models) if models else None)
    except urllib.error.HTTPError as exc:
        supported = exc.code not in (404, 405)
        result.update(
            ok=False,
            latency_ms=int((time.time() - started) * 1000),
            supported=supported,
            http_status=exc.code,
            error=(f"凭据被拒绝（HTTP {exc.code}）") if exc.code in (401, 403)
            else (f"HTTP {exc.code}" if supported else "该服务未暴露 /models，无法据此判定连通性"),
        )
    except Exception as exc:  # noqa: BLE001
        result.update(ok=False, latency_ms=int((time.time() - started) * 1000), supported=True,
                      error=f"{type(exc).__name__}: {exc}")
    _record_probe(tenant_id, config_id, result)
    return result


def _model_ids(body: str) -> list[str]:
    try:
        payload = json.loads(body)
    except (ValueError, TypeError):
        return []
    entries = payload.get("data") if isinstance(payload, dict) else None
    if not isinstance(entries, list):
        return []
    ids: list[str] = []
    for entry in entries:
        if isinstance(entry, dict) and entry.get("id"):
            ids.append(str(entry["id"]))
    return ids[:50]


# ─────────── 存储实现（PG / 进程内）──────────

def _select(tenant_id: str) -> list[dict[str, Any]]:
    sql = f"SELECT {', '.join(COLUMNS)} FROM llm_model_config WHERE tenant_id = %s ORDER BY config_key, id"
    ok, rows = pg.execute([(sql, (tenant_id,))], fetch=True)
    if ok and rows is not None:
        return [_row_from_pg(row) for row in rows]
    with _memory_lock:
        return [dict(item) for item in _memory.values() if item["tenant_id"] == tenant_id]


def _find_row(tenant_id: str, config_id: int) -> dict[str, Any] | None:
    for row in _select(tenant_id):
        if int(row.get("id") or 0) == int(config_id):
            return row
    return None


def _exists(tenant_id: str, role_key: str, name: str, exclude_id: int | None = None) -> bool:
    return any(int(row.get("id") or 0) != int(exclude_id or -1)
               and row["config_key"] == role_key and row["name"] == name
               for row in _select(tenant_id))


def _next_id() -> int:
    """**仅降级路径使用**的进程内 id 分配（PG 可用时 id 由序列给，见 `_insert`）。

    跳过已占用的号，避免降级期间与既有内存行撞号。
    """
    with _memory_lock:
        value = _memory_seq["next"]
        while value in _memory:
            value += 1
        _memory_seq["next"] = value + 1
        return value


def _insert(row: dict[str, Any]) -> dict[str, Any]:
    """写入新配置，返回**带真实 id 的行**。

    id 由 PostgreSQL 序列分配（`RETURNING id`），**不再由进程内计数器决定**。
    修复前的缺陷：`id` 取自进程内计数（每次启动从 1 开始），
    库里已有行时（服务重启后 / 多实例 / 残留数据）**必然撞主键**；
    插入失败后只是 `logger.warning` 一句就降级到进程内存储 ——
    界面显示"保存成功"，重启后配置消失。这正是"静默降级"最坏的一种：
    降级路径本身没问题，问题是**本该成功的主路径被自己制造的冲突挡住了**。
    """
    columns = [key for key in COLUMNS if key != "id"]
    placeholders = ", ".join(["%s"] * len(columns))
    params = tuple(
        json.dumps(row.get("extra") or {}) if key == "extra" else row.get(key)
        for key in columns
    )
    sql = (f"INSERT INTO llm_model_config ({', '.join(columns)}) "
           f"VALUES ({placeholders}) RETURNING id")
    ok, rows = pg.execute([(sql, params)], fetch=True)
    if ok and rows:
        return {**row, "id": int(rows[0][0])}
    # PG 不可用/失败 → 进程内存储（如实降级；status() 会标 degraded）
    stored = {**row, "id": _next_id()}
    with _memory_lock:
        _memory[int(stored["id"])] = dict(stored)
    logger.warning("llm_model_config PG write failed, stored in process memory only (degraded)")
    return stored


def _persist(row: dict[str, Any]) -> None:
    assignments = ", ".join(f"{key} = %s" for key in COLUMNS if key != "id")
    params = (*tuple(json.dumps(row.get("extra") or {}) if key == "extra" else row.get(key) for key in COLUMNS if key != "id"), int(row["id"]))
    ok, _ = pg.execute([(f"UPDATE llm_model_config SET {assignments} WHERE id = %s", params)])
    if not ok:
        with _memory_lock:
            _memory[int(row["id"])] = dict(row)
        logger.warning("llm_model_config PG update failed, kept in process memory only (degraded)")


def _remove(tenant_id: str, config_id: int) -> None:
    ok, _ = pg.execute([("DELETE FROM llm_model_config WHERE tenant_id = %s AND id = %s",
                         (tenant_id, int(config_id)))])
    with _memory_lock:
        _memory.pop(int(config_id), None)
    if not ok:
        logger.warning("llm_model_config PG delete failed, removed from process memory only (degraded)")


def _record_probe(tenant_id: str, config_id: int, result: dict[str, Any]) -> None:
    ok, _ = pg.execute([(
        ("UPDATE llm_model_config SET last_probe_at = NOW(), last_probe_ok = %s, "
        "last_probe_latency_ms = %s, last_probe_error = %s WHERE tenant_id = %s AND id = %s"),
        (bool(result.get("ok")), int(result.get("latency_ms") or 0),
         str(result.get("error") or "")[:500], tenant_id, int(config_id)),
    )])
    with _memory_lock:
        item = _memory.get(int(config_id))
        if item is not None:
            item.update(last_probe_at=result.get("probed_at"), last_probe_ok=bool(result.get("ok")),
                        last_probe_latency_ms=int(result.get("latency_ms") or 0),
                        last_probe_error=str(result.get("error") or "")[:500])
    if not ok:
        logger.info("llm_model_config probe record not persisted (degraded)")


# ─────────── 状态 ───────────

def status() -> dict[str, Any]:
    _, key_source = resolve_master_key()
    storage = pg.status()
    return {
        "backend": "postgres" if not storage.get("degraded", True) else "memory",
        "degraded": bool(storage.get("degraded", True)),
        "reason": str(storage.get("reason", "")),
        "dsn": str(storage.get("dsn", "")),
        "key_source": key_source,
        "key_source_warning": (
            "MODEL_CONFIG_MASTER_KEY 未设置，正在使用本地密钥文件（仅限开发环境）"
            if str(key_source).startswith("file") else ""
        ),
        "encryption": "aes-256-gcm",
        "crypto_available": _crypto_available(),
        "roles": list(ROLE_KEYS),
    }


def _crypto_available() -> bool:
    try:
        _aesgcm()
        return True
    except Exception:  # noqa: BLE001
        return False
