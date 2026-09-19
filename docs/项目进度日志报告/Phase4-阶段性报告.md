# Phase 4 大脑期 · 阶段性报告

| 项 | 内容 |
|---|---|
| 阶段 | Phase 4 · 大脑期（Brain Stage） |
| 项目 | Agent-Lifeform · AI Agent 生命体架构 |
| 仓库 | `E:\AI\ai-agent`（开发分支 `dev`，跟踪 `origin/workbuddy/main`） |
| 分支 | `dev` / `codex/main` / `workbuddy/main`（三分支同内容对齐） |
| 报告日期 | 2026-09-19 |
| 代码基线 | `19c5347` |
| 需求范围 | R4-01 ~ R4-09 + 终端线 R-C04 |
| 阶段结论 | **通过**（Must 7/7 · Should 2/2 · 终端线 R-C04 达标；1 项外部引擎依赖诚实降级） |

---

## 1. 阶段目标回顾

Phase 3（躯体期）让生命体**有了知识**：文档能入库、能向量化、能语义检索，但**不会思考**——
问答仍由 `RagPipeline` 的模板拼接完成，没有会话状态，没有来源标注，也没有"知识不够就说不知道"的能力。

Phase 4 的目标是**长出大脑**：把「意图 → 规划 → 检索 → 覆盖度评估 → 生成 → 来源标注」串成一条
可回放的决策链，并让会话有状态、可持久化、可恢复。

设计文档定义的链路（`AI-Agent生命体架构-阶段性需求拆解分析.md` Phase 4 表）：

```
问答：问题 → 意图识别 → 任务规划 → 检索（体层） → 覆盖度评估 → 生成 → 来源标注 → 回答
会话：NEW → ACTIVE ⇄ IDLE → TIMEOUT/CLOSED（Redis 持久化，TTL 2h，最近 K 轮进 Prompt）
缓存：相似问题命中语义缓存，命中率 ≥ 20%
```

---

## 2. 交付物清单

### 2.1 Python nlp-service · 大脑层（新增 `app/brain/`，7 文件 / 1374 行 + 测试 399 行）

| 模块 | 行数 | 对应需求 |
|---|---|---|
| `llm_gateway.py` | 330 | R4-03 LLM Gateway（L1/L2/L3 路由 + 超时重试 + Token 统计） |
| `pipeline.py` | 416 | R4-06 LangGraph 决策链 `plan→retrieve→gap→generate` |
| `semantic_cache.py` | 207 | R4-04 语义缓存（Redis 向量相似 0.95，进程内兜底并标 degraded） |
| `planner.py` | 142 | R4-05 任务规划器（qa/summarize/retrieve/chat） |
| `gap.py` | 174 | R4-07 缺口检测 + R4-08 来源标注 |
| `retrieval.py` | 95 | R4-06 复用体层检索 |
| `__init__.py` | 10 | 包导出 |

端点：`/api/nlp/brain/{ask,plan,health,cache/stats}`。

### 2.2 Java session-manager（R4-01 / R4-02 / R4-06 接线）

| 交付物 | 行数 | 对应需求 |
|---|---|---|
| `fsm/SessionFsm.java` | 94 | R4-01 会话状态机 |
| `fsm/SessionStore.java` | 188 | R4-02 Redis Hash + TTL + 最近 K 轮上下文 |
| `orchestration/BrainClient.java` | 143 | R4-06 大脑层调用（含降级，不伪造回答） |
| `controller/SessionController.java`（改） | 244 | `/ask` 走大脑层 + `/context` 端点 |
| 测试（4 个） | 483 | FSM 迁移 / 持久化 / 大脑层接线 / Spring 装配约束 |

### 2.3 wp-bff（R-C04 / D5 / R4-06 代理）

`GET /api/wp/brain`、`POST /api/wp/brain/ask`、`GET /api/wp/brain/{decision_id}`；
契约三条路径由 `planned` 改为 `implemented`（implemented 8 → 11 路径）。

