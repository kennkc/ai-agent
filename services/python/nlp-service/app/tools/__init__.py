"""Phase 5 四肢期 · Python 侧工具能力包。

- :mod:`app.tools.function_calling` —— R5-06 LLM 工具决策 ↔ 执行 ↔ 结果回填
"""
from app.tools.function_calling import FUNCTION_CALLING, FunctionCallingAdapter  # noqa: F401

__all__ = ["FUNCTION_CALLING", "FunctionCallingAdapter"]