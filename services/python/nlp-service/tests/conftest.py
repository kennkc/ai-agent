# -*- coding: utf-8 -*-
"""pytest 全局前置（2026-09-20 新增）。

**必须在任何 `app.*` 导入之前**把测试环境钉死，原因是一个实测踩到的顺序陷阱：

* `app/brain/pg.py` 在**导入期**就把 `BRAIN_PG_ENABLED` 读成模块常量 `PG_ENABLED`；
* pytest 按文件名字典序收集 —— `test_brain.py` 排在 `test_model_config.py` **之前**，
  于是后者文件顶部那句 `os.environ.setdefault("BRAIN_PG_ENABLED", "false")`
  **永远晚一步执行**，`PG_ENABLED` 早已是默认的 `True`。

后果是测试**实际连上了真实 PostgreSQL**：既污染开发库，
又让结果依赖"库里当时有没有残留数据" —— 2026-09-20 实测因此 3 项失败
（`test_create_list_get_roundtrip_hides_secret` 等，报 `duplicate key ... llm_model_config_pkey`）。

pytest 保证同目录 `conftest.py` 先于测试模块导入，故这里是唯一正确的落点。
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# 1) 让底座走进程内后端（快、确定、不污染真实库）
os.environ["BRAIN_PG_ENABLED"] = "false"
# 2) 固定主密钥：否则每次跑测试都会新建 `.model-config-key` 或依赖环境变量
os.environ.setdefault("MODEL_CONFIG_MASTER_KEY", "0123456789abcdef0123456789abcdef")

# 双保险：即便将来 `pg.py` 改成延迟读取，也把已导入的模块常量压回 False。
# 需要真实连接语义的用例（DDL 回归）自行 `monkeypatch.setattr(brain_pg, "PG_ENABLED", True)`。
try:  # pragma: no cover - 导入期不因环境问题阻断收集
    from app.brain import pg as _pg

    _pg.PG_ENABLED = False
except Exception:
    pass
