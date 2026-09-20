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

- `LifeformTargetDown`
- `LifeformHighHttpP99`
- `LifeformHighJvmMemory`

注意：

1. Compose 通过 `host.docker.internal` 抓取本机服务；Linux 已配置 `host-gateway`。
2. `alertmanager.yml` 的 `default` receiver 仅用于本地验证，不会发送外部通知。
3. 生产环境必须替换 receiver，并接入组织现有告警通道。