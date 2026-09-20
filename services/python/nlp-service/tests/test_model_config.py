# -*- coding: utf-8 -*-
"""WB-10 模型接入配置测试（2026-09-20 新增）。

覆盖四层：
  1. 凭据保险柜 —— AES-GCM 可逆、密文不等于明文、篡改被拒、脱敏不泄露；
  2. 配置 CRUD —— 角色校验、启用必须有地址、同角色重名冲突、**局部更新不清空字段**；
  3. 引擎装配 —— 角色优先于层级级联、角色缺失时如实回落并标 degraded、重载失败保留旧装配；
  4. PG 底座缺陷回归 —— 多模块各自的 DDL 都必须执行（修复前只有先到者的会执行）。

测试**不依赖真实 PostgreSQL**：由同目录 `conftest.py` 在任何 `app.*` 导入之前
把 `BRAIN_PG_ENABLED` 钉为 false，走进程内后端；另用假的 `pg.execute` 单独断言 SQL 形状，
用假的 `pg._connect` 回归 DDL 行为。

> 注意：这里**不能**用模块级 `os.environ.setdefault("BRAIN_PG_ENABLED", "false")` 来达到目的 ——
> `pg.py` 在导入期就把该变量读成常量，而 pytest 按文件名字典序收集，
> `test_brain.py` 先于本文件导入 `pg`，那句 setdefault 永远晚一步（实测导致测试连上真实库）。
> 环境钉死统一放 `conftest.py`。
"""
from __future__ import annotations

import os
import sys
import uuid

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app import main, model_config  # noqa: E402
from app.brain import pg as brain_pg  # noqa: E402
from app.brain.llm_gateway import (  # noqa: E402
    L1, L2, LlmEngine, LlmGateway, LlmRequest, TemplateEngine,
)

ENVELOPE_KEYS = {"code", "message", "details"}


def _tenant() -> str:
    return f"test-{uuid.uuid4().hex[:12]}"


def _payload(**over) -> dict:
    data = {
        "config_key": "generate",
        "name": "主模型",
        "provider": "deepseek",
        "base_url": "https://api.deepseek.com/v1",
        "model": "deepseek-chat",
        "api_key": "sk-abcdefghijklmnop",
        "enabled": True,
    }
    data.update(over)
    return data


# ─────────── 1. 凭据保险柜 ───────────

def test_secret_roundtrip_and_cipher_is_not_plaintext():
    plain = "sk-abcdefghijklmnop"
    sealed = model_config.seal_secret(plain)
    assert sealed, "非空明文必须产出密文"
    assert plain not in sealed, "密文里不得出现明文"
    assert model_config.open_secret(sealed) == plain


def test_mask_secret_keeps_only_public_prefix_and_last_four():
    masked = model_config.mask_secret("sk-abcdefghijklmnop")
    assert masked == "sk-***mnop"
    assert "abcdefghijkl" not in masked, "秘密部分不得出现在脱敏结果里"
    assert model_config.mask_secret("") == ""
    assert set(model_config.mask_secret("short")) == {"*"}


def test_tampered_cipher_is_rejected_not_silently_ignored():
    sealed = model_config.seal_secret("sk-secret-value")
    tampered = sealed[:-6] + ("AAAAAA" if not sealed.endswith("AAAAAA") else "BBBBBB")
    with pytest.raises(model_config.ModelConfigError) as exc:
        model_config.open_secret(tampered)
    assert exc.value.code == "AGENT_CONFIG_DECRYPT_FAILED"


def test_seal_refuses_when_master_key_unusable(monkeypatch):
    """密钥不可用时**拒绝写入**，绝不退化成明文落库。"""
    monkeypatch.setattr(model_config, "resolve_master_key", lambda: (None, "error: forced"))
    with pytest.raises(model_config.ModelConfigError) as exc:
        model_config.seal_secret("sk-x")
    assert exc.value.code == "AGENT_CONFIG_KEY_UNAVAILABLE"


# ─────────── 2. 配置 CRUD ───────────

