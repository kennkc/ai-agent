# Phase 6 并发编排 · 前置设计冻结稿

> **状态**：完整 Phase 6 继续冻结；Blocking 端点已完成最小闭环，本节只记录边界与证据
> **日期**：2026-09-20（2026-09-21 补 Blocking 最小闭环）
> **依赖**：MC-01 R-MC01-01~05、Phase 5 工具执行与审计、WB-10 模型配置
> **决策**：暂不启动 Phase 6 完整开发；Blocking 端点允许真实派生或显式 fail-closed，不得伪造 planner / 工件 / 审批结果。

## 1. 目标与非目标

### 目标

为未来 Phase 6 开发提供可直接落库、可直接写契约测试的接口冻结：

1. DAG 编译结果的数据结构；
2. 节点执行状态机与重试/取消语义；
3. 协作总线消息信封；
4. 共享工件层元数据；
5. 验收门与证据模型；
6. 与 MC-01、工具执行、模型角色的边界。

### 非目标

- 不实现 planner / merger / dispatcher；
- 不实现共享工件存储服务；
- 不实现冲突消解；
- 不接入前端新的 Phase 6 页面；
- 不改变 MC-01 已验证的 durable consumer、DLQ 与心跳语义。

## 2. DAG 编译契约

```json
{
  "workflow_id": "wf-20260920-0001",
  "domain_id": "collab-20260920-0001",
  "tenant_id": "default",
  "mode": "fanout",
  "max_concurrency": 8,
  "budget_ms": 120000,
  "nodes": [],
  "edges": [],
  "entry_nodes": [],
  "terminal_nodes": [],
  "artifact_policy": {},
  "gate_policy": {}
}
```

节点类型首期冻结为：

| 类型 | 作用 | 必填输入 |
|---|---|---|
| `plan` | 任务拆解或再规划 | 原始任务、上下文引用 |
| `retrieve` | 知识检索 | query、top_k、filter |
| `generate` | 模型生成 | prompt、role、budget |
| `tool` | 工具执行 | tool_name、args、sandbox_required |
| `verify` | 自校验或交叉验证 | 目标工件、验收规则 |
| `merge` | 结果合并 | 输入工件列表、合并策略 |
| `approval` | 人工/策略审批 | 风险等级、审批策略 |

DAG 约束：

- `workflow_id` 全局唯一；
- 节点 ID 在单个 DAG 内唯一；
- 边必须指向已声明节点，禁止自环；
- 首期只支持有向无环图；
- `entry_nodes` 入度为 0，`terminal_nodes` 出度为 0；
- `max_concurrency` 默认 8，超过需显式配置理由。

## 3. 节点状态机

```text
pending -> ready -> running -> succeeded
                     |  |-> failed -> retry_wait -> ready
                     |  |-> cancelled
                     |  |-> blocked
                     |-> timed_out -> retry_wait / failed
```

状态语义：

| 状态 | 含义 |
|---|---|
| `pending` | 已入图，前置依赖未满足 |
| `ready` | 依赖满足，等待调度 |
| `running` | 已占用执行槽 |
| `retry_wait` | 可重试，等待退避时间 |
| `succeeded` | 产出符合节点契约 |
| `failed` | 重试耗尽或不可重试错误 |
| `cancelled` | 用户/策略取消 |
| `blocked` | 缺少人工审批、外部依赖或冲突未解 |
| `timed_out` | 超出节点或全局预算 |

硬约束：

- 每次尝试必须使用新的 `attempt_id`，但保持 `request_id` 稳定；
- 成功节点不得重复执行；
- 重试必须幂等，副作用只能由工具侧幂等键抑制；
- 全局预算耗尽后不得启动新节点；
- 取消是协作式，不允许强杀造成状态未知。

## 4. 协作消息信封

```json
{
  "request_id": "req-0001",
  "workflow_id": "wf-20260920-0001",
  "domain_id": "collab-20260920-0001",
  "node_id": "n-generate-01",
  "attempt_id": "att-01",
  "tenant_id": "default",
  "type": "dispatch",
  "from": "planner",
  "to": "agent-a",
  "created_at": "2026-09-20T17:00:00+08:00",
  "deadline_ms": 20000,
  "payload": {}
}
```

