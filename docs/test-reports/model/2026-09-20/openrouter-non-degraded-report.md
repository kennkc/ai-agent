# OpenRouter 非降级模型验证报告

> **日期**：2026-09-20
> **脚本**：`scripts/openrouter-e2e.py`
> **参数**：`base_url=https://openrouter.ai/api/v1` · `model=nex-agi/nex-n2.5-mini:free`
> **密钥**：仅通过 `OPENROUTER_API_KEY` 环境变量传入，未写入仓库或报告

## 1. 结论

固定上游模型 **`nex-agi/nex-n2.5-mini:free`** 的直连和现有网关链路均通过：

- 直连 `/chat/completions` 返回 `OPENROUTER_OK`，延迟 **2843.44 ms**
- `HttpLlmEngine → LlmGateway` 返回 `OPENROUTER_OK`，延迟 **1346 ms**
- `HttpLlmEngine.available() == true`
- `LlmGateway` 返回 `degraded=false`
- `generator=model`
- 输出非空，且包含预期校验标记

因此，**OpenRouter 接口接入、真实模型调用与非降级网关路径验证通过（status=pass）**。

`openrouter/free` 只能作为路由别名做连通性 smoke test：历史实测两次落到了不同上游，其中一次返回预期文本，另一次返回字面量 `None`。该别名 **不能用于稳定质量评测**，评测和生产配置应固定具体上游模型 ID。

## 2. 固定模型实测记录

| 路径 | 上游模型 | 结果 | 延迟 | 降级状态 | 生成器 |
|---|---|---|---:|---|---|
| 直连 `/chat/completions` | `nex-agi/nex-n2.5-mini:free` | `OPENROUTER_OK` | 2843.44 ms | — | 上游模型 |
| `HttpLlmEngine → LlmGateway` | `nex-agi/nex-n2.5-mini:free` | `OPENROUTER_OK` | 1346 ms | `false` | `model` |

直连用量：`prompt_tokens=35`、`completion_tokens=8`、`total_tokens=43`、`cost=0`。
网关侧同样完成真实模型调用，未触发任何降级或规则/模板回退。

## 3. OpenRouter 路由别名风险

| 场景 | 结果 | 风险 |
|---|---|---|
| `openrouter/free` Run 1 | 路由到 `nex-agi/nex-n2.5-mini:free`，返回预期文本 | 不可复现上游 |
| `openrouter/free` Run 2 | 路由到 `liquid/lfm-2.5-2.6b:free`，返回字面量 `None` | 非降级但质量失败 |

风险结论：`degraded=false` 只能证明请求走到了真实模型，**不能单独证明输出质量符合预期**；质量门禁还必须校验内容结构和语义。

## 4. 优化建议

1. 正式评测和生产配置固定具体上游模型 ID，并记录模型版本与实际路由结果。
2. 保留 `openrouter/free` 仅用于连通性 smoke test，不用于质量、成本或响应格式评测。
3. 为模型输出增加结构化校验，拒绝空串、`None`、`null` 和模板占位，即使 HTTP 状态为 200、`degraded=false` 也判失败。
4. 为 `HttpLlmEngine` 的 `extra` 配置增加可选请求头支持：`HTTP-Referer`、`X-Title`。
5. 外部模型测试通过环境变量/CI Secret 注入 Key；本轮报告不包含 Key 明文。
6. 本轮对话中曾暴露过测试 Key，建议立即轮换，后续仅在本地环境变量或密钥管理服务中使用。
7. `embed` / `rerank` 不纳入本次 OpenRouter 生成测试：这两类能力仍使用本地 BGE-M3 / reranker，不能由 chat completion 模型替代。

## 5. 复现命令

```bash
OPENROUTER_E2E=1 \
OPENROUTER_API_KEY=... \
python scripts/openrouter-e2e.py \
  --base-url https://openrouter.ai/api/v1 \
  --model nex-agi/nex-n2.5-mini:free \
  --output docs/test-reports/model/2026-09-20/openrouter-fixed-model.json
```

## 6. 测试边界

- 本测试验证 **chat completion 生成路径**，不覆盖 `embed` / `rerank`。
- 外部模型受免费额度、限流、上游可用性和网络影响；该测试保持 opt-in，不作为默认 CI 硬门禁。
- 结果对应 2026-09-20 的免费模型可用状态；后续仍需在 CI 夜检或发布前复测。