def test_create_list_get_roundtrip_hides_secret():
    tenant = _tenant()
    created = model_config.create_config(tenant, _payload())
    assert created["api_key_hint"] == "sk-***mnop"
    assert created["has_api_key"] is True
    # 对外结构里不允许出现任何密钥字段
    assert "api_key" not in created and "api_key_cipher" not in created
    assert created["role_label"] == "内容生成"

    listing = model_config.list_configs(tenant)
    assert listing["total"] == 1
    assert [item["name"] for item in listing["by_role"]["generate"]] == ["主模型"]
    assert listing["enabled"] == 1
    assert all("api_key" not in item and "api_key_cipher" not in item for item in listing["items"])

    fetched = model_config.get_config(tenant, created["id"])
    assert fetched["model"] == "deepseek-chat"


def test_reject_unknown_role_and_bad_url():
    tenant = _tenant()
    with pytest.raises(model_config.ModelConfigError) as exc:
        model_config.create_config(tenant, _payload(config_key="no-such-role"))
    assert exc.value.code == "AGENT_BAD_REQUEST"
    assert "no-such-role" in str(exc.value.details.get("supported", "")) or \
        "no-such-role" not in str(exc.value.details)

    with pytest.raises(model_config.ModelConfigError):
        model_config.create_config(tenant, _payload(base_url="ftp://bad"))


def test_enabled_without_endpoint_is_rejected():
    """启用但没地址/模型名 → 拒绝。否则运行期只表现为"引擎不可用"，极难排查。"""
    tenant = _tenant()
    with pytest.raises(model_config.ModelConfigError) as exc:
        model_config.create_config(tenant, _payload(model=""))
    assert exc.value.details.get("field") == "enabled"


def test_duplicate_name_within_role_conflicts():
    tenant = _tenant()
    model_config.create_config(tenant, _payload())
    with pytest.raises(model_config.ModelConfigError) as exc:
        model_config.create_config(tenant, _payload())
    assert exc.value.code == "AGENT_CONFLICT"
    assert exc.value.status == 409
    # 同租户不同角色可以同名
    other = model_config.create_config(tenant, _payload(config_key="plan"))
    assert other["id"] != 0


def test_update_is_partial_and_does_not_wipe_fields():
    """只改一个开关，不得顺手清空 base_url / model —— 这是最容易被写错的地方。"""
    tenant = _tenant()
    created = model_config.create_config(tenant, _payload())
    updated = model_config.update_config(tenant, created["id"], {"enabled": False})
    assert updated["enabled"] is False
    assert updated["base_url"] == "https://api.deepseek.com/v1"
    assert updated["model"] == "deepseek-chat"
    assert updated["api_key_hint"] == "sk-***mnop", "未提供 api_key 时不得清空原有凭据"


def test_update_can_rotate_and_clear_secret():
    tenant = _tenant()
    created = model_config.create_config(tenant, _payload())
    rotated = model_config.update_config(tenant, created["id"], {"api_key": "sk-zzzzzzzzzzzz9999"})
    assert rotated["api_key_hint"] == "sk-***9999"
    cleared = model_config.update_config(tenant, created["id"], {"api_key": ""})
    assert cleared["api_key_hint"] == "" and cleared["has_api_key"] is False


def test_delete_and_missing_lookup():
    tenant = _tenant()
    created = model_config.create_config(tenant, _payload())
    removed = model_config.delete_config(tenant, created["id"])
    assert removed["deleted"] is True and removed["name"] == "主模型"
    assert model_config.list_configs(tenant)["total"] == 0
    with pytest.raises(model_config.ModelConfigError) as exc:
        model_config.get_config(tenant, created["id"])
    assert exc.value.status == 404


def test_tenant_isolation():
    tenant_a, tenant_b = _tenant(), _tenant()
    model_config.create_config(tenant_a, _payload())
    assert model_config.list_configs(tenant_b)["total"] == 0


def test_resolve_engines_only_enabled_and_decrypts():
    tenant = _tenant()
    model_config.create_config(tenant, _payload(name="启用主模型", enabled=True, routing_weight=90))
    model_config.create_config(tenant, _payload(name="停用备选", enabled=False))
    model_config.create_config(tenant, _payload(config_key="intent", name="意图小模型",
                                                model="deepseek-chat", enabled=True))
    specs = model_config.resolve_engines(tenant)
    # 顺序由 ROLES 声明序决定，不作为语义断言；这里只断言「停用的被排除」
    assert sorted(spec["role"] for spec in specs) == ["generate", "intent"], "停用的配置不得进入引擎列表"
    generate_spec = next(spec for spec in specs if spec["role"] == "generate")
    assert generate_spec["api_key"] == "sk-abcdefghijklmnop", "构造引擎时才解密"
    assert generate_spec["max_tokens"] == 512


