# Nginx 生产反向代理模板

用途：承载 Vue 构建产物，并把 `/api/**` 统一转发到 `gateway-service`。

## 路由边界

| 对外路径 | 上游 | 说明 |
|---|---|---|
| `/` | Nginx 静态文件 | `web/work-platform/dist` |
| `/api/wp/**` | gateway-service -> 按 `WP_BFF_URI` 转发 wp-bff | 工作平台 BFF |
| `/api/**` | gateway-service | 会话、感官、大脑、躯体、工具等统一入口 |

## 部署步骤

1. 构建前端：

   ```bash
   cd web/work-platform
   npm ci
   npm run build
   ```

2. 把 `dist/` 内容复制到 Nginx 容器的 `/usr/share/nginx/html`。
3. 把 `lifeform.conf` 放入 `/etc/nginx/conf.d/`。
4. 启动 gateway-service，并设置：

   ```bash
   export WP_BFF_URI=http://127.0.0.1:8090
   ```

   若服务运行在不同的容器或主机，把 `127.0.0.1:8090` 替换为可达地址。
5. 校验并重载：

   ```bash
   nginx -t
   nginx -s reload
   ```

## 验证

```bash
curl -fsS http://127.0.0.1/healthz
curl -fsS http://127.0.0.1/api/wp/healthz
```

第二个请求必须经过 gateway-service 到达 wp-bff，而不是由 Vite 开发代理处理。

## 生产边界

- 本模板不包含 TLS；生产应在入口层配置证书或上游负载均衡。
- `VITE_API_BASE` 保持 `/api/wp`，不要在构建时写死内网地址。
- gateway-service 当前使用静态 `WP_BFF_URI` 路由 wp-bff；多实例部署时应改为服务发现或负载均衡地址。