### 2.4 终端线

| 端 | 交付 |
|---|---|
| work-platform（R4-09） | `askBrain()` + 来源（含相似度与片段）/ 缺口提示 / 降级徽标 / 决策链回放 |
| Console（R-C04） | 大脑视图真实指标 + 交互终端多轮对话；Mock/Api 双数据源同构 |

---

## 3. 需求达成情况

| ID | 需求 | 结论 | 关键实测 |
|---|---|:---:|---|
| R4-01 | 会话状态机 FSM | ✅ | 终态拒绝 → 409 AGENT_CONFLICT |
| R4-02 | 会话持久化 | ✅ | **杀进程重启后会话仍 ACTIVE、消息 2 条** |
| R4-03 | LLM Gateway | ✅（降级） | 模板后端如实标 `generator=template` |
| R4-04 | 语义缓存 | ✅ | 命中率 **57.1%~66.7%**（DoD ≥20%） |
| R4-05 | 任务规划器 | ✅ | 四类模板映射，如实返回 `planner=rule` |
| R4-06 | RAG 全链路 | ✅ | 104~186ms，5 条来源 + 决策链 |
| R4-07 | 缺口检测 | ✅ | 覆盖度 0.2877 → 「部分信息可能不完整」 |
| R4-08 | 来源标注 | ✅ | 来源含相似度，界面可展开片段 |
| R4-09 | 前端对话界面 | ✅ | Vite 代理实测 200，多轮可用 |
| R-C04 | Console 大脑视图 + 交互终端 | ✅ | 2 轮真实问答，指标真实 |

---

## 4. 架构演进

```
Phase 3：文档 → 分块 → 嵌入 → 三级存储 → 检索（无会话、无规划、无生成）
Phase 4：问题 → 意图 → 规划 → 检索 → 覆盖度 → 生成 → 来源标注 → 回答
                ↑会话 FSM（Redis）   ↑语义缓存（Redis 向量）   ↑决策链（D5 可回放）
```

新增的两个"大脑组件"落点：

- **组件 2 任务规划器**：`planner.py`（规则模板映射，P5/P6 升级为模型规划）。
- **C21 Memory Graph 之外的最近会话**：`SessionStore` 的"最近 K 轮"窗口（本阶段以 Redis List + 截断实现，记忆图谱留待后续阶段）。

---

## 5. 技术债与口径差异（均已在《技术债台账》留痕）

| 项 | 设计口径 | 实现口径 | 性质 |
|---|---|---|---|
| 编排框架 | LangGraph 0.2+ | 实装 1.2.11（API 一致） | 版本差异，非选型变更 |
| LLM 接入 | LiteLLM / 自研 Gateway | 自研 `LlmGateway`，默认模板降级 | DEBT-015/016 |
| 缺口阈值 | 覆盖度 ≥0.85 | 默认 0.35（环境变量可调） | 实测校准，见 §4.14 |
| 可用性打分 | 未规定 | 以 `score`（向量召回）判可用 | 重排分量纲随后端而变 |
| Console 图表 | 含意图分布/会话趋势 | 两图为演示值并显式标注 | 后端暂无该统计接口 |

新登记：**DEBT-014**（会话消息全量保留）、**DEBT-015/016**（LLM 引擎未接）、**DEBT-017**（规则规划器）。

---

## 6. 经验与下一步

1. **运行态验证是最有价值的投入**：本阶段 3 个真实缺陷全部由"真起进程 + 真数据"抓到，单测全绿时它们一个都不暴露。
2. **降级可见要贯穿到界面**：模板生成、本地检索直出、大脑层不可用三条分支都在响应里标了 `generator` / `degraded_reasons`，前端据此打标——不做"看起来成功"的假动作。
3. **下一步（Phase 5 四肢期）**：工具调用与执行视图（R-C05）；在此之前建议先闭合 DEBT-015/016（接入真实 LLM），让 R4-03/R4-06 的"模型 vs 模板"对比验收有真实对照。