# ─────────── 3. 引擎装配（角色路由）──────────

class _FixedEngine(LlmEngine):
    def __init__(self, text: str, level: str = L2, role: str = "") -> None:
        self.text = text
        self.level = level
        self.role = role
        self.name = f"fixed:{role or level}"

    def available(self) -> bool:
        return True

    def generate(self, request: LlmRequest, timeout: float) -> str:
        return self.text


class _DeadEngine(LlmEngine):
    def __init__(self, level: str = L2, role: str = "") -> None:
        self.level = level
        self.role = role
        self.name = f"dead:{role or level}"

    def available(self) -> bool:
        return True

    def generate(self, request: LlmRequest, timeout: float) -> str:
        raise OSError("connection refused")


def test_role_engine_takes_priority_over_level_cascade():
    gateway = LlmGateway(
        engines=[TemplateEngine()],
        role_engines={"generate": [_FixedEngine("模型生成的内容", role="generate")]},
    )
    response = gateway.generate(LlmRequest(prompt="Question: x", level=L1, role="generate"))
    assert response.text == "模型生成的内容"
    assert response.generator == "model" and response.degraded is False
    assert response.role == "generate" and response.level == L2


def test_role_engine_carries_its_own_generation_params():
    engine = _FixedEngine("ok", role="generate")
    engine.max_tokens, engine.temperature = 2048, 0.9
    adjusted = engine.apply_defaults(LlmRequest(prompt="p", max_tokens=512, temperature=0.2))
    assert (adjusted.max_tokens, adjusted.temperature) == (2048, 0.9)

    gateway = LlmGateway(engines=[TemplateEngine()], role_engines={"generate": [engine]})
    assert gateway.generate(LlmRequest(prompt="Question: x", role="generate")).generator == "model"


def test_unconfigured_role_falls_back_to_template_and_is_marked_degraded():
    gateway = LlmGateway(engines=[TemplateEngine()], role_engines={})
    response = gateway.generate(LlmRequest(prompt="Question: x", level=L2, role="generate"))
    assert response.degraded is True and response.generator == "template"


def test_dead_role_engine_falls_through_to_level_cascade():
    """角色引擎全挂 → 仍按既有分层降级落到模板，而不是把整条链路打死。"""
    gateway = LlmGateway(engines=[TemplateEngine()], role_engines={"generate": [_DeadEngine(role="generate")]},
                         max_retries=0)
    response = gateway.generate(LlmRequest(prompt="Question: x", level=L2, role="generate"))
    assert response.generator == "template" and response.degraded is True
    assert gateway.stats.timeouts >= 1


def test_apply_specs_registers_only_available_engines():
    gateway = LlmGateway(engines=[TemplateEngine()])
    summary = gateway.apply_specs([
        {"role": "generate", "name": "主模型", "provider": "deepseek", "base_url": "https://api.deepseek.com/v1",
         "model": "deepseek-chat", "api_key": "sk-x", "tier": L2, "max_tokens": 1024, "temperature": 0.3,
         "timeout_ms": 9000},
        {"role": "intent", "name": "缺地址的配置", "base_url": "", "model": "qwen-turbo", "tier": L1},
    ])
    assert sorted(summary.keys()) == ["generate"], "缺 base_url 的规格不得注册成「永远不可用」的引擎"
    engine = gateway.role_engines["generate"][0]
    assert engine.name == "generate:主模型" and engine.timeout_ms == 9000
    assert gateway.health()["role_configured"] == ["generate"]


def test_reload_failure_keeps_previous_engines(monkeypatch):
    gateway = LlmGateway(engines=[TemplateEngine()],
                         role_engines={"generate": [_FixedEngine("keep", role="generate")]})

    def _boom(_tenant: str = "default"):
        raise RuntimeError("db gone")

    monkeypatch.setattr(model_config, "resolve_engines", _boom)
    result = gateway.reload_from_model_config()
    assert result["ok"] is False and result["kept_previous"] is True
    assert gateway.role_engines["generate"], "一次读取失败不得清空线上装配"
    assert gateway.generate(LlmRequest(prompt="Question: x", role="generate")).text == "keep"


