# MC-01 多域规模验证报告

> **日期**：2026-09-21
> **环境**：本机 PostgreSQL + NATS JetStream + collab-bus 单实例
> **脚本**：`scripts/collab-scale-check.py`

## 1. 结论

25、100、500、1000 协作域全部创建成功，active durable consumer 增量与目标域数一致，规模 smoke 状态均为 `pass`。

## 2. 实测结果

| 域数 | 并发 | 创建总耗时 | 创建 P50 | 创建 P95 | 列表延迟 | Consumer 增量 | 结果 |
|---:|---:|---:|---:|---:|---:|---:|---|
| 25 | 8 | 168.62ms | 42.75ms | 70.80ms | 77.65ms | 25 | pass |
| 100 | 16 | 700.31ms | 103.34ms | 173.88ms | 122.06ms | 100 | pass |
| 500 | 24 | 3064.46ms | 141.80ms | 254.34ms | 669.10ms | 500 | pass |
| 1000 | 32 | 6698.10ms | 209.36ms | 384.22ms | 1221.34ms | 1000 | pass |

原始结果：

- `scale-25.json`
- `scale-100.json`
- `scale-500.json`
- `scale-1000.json`

## 3. 观察

- 创建和 consumer 激活随域数近似线性增长；
- 1000 域时列表延迟约 1.2s，需在 Phase 6 前继续评估分页或索引优化；
- 单实例虚拟线程与 NATS consumer 规模在 1000 域下仍可运行；
- 本次未验证多实例共享 consumer 行为，该部分仍保留为后续项。

## 4. 复现

```bash
python scripts/collab-scale-check.py --domains 1000 --concurrency 32 --cleanup \
  --base-url http://127.0.0.1:8085 \
  --output docs/test-reports/collab/2026-09-21/scale-1000.json
```