`type`：

- `dispatch`
- `heartbeat`
- `result`
- `failure`
- `negotiate`
- `cancel`
- `deadletter`

消息必须复用 MC-01 的 `request_id` 幂等语义；`node_id + attempt_id` 用于业务级追踪。

## 5. 共享工件契约

```json
{
  "artifact_id": "art-0001",
  "workflow_id": "wf-20260920-0001",
  "node_id": "n-generate-01",
  "kind": "document",
  "title": "竞品分析摘要",
  "version": 1,
  "content_ref": "s3://lifeform-artifacts/...",
  "checksum": "sha256:...",
  "producer": "agent-a",
  "provenance": ["doc-1", "doc-2"],
  "visibility": "workflow",
  "created_at": "2026-09-20T17:00:00+08:00"
}
```

规则：

- 工件内容与元数据分离，元数据可落 PG，正文放对象存储；
- 新版本递增 `version`，不得覆盖旧版本；
- `checksum` 用于合并与验收去重；
- `provenance` 必须能回溯知识文档或上游工件；
- `visibility` 首期只允许 `private / workflow / tenant`。

## 6. 验收门契约

```json
{
  "gate_id": "gate-fact-01",
  "workflow_id": "wf-20260920-0001",
  "type": "factuality",
  "required": true,
  "state": "pending",
  "inputs": ["art-0001"],
  "rules": {"min_score": 0.85},
  "evidence": [],
  "decided_by": "verifier",
  "decided_at": null
}
```

状态：

```text
pending -> passed
        -> rejected
        -> needs_revision
        -> blocked
```

约束：

- 必需门未通过时，工作流不得进入 `succeeded`；
- 拒绝与需要修订必须带可执行原因；
- 人工审批必须记录操作者、时间和证据；
- 验收结论是业务真相，不得由前端本地计算替代。

## 7. 与既有模块的边界

| 能力 | 归属 | Phase 6 只做什么 |
|---|---|---|
| 消息可靠投递、幂等、DLQ、心跳 | MC-01 collab-bus | 发布/消费冻结后的消息信封 |
| 模型选择、密钥、Token 计量 | WB-10 nlp-service | 按 role 请求生成，不自建模型配置 |
| 工具执行、沙箱、审计 | tool-executor | 通过既有工具 API 调用 |
| 工作平台协作展示 | wp-bff + Vue | 消费聚合视图，不在前端实现编排 |
| DAG 调度与合并 | Phase 6 | 使用本冻结稿，另立开发任务 |
| 工件存储与验收门 | Phase 6 | 使用本冻结稿，另立服务设计 |

## 8. 解冻前必须完成的检查

1. MC-01 多域、多实例 consumer 压测通过；
2. `data_quality.synthetic_fields` 清零或明确降级展示；
3. Phase 6 必需 BFF planned 端点有独立排期；
4. 模型配置生产 fail-closed 已验证；
5. 工件与验收门存储方案完成设计评审；
6. DAG 状态机形成契约测试。

**结论**：本稿只冻结设计输入，不构成 Phase 6 完整开工声明。

## 9. Blocking 端点最小闭环（2026-09-21）

| 端点 | 当前能力 | 诚实边界 |
|---|---|---|
| `GET /agents/online` | 从 collab-bus 真实心跳聚合成员 | 模型 / 延迟未接入注册表时返回 `- / 0`，并标注数据质量 |
| `GET /tasks`、`POST /tasks` | 协作域映射任务；创建任务会真实创建协作域 | 不声称已具备 planner / expert routing |
| `GET /tasks/{task_id}` | 协作域详情、成员、DAG 映射 | 调度状态仍未实现 |
| `GET /results/{task_id}` | 端点存在并校验任务 | `artifact_source_connected=false`，无工件时返回空列表 |
| `GET /experts`、`GET /approvals` | 固定响应契约 | 注册表未接入时 `available=false` |
| `POST /approvals/{approval_id}/decision` | fail-closed 写路径 | 注册表未接入时 503，`side_effects=false` |

真实验收脚本：`scripts/phase6-blocking-check.py`；报告：`docs/test-reports/collab/2026-09-21/phase6-blocking-minimal.json`。
