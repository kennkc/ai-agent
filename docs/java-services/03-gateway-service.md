# 03 · gateway-service 网关服务

> 模块路径：`services/java/gateway-service/`
> 源文件：**6 个主代码 + 1 个测试**
> HTTP 端口：**8080**（统一入口）· gRPC 端口：**9091**
> 技术栈：Spring Cloud Gateway（WebFlux 响应式）+ JJWT

## 1. 模块职责

网关是整个生命体的**统一脊柱入口**，承担四件事：

1. **路由**：把 `/api/{session,sense,nlp,body}/**` 分发到对应后端服务（基于 Nacos 服务发现的负载均衡）。
2. **鉴权**：校验 `Authorization: Bearer <JWT>`，失败直接短路返回，不透传到业务服务。
3. **租户注入**：从 JWT 解出 `tenant_id`，**覆写**为请求头 `X-Tenant-Id` 后再转发。
   这是多租户隔离的根基 —— 业务服务**不得信任**请求参数里客户端自带的租户 ID。
4. **健康探针**：暴露 gRPC Health 服务，供服务网格/运维探活。

## 2. 关键设计约束（改代码前必读）

### 2.1 租户 ID 的可信边界

`JwtAuthFilter` 用 `mutate().header(TENANT_HEADER, tenantId)` 写入租户头。
若客户端在请求里伪造 `X-Tenant-Id`，**会被此处覆写覆盖**，因此下游可以放心取用。

对下游的约束：业务服务只应从 `X-Tenant-Id` 请求头读取租户，不读查询参数或请求体里的租户字段。

### 2.2 开发令牌端点是「默认关闭 + 仅环回地址」

`AuthController` 受两个条件共同保护：

| 保护 | 实现 |
|---|---|
| 功能开关 | `@ConditionalOnProperty(prefix = "app.auth", name = "dev-token-endpoint-enabled", havingValue = "true")` —— 默认 `false`，Bean 根本不注册 |
| 来源限制 | 运行时校验 `remoteAddress` 必须是环回地址（`InetAddress.isLoopbackAddress()`），否则 403 |

**生产环境务必保持 `DEV_TOKEN_ENDPOINT_ENABLED=false`**（默认值已满足）。若开启且网关监听在公网，等同于开放任意租户的令牌签发。

### 2.3 JWT 密钥强度是启动期硬校验

`JwtService` 构造函数会在密钥缺失或不足 32 字节时**直接抛异常终止启动**，而不是回退到默认密钥。
这是有意为之：宁可启动失败，也不接受「用弱密钥悄悄跑起来」。

## 3. 文件清单

| 文件 | 类型 | 行数 | 职责 |
|---|---|---:|---|
| `GatewayApplication.java` | 启动类 | 14 | Spring Boot 入口 + 服务发现 + 配置绑定 |
| `grpc/GrpcHealthServer.java` | 组件 | 32 | gRPC Health 探针服务 |
| `security/AuthProperties.java` | 配置类 | 19 | 绑定 `app.auth.*` 配置 |
| `security/JwtService.java` | 服务 | 41 | JWT 签发与校验 |
| `security/JwtAuthFilter.java` | 全局过滤器 | 58 | 鉴权 + 租户注入 |
| `security/AuthController.java` | 控制器 | 36 | 开发令牌端点（默认关闭） |
| `test/…/JwtServiceTest.java` | 测试 | 24 | 密钥强度与租户往返 |

## 4. 逐文件说明

### 4.1 `GatewayApplication.java` · 启动类 · 14 行

```java
@SpringBootApplication
@EnableDiscoveryClient
@EnableConfigurationProperties(AuthProperties.class)
```

- **职责**：Spring Boot 应用入口。
- **@EnableDiscoveryClient**：启用 Nacos 服务发现，使 `lb://sense-service` 这类 URI 能解析为实际实例。
- **@EnableConfigurationProperties(AuthProperties.class)**：显式注册配置类（注意本模块**没有**用
  `@ConfigurationPropertiesScan`，与 `sense-service` 的做法不同 —— 新增配置类时需在此显式登记，或用 `@Component` 标注）。

### 4.2 `grpc/GrpcHealthServer.java` · 组件 · 32 行

- **职责**：以 `SmartLifecycle` 方式在 Spring 容器启动/关闭时管理一个 gRPC 服务，仅提供标准 Health 服务。
- **关键行为**：读取 `${grpc.port:0}`；**端口 ≤ 0 时直接跳过启动**（便于本地不想开 gRPC 时置 0）。
- **实现要点**：用 `HealthStatusManager` 注册空服务名 `""` 的状态为 `SERVING`（gRPC 健康检查协议约定：
  空字符串代表整个服务器）。启动失败抛 `IllegalStateException`，使容器启动失败而非静默降级。
- **为何手写而非用框架**：Spring Boot 官方没有内置的 gRPC server 自动配置，手写 30 行比引入额外依赖更轻。
- **协作**：`gateway-service` / `session-manager` / `sense-service` / `body-service` 四个模块各有一个
  **内容几乎相同**的 `GrpcHealthServer`（端口不同）。这是刻意的取舍 —— 保持模块间零共享代码依赖，
  避免为了复用 30 行工具类而在 `proto-contracts` 里引入 Spring 依赖。

### 4.3 `security/AuthProperties.java` · 配置类 · 19 行

- **职责**：绑定 `app.auth.*` 配置项。
- **成员**：

| 属性 | 配置键 | 默认值 | 说明 |
|---|---|---|---|
| `secret` | `app.auth.secret` | 无（必填） | HS256 密钥，**至少 32 字节** |
| `tokenTtl` | `app.auth.token-ttl` | `PT24H` | 令牌有效期，`Duration` 类型 |
| `devTokenEndpointEnabled` | `app.auth.dev-token-endpoint-enabled` | `false` | 是否注册开发令牌端点 |

- **协作**：由 `GatewayApplication` 的 `@EnableConfigurationProperties` 注册；被 `JwtService`（读密钥与 TTL）
  和 `AuthController` 的 `@ConditionalOnProperty`（读开关）消费。

### 4.4 `security/JwtService.java` · 服务 · 41 行

- **职责**：JWT（HS256）的签发与解析，唯一持有签名密钥的地方。
- **核心方法**：

| 方法 | 说明 |
|---|---|
| `String sign(String tenantId)` | 签发令牌：`subject=lifeform-agent`，自定义声明 `tenant_id`，含 `iat` / `exp` |
| `Claims parse(String token)` | 校验签名与有效期后返回声明；失败抛 JJWT 异常由过滤器统一处理 |

- **启动期安全带（重要）**：

| 校验 | 行为 |
|---|---|
| `secret` 为 null 或空白 | 抛 `IllegalStateException("JWT_SECRET must be configured. Refusing insecure default key.")` |
| 密钥字节数 < 32 | 抛 `IllegalStateException("JWT_SECRET must be at least 32 bytes for HS256.")` |

- **为什么用 `tenant_id` 自定义声明而不是只放 subject**：租户是**授权维度**，必须与身份主体解耦 ——
  同一个主体将来可能切换租户（管理后台场景），把租户放独立声明比塞进 subject 字符串更清晰。
- **协作**：`JwtAuthFilter` 调用 `parse`；`AuthController` 调用 `sign`。

### 4.5 `security/JwtAuthFilter.java` · 全局过滤器 · 58 行

- **职责**：网关的鉴权与租户注入关卡。实现 `GlobalFilter, Ordered`，`getOrder()` 返回 **-100**（尽早执行）。
- **处理流程**：

```text
取请求路径 path
├─ path 以 /actuator 或 /api/auth 开头 → 直接放行（健康检查与令牌签发本身无需鉴权）
└─ 否则
   ├─ 无 Authorization 或前缀不是 "Bearer " → 401 AGENT_UNAUTHORIZED
   ├─ jwtService.parse(token)
   │   ├─ 抛异常（签名错/过期/格式错） → 401 AGENT_UNAUTHORIZED
   │   ├─ 声明中无 tenant_id 或为空白 → 403 AGENT_FORBIDDEN
   │   └─ 成功 → mutate 请求头，写入 X-Tenant-Id → 放行
```

