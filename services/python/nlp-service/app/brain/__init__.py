"""Phase 4 大脑层（R4-01~R4-08 的 Python 侧落点）。

模块分工：
    llm_gateway    R4-03 模型路由 / 超时 / 重试 / Token 统计
    semantic_cache R4-04 语义缓存（向量相似 0.95）
    planner        R4-05 任务规划器（意图 → 任务模板 + 槽位）
    pipeline       R4-06 检索增强生成链路（LangGraph）
    gap            R4-07 信息缺口检测 + R4-08 来源标注
    retrieval      Phase 3 检索服务客户端
"""
