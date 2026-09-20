# OpenRouter 非降级模型验证报告

> **日期**：2026-09-20  
> **脚本**：`scripts/openrouter-e2e.py`  
> **参数**：`base_url=https://openrouter.ai/api/v1` · `model=openrouter/free`  
> **密钥**：仅通过 `OPENROUTER_API_KEY` 环境变量传入，未写入仓库或报告

## 1. 结论

`OpenRouter` 的 OpenAI 兼容接口能够被现有 `HttpLlmEngine → LlmGateway` 路径真实调用：

- `HttpLlmEngine.available() == true`
- `LlmGateway` 返回 `degraded=false`
- `generator=model`
- 返回内容非空（在成功路由的上游模型上）

因此，**接口连接与网关非降级路径验证通过**。

但 `openrouter/free` 是路由别名，不保证固定到同一上游模型。两次实测分别落到不同免费模型，其中一次返回预期文本，另一次返回字面量 `None`。这说明：

- **连通性和非降级状态可用**；
- **输出质量不可用 `openrouter/free` 做稳定评测**；
- 生产或评测场景应固定具体上游模型 ID。

## 2. 实测记录

### Run 1：成功返回预期文本

| 路径 | 上游模型 | 结果 | 延迟 |
|---|---|---|---:|
| 直连 `/chat/completions` | `nex-agi/nex-n2.5-mini:free` | `OPENROUTER_OK` | 2146ms |
| `HttpLlmEngine → LlmGateway` | `openrouter/free` | `OPENROUTER_OK` | 11640ms |

网关结果：`degraded=false`，`generator=model`。

### Run 2：非降级成功，但内容未达预期

| 路径 | 上游模型 | 结果 | 延迟 |
|---|---|---|---:|
| 直连 `/chat/completions` | `liquid/lfm-2.5-2.6b:free` | 字面量 `None` | 1749ms |
| `HttpLlmEngine → LlmGateway` | `openrouter/free` | 字面量 `None` | 2296ms |

网关结果仍为：`degraded=false`，`generator=model`。  
因此该次结果应标记为 **pass_with_warning**，不能把“非降级”误读为“输出质量稳定通过”。

## 3. 优化建议

1. 将 `openrouter` 加入模型供应商字典与默认 Base URL，改善前台配置体验。
2. 保留 `openrouter/free` 作为连通性 smoke 测试，不用于质量评测。
3. 正式评测时固定具体免费模型 ID，例如实测中有效的上游模型；同时记录模型版本和路由结果。
4. 为 `HttpLlmEngine` 的 `extra` 配置增加可选请求头支持：
   - `HTTP-Referer`
   - `X-Title`
5. 对模型输出增加结构化校验，不仅检查 HTTP 200，还要拒绝空串、`None`、`null` 和明显模板占位。
6. 外部模型测试通过环境变量/CI Secret 注入 Key；本轮报告不包含 Key 明文。
7. 建议轮换本轮对话中暴露过的 Key，避免继续使用已出现在文本记录中的凭据。

## 4. 复现命令

```bash
OPENROUTER_E2E=1 \
OPENROUTER_API_KEY=... \
python scripts/openrouter-e2e.py \
  --base-url https://openrouter.ai/api/v1 \
  --model openrouter/free \
  --output docs/test-reports/model/2026-09-20/openrouter-non-degraded.json
```

## 5. 测试边界

- 本测试验证 **chat completion 生成路径**，不覆盖 `embed` / `rerank`。
- `embed` 与 `rerank` 仍需本地 BGE-M3 / reranker，不能使用 `openrouter/free` 代替。
- 外部模型受免费额度、限流、上游路由和网络影响；该测试应保持 opt-in，不作为默认 CI 硬门禁。