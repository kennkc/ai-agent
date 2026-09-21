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

注意：

1. Compose 通过 `host.docker.internal` 抓取本机服务；Linux 已配置 `host-gateway`。
2. `alertmanager.yml` 的 `default` receiver 仅用于本地验证，不会发送外部通知。生产复制 `alertmanager.production.yml.example`，替换真实 webhook URL 后挂载。
3. 生产环境复制 `alertmanager.production.yml.example`，替换真实 webhook URL，并接入组织现有告警通道。