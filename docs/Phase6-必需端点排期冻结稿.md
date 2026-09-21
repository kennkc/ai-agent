# Phase 6 必需 planned 端点排期冻结稿

> **日期**：2026-09-21
> **状态**：已冻结，**不构成 Phase 6 开工声明**
> **机器可读清单**：`contracts/phase6-endpoint-priority.yaml`

## 1. 分层原则

- **Blocking**：没有这些端点，Phase 6 的编排结果无法被工作平台看见、审批或验收。
- **Enabling**：不阻塞首个 Phase 6 闭环，但缺失会显著降低可用性。
- **Deferred**：可继续以 Mock/降级展示，等待 Phase 6 后或产品需求明确。

## 2. Blocking

| 路径 | 目的 |
|---|---|
| `/agents/online` | 在线 Agent、角色、心跳与当前任务 |
| `/tasks` | 任务列表与编排入口 |
| `/tasks/{task_id}` | 任务 DAG/节点/状态详情 |
| `/results/{task_id}` | 任务结果与工件汇总 |
| `/experts` | 专家团队选择与角色能力 |
| `/approvals` | 审批门待办 |
| `/approvals/{approval_id}/decision` | 审批决策 |

## 3. Enabling

- `/skills`
- `/automations`
- `/automations/{automation_id}`
- `/cases`
- `/cases/{case_id}/reuse`
- `/search`

## 4. Deferred

- `/vitals`
- `/organs`
- `/senses`
- `/evolution`
- `/connectors/*`
- `/remote-im/*`
- `/preferences`

## 5. Phase 6 解冻前置条件

1. MC-01 25/100/500/1000 域规模验证完成；
2. Prometheus 业务指标和告警接收完成；
3. 真实 BFF/API E2E 基线通过；
4. 工件层与验收门存储方案评审通过；
5. Blocking 端点完成独立排期和契约测试。

当前 25/100/500/1000 域本地规模验证已通过；其余条件继续作为解冻门槛。