# ─────────── 4. 存储层（SQL 形状 + PG 底座缺陷回归）──────────

def _pg_stub(monkeypatch, next_id: int, captured: list[tuple]):
    """假 PG：INSERT 走 `RETURNING id` 返回 `next_id`，其余语句返回空结果。

    严格一点更有价值 —— 只有 INSERT 才回 id，别让 SELECT 也"看起来"拿到了行。
    """
    def _fake_execute(statements, fetch: bool = False):
        captured.extend(statements)
        sql = statements[-1][0] if statements else ""
        if sql.lstrip().upper().startswith("INSERT"):
            return (True, [(next_id,)])
        return (True, [])

    monkeypatch.setattr(brain_pg, "execute", _fake_execute)
    monkeypatch.setattr(model_config, "_schema_ready", {"done": True})


def test_pg_path_builds_insert_with_all_columns(monkeypatch):
    tenant = _tenant()
    captured: list[tuple] = []
    _pg_stub(monkeypatch, 101, captured)
    created = model_config.create_config(tenant, _payload())
    sql, params = captured[-1]
    assert sql.startswith("INSERT INTO llm_model_config (") and "api_key_cipher" in sql
    assert " RETURNING id" in sql, "id 必须由序列分配（RETURNING id）"
    assert not sql.startswith("INSERT INTO llm_model_config (id,"), "不得自行指定 id"
    assert len(params) == len(model_config.COLUMNS) - 1, "id 不参与 INSERT（由序列给）"
    assert "sk-abcdefghijklmnop" not in params, "落库参数里不得出现明文凭据"
    assert created["id"] == 101, "对外 id 必须是数据库返回的真实 id"


def test_insert_uses_db_sequence_so_restart_cannot_collide(monkeypatch):
    """回归：**新配置的 id 不得来自进程内计数器**。

    缺陷原型（2026-09-20 实测）：`_next_id()` 每次启动从 1 开始，而库里已有行时
    必然撞 `llm_model_config_pkey`；插入失败后只 `logger.warning` 就降级到进程内存储 ——
    界面显示"保存成功"，**重启后配置消失**。

    这里模拟"库里已有旧数据"：数据库序列早已推进到 4242，进程内计数器还是 1。
    插入成功时必须采用序列给的值，而不是计数器给的 1。
    """
    tenant = _tenant()
    captured: list[tuple] = []
    _pg_stub(monkeypatch, 4242, captured)
    created = model_config.create_config(tenant, _payload(name="重启后新建"))
    assert created["id"] == 4242, "必须用数据库序列分配的 id，而不是进程内计数"
    assert created["has_api_key"] is True
    assert created["api_key_hint"] == "sk-***mnop"


def test_insert_falls_back_to_memory_only_when_pg_write_fails(monkeypatch):
    """PG 真失败才走内存分配；降级分配要跳过已占用的号，不覆盖既有内存行。"""
    tenant = _tenant()
    monkeypatch.setattr(brain_pg, "execute", lambda statements, fetch=False: (False, None))
    model_config.reset_for_test()
    monkeypatch.setattr(model_config, "_schema_ready", {"done": True})
    first = model_config.create_config(tenant, _payload(name="内存A"))
    second = model_config.create_config(tenant, _payload(name="内存B"))
    assert first["id"] != second["id"], "降级分配不得撞号"
    assert model_config.list_configs(tenant)["total"] == 2


def test_ddl_includes_sequence_repair():
    """回归：DDL 必须带序列校正语句。

    历史版本用「进程内计数器 + 显式 id」写入，**从不推进 SERIAL 序列**，
    实测表里已有 `id=10` 而序列停在 `1`。缺了这条语句，
    改成"由序列分配 id"后第一次插入就撞主键。
    """
    joined = " ".join(model_config.DDL)
    assert "setval" in joined and "pg_get_serial_sequence" in joined
    assert "llm_model_config" in joined


