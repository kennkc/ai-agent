# infra · 基础设施层

Agent-Lifeform 基础设施配置目录。

## 说明

基础设施（Redis / PostgreSQL / Qdrant / NATS / Nacos / MinIO）通过根目录 `docker-compose.yml` 统一编排启动，本目录存放基础设施相关的补充配置与说明。

| 组件 | 容器名 | 端口 | 用途 | 启用阶段 |
|------|--------|:---:|------|:---:|
| Redis | lifeform-redis | 6379 | 会话缓存 / 热存储 | P0 |
| PostgreSQL (pgvector 镜像) | lifeform-postgres | 5432 | 冷存储 / 关系数据（当前仅关系表，未启用 pgvector 列） | P0 |
| Qdrant | lifeform-qdrant | 6333/6334 | 向量检索（温存储） | P0（P3 启用） |
| NATS | lifeform-nats | 4222/8222 | 消息总线（JetStream） | P0（P1 启用） |
| Nacos | lifeform-nacos | 8848/9848 | 服务注册 / 配置中心 | P0 |
| MinIO | lifeform-minio | 9000/9001 | 对象存储 | P0（P3 启用） |

## 常用命令

```bash
# 启动全部基础设施
docker compose up -d

# 查看状态
docker compose ps

# 停止（保留数据卷）
docker compose down

# 停止并删除数据卷（谨慎）
docker compose down -v
```

## 健康检查

```bash
./scripts/healthcheck.sh   # 期望 7/7 全绿
```

## 国内镜像源

若镜像拉取失败（如 403），检查 `~/.docker/daemon.json` 的 `registry-mirrors`，保留可用源后重启 Docker Desktop：

```json
{
  "registry-mirrors": [
    "https://docker.1ms.run",
    "https://docker.m.daocloud.io",
    "https://hub-mirror.c.163.com"
  ]
}
```
## 生产反向代理

`infra/nginx/lifeform.conf` 提供生产入口模板：

- Nginx 承载 `web/work-platform/dist` 静态资源；
- `/api/**` 统一转发到 `gateway-service`；
- `/api/wp/**` 由 gateway-service 通过 `WP_BFF_URI` 转发到 wp-bff；
- WebSocket Upgrade 头已保留，供后续协作/消息流使用。

详见 `infra/nginx/README.md`。

## Prometheus / Alertmanager

- 配置：`infra/prometheus/prometheus.yml`、`alerts.yml`、`alertmanager.yml`。
- 说明：`infra/prometheus/README.md`。
- 启动：`docker compose up -d prometheus alertmanager`。