- **`reject()` 的设计**：不抛异常，而是直接构造 JSON 响应体
  （`{"code":…,"message":…,"details":{}}`）短路返回。这样错误响应格式与业务服务的
  `GlobalExceptionHandler` 输出保持一致，客户端只需一套解析逻辑。
- **注意**：`/api/auth` 整体放行意味着**将来若在该前缀下新增需要鉴权的接口，会被这里一并放行** ——
  新增端点前请确认它属于「认证类」而非「业务类」。
- **协作**：上游是客户端请求，下游是所有被路由的业务服务。

### 4.6 `security/AuthController.java` · 控制器 · 36 行

- **职责**：开发/联调用的令牌签发端点 `POST /api/auth/token?tenantId=xxx`。
- **双重保护**（见 §2.2）：类级 `@ConditionalOnProperty` 控制 Bean 是否注册；方法体内校验来源必须是环回地址。
- **返回**：`{"token": "...", "tenant_id": "...", "token_type": "Bearer"}`。
- **实现细节**：`remoteAddress` 为 null 时也拒绝（无法确证来源即不放行）；
  地址解析异常同样归为 403，避免把内部异常细节暴露给调用方。
- **典型用法**（本机联调）：

  ```bash
  # 需先设置 DEV_TOKEN_ENDPOINT_ENABLED=true 并重启网关
  curl -X POST "http://127.0.0.1:8080/api/auth/token?tenantId=demo"
  ```

### 4.7 `test/…/JwtServiceTest.java` · 测试 · 24 行 · 2 个用例

| 用例 | 验证内容 |
|---|---|
| `signsAndParsesTenant` | 用 32 字节密钥签发后解析，`tenant_id` 声明能正确往返 |
| `rejectsWeakSecret` | 弱密钥（`short-secret`）触发 `IllegalStateException`，锁住 §2.3 的安全带 |

**这个测试的价值在于第二条** —— 它是对「禁止弱密钥」这一安全约定的回归守卫，防止有人为了「方便本地开发」
把启动期校验删掉。

## 5. 路由配置

路由定义在 `src/main/resources/application.yml`：

| 路由 id | 匹配路径 | 目标 |
|---|---|---|
| `session-manager` | `/api/session/**` | `lb://session-manager` |
| `sense-service` | `/api/sense/**` | `lb://sense-service` |
| `nlp-service` | `/api/nlp/**` | `lb://nlp-service`（Python 服务，也注册到 Nacos） |
| `body-service` | `/api/body/**` | `lb://body-service` |

同时开启了 `discovery.locator`（`enabled: true`、`lower-case-service-id: true`），
意味着**未显式配置的服务也会按 `/{service-id}/**` 自动生成路由** —— 这是服务发现定位器的默认行为。
新增服务时优先补显式路由，以便控制路径前缀。

**已知缺口**：中间件观测的 `wp-bff`（Node，端口 8090）**尚无网关路由**。
当前前端在开发期依赖 Vite 代理 `/api/wp → 127.0.0.1:8090`；生产路径需要在网关补一条
`/api/wp/**` 路由。详见 [07-运行时配置与横切约定](07-运行时配置与横切约定.md)。

## 6. 修改指引

| 需求 | 改动位置 |
|---|---|
| 新增一条路由 | `application.yml` 的 `spring.cloud.gateway.routes` |
| 放行某类无需鉴权的路径 | `JwtAuthFilter` 中的路径判断（**注意安全影响**） |
| 调整令牌有效期 | `application.yml` 的 `app.auth.token-ttl` |
| 新增鉴权相关配置项 | 加字段到 `AuthProperties`（已在启动类登记，无需额外操作） |
| 实现真正的 gRPC 转发 | 当前仅暴露 Health；服务间 gRPC 调用尚未启用，契约已备于 `proto-contracts` |