def test_ensure_schema_applies_every_module_ddl(monkeypatch):
    """回归：两个模块各自的 DDL 都要执行。

    修复前 `pg.ensure_schema` 靠"首次调用"短路，第二个调用模块的建表语句被静默跳过，
    表现为"写入总是降级到进程内存储"，而日志里只看到一句 pg_unavailable 式的告警。
    """
    executed: list[str] = []

    class _Cursor:
        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

        def execute(self, sql, params=None):
            executed.append(sql)

    class _Conn:
        def cursor(self):
            return _Cursor()

        def commit(self):
            pass

        def rollback(self):
            pass

        def close(self):
            pass

    brain_pg.reset_for_test()
    monkeypatch.setattr(brain_pg, "PG_ENABLED", True)
    monkeypatch.setattr(brain_pg, "_connect", lambda: _Conn())
    try:
        assert brain_pg.ensure_schema(["CREATE TABLE IF NOT EXISTS alpha (id int)"]) is True
        assert brain_pg.ensure_schema(["CREATE TABLE IF NOT EXISTS beta (id int)"]) is True
        assert any("alpha" in sql for sql in executed)
        assert any("beta" in sql for sql in executed), "第二个调用模块的 DDL 未执行 —— 缺陷回归"
        assert brain_pg.status()["applied_ddl"] == 2
        # 幂等：重复调用不重复执行
        before = len(executed)
        brain_pg.ensure_schema(["CREATE TABLE IF NOT EXISTS beta (id int)"])
        assert len(executed) == before
    finally:
        brain_pg.reset_for_test()


# ─────────── 5. HTTP 层 ───────────

def _client():
    return TestClient(main.app)


def test_http_roles_dictionary():
    with _client() as client:
        body = client.get("/api/nlp/models/roles").json()
    assert [role["key"] for role in body["roles"]] == list(model_config.ROLE_KEYS)
    assert "generate" in body["tiers"] or "L2" in body["tiers"]
    assert any(provider["key"] == "deepseek" for provider in body["providers"])


def test_http_crud_flow_end_to_end():
    tenant = _tenant()
    headers = {"X-Tenant-Id": tenant}
    with _client() as client:
        created = client.post("/api/nlp/models", json=_payload(), headers=headers)
        assert created.status_code == 200, created.text
        created_body = created.json()
        model_id = created_body["item"]["id"]
        assert created_body["item"]["api_key_hint"] == "sk-***mnop"
        assert "sk-abcdefghijklmnop" not in created.text, "响应体里不得泄露明文密钥"

        listed = client.get("/api/nlp/models", headers=headers).json()
        assert listed["total"] == 1 and "llm" in listed

        patched = client.patch(f"/api/nlp/models/{model_id}", json={"enabled": False}, headers=headers)
        assert patched.status_code == 200
        assert patched.json()["item"]["enabled"] is False
        assert patched.json()["item"]["base_url"] == "https://api.deepseek.com/v1"

        reloaded = client.post("/api/nlp/models/reload").json()
        assert "ok" in reloaded["reload"]

        deleted = client.delete(f"/api/nlp/models/{model_id}", headers=headers)
        assert deleted.status_code == 200 and deleted.json()["deleted"] is True

        missing = client.get("/api/nlp/models/roles").status_code
        assert missing == 200


def test_http_errors_use_unified_envelope_with_specific_code():
    tenant = _tenant()
    headers = {"X-Tenant-Id": tenant}
    with _client() as client:
        bad_role = client.post("/api/nlp/models", json=_payload(config_key="nope"), headers=headers)
        assert bad_role.status_code == 400
        assert set(bad_role.json()) == ENVELOPE_KEYS
        assert bad_role.json()["code"] == "AGENT_BAD_REQUEST"

        client.post("/api/nlp/models", json=_payload(), headers=headers)
        conflict = client.post("/api/nlp/models", json=_payload(), headers=headers)
        assert conflict.status_code == 409 and conflict.json()["code"] == "AGENT_CONFLICT"

        not_found = client.patch("/api/nlp/models/999999", json={"enabled": True}, headers=headers)
        assert not_found.status_code == 404 and not_found.json()["code"] == "AGENT_NOT_FOUND"


def test_http_probe_reports_honestly_when_base_url_missing():
    tenant = _tenant()
    headers = {"X-Tenant-Id": tenant}
    with _client() as client:
        payload = _payload(name="无地址", base_url="", enabled=False)
        model_id = client.post("/api/nlp/models", json=payload, headers=headers).json()["item"]["id"]
        probed = client.post(f"/api/nlp/models/{model_id}/test", headers=headers).json()
    assert probed["ok"] is False and "base_url" in probed["error"]
    assert probed["supported"] is True
