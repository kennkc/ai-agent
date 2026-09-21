# Prometheus / Alertmanager 本地与生产基线

启动：

```bash
docker compose up -d prometheus alertmanager
```

访问：

- Prometheus: http://127.0.0.1:9090
- Alertmanager: http://127.0.0.1:19093

抓取目标：

- Java 服务：`/actuator/prometheus`
- nlp-service：`/metrics`
- wp-bff：`/metrics`

告警规则：

- 可用性：`LifeformTargetDown`、`LifeformHighHttpP99`、`LifeformHighJvmMemory`
- Collab：DLQ、重试和心跳 backlog
- LLM：失败率和模板降级
- Tool：失败率和熔断打开

本地告警投递链：

```text
Prometheus -> Alertmanager -> wp-bff /api/wp/alerts/alertmanager -> memory + JSONL
```

端到端验证：

```powershell
& services/python/venv/Scripts/python.exe -B scripts/alertmanager-delivery-check.py `
  --output docs/test-reports/collab/2026-09-21/alertmanager-delivery.json
```

报告必须显示本次唯一 `run_id` 的 `delivered=true`，历史告警不会造成假通过。

注意：

1. Compose 通过 `host.docker.internal` 抓取本机服务；Linux 已配置 `host-gateway`。
2. `alertmanager.yml` 已把本地告警真实投递到 wp-bff 并落盘；它仍是**本地验证通道**，不是企业微信 / 邮件 / Slack。
3. 生产复制 `alertmanager.production.yml.example`，替换真实 webhook URL，并通过 secret 文件注入 Bearer 凭据。禁止把通知渠道凭据提交到仓库。