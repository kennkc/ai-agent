# 04 · session-manager 会话服务

> 模块路径：`services/java/session-manager/`
> 源文件：**13 个主代码 + 4 个测试**（主代码 616 行）
> HTTP 端口：**8081** · gRPC 端口：**9092**
> 主要依赖：Spring Web、Spring Data Redis、NATS（jnats）、Kafka clients

## 1. 模块职责

`session-manager` 是生命体的**对话记忆与编排中枢**，承担三件事：

1. **会话生命周期管理**：创建 / 查询 / 关闭会话，状态存 Redis（Hash + List 结构，TTL 2 小时）。
2. **VS1 问答编排**：`ask` 请求串起「意图识别 → 知识检索 → 模板回答 → 消息落库」的完整链路。
3. **脊柱总线接入**：通过 NATS 做同步请求-应答（与感官层握手）与事件发布；通过 Kafka 发布持久化领域事件。

它在链路中的位置：**网关之后、`nlp-service` 与 `body-service` 之前**，是唯一掌握完整问答流程的模块。

## 2. 关键设计约束（改代码前必读）

### 2.1 会话归属校验是每次访问的强制关卡

所有按 ID 访问的操作（`get` / `close` / `ask`）都先经过 `requireOwnedSession(sessionId, tenantId)`：

| 情况 | 结果 |
|---|---|
| Redis 中无该会话 | `AGENT_NOT_FOUND`（404） |
| 会话存在但 `tenant_id` 与请求头不一致 | `AGENT_FORBIDDEN`（403） |

**这是跨租户越权读的唯一防线**。新增任何按会话 ID 的接口，都必须调用这个方法 ——
包括只读接口，因为会话内容本身属于租户资产。

注意这里比对的是**请求头 `X-Tenant-Id`**（由网关注入，见 [03-gateway-service](03-gateway-service.md)），
不是请求体或查询参数。

### 2.2 上游故障默认降级，而不是整链路失败

`NlpClient` / `BodyClient` 各自受一个开关控制：

| 配置 | 默认 | 降级时行为 | 关闭降级时行为 |
|---|---|---|---|
| `app.nlp.degrade-on-failure` | `true` | 意图 → `{"intent":"闲聊","confidence":0.5,"engine":"FALLBACK"}` | 抛 `AGENT_UPSTREAM_UNAVAILABLE`（502），**保留 cause** |
| `app.body.degrade-on-failure` | `true` | 检索 → 空列表 | 同上 |

设计取舍：**默认可用性优先**。`nlp-service`（Python）或 `body-service` 挂掉时，用户仍能拿到模板回答，
而不是看到 502。需要严格语义（例如做数据一致性校验的环境）时把开关关掉。

降级时返回的 `intent` 与 `nlp-service` 自身的 `FALLBACK` 引擎语义保持一致，避免两侧出现「同一个故障、两种意图值」。

### 2.3 跨服务 HTTP 必须走 `OutboundHttp`（缺陷 D-1 的修复成果）

**不要直接使用 `RestClient.builder().build()`**，一律经 `OutboundHttp.restClient(baseUrl)` 构建。

原因（这是本项目一个已修复的真实线上级缺陷，务必理解）：

```text
RestClient 在无 Apache HttpClient 依赖时回退到 JdkClientHttpRequestFactory，
底层 JDK HttpClient 默认协商 HTTP/2。对明文（http://）连接，JDK 会先发送
    Upgrade: h2c
    HTTP2-Settings: ...
升级握手。uvicorn 的 h11 实现不兼容该升级，把请求判为非法并【丢弃请求体】，
于是 FastAPI 报 422 "body Field required"，Java 侧只看到"上游不可用"。
```

`OutboundHttp` 显式锁定 **HTTP/1.1**，并统一补齐连接超时（3s）与读超时（10s）——
后者避免 Python 侧卡住时把会话请求一起拖死。

该约束由 `OutboundHttpTest` 用裸 `ServerSocket` 抓原始报文做回归守卫（断言请求行是 `HTTP/1.1`
且不含 `h2c` / `http2-settings` 头）—— 因为端口级健康检查**无法发现**这个缺陷，健康检查是通的，只有请求体会丢。

## 3. 文件清单

| 文件 | 类型 | 行数 | 职责 |
|---|---|---:|---|
| `SessionManagerApplication.java` | 启动类 | 19 | Spring Boot 入口 + 服务发现 |
| `controller/SessionController.java` | 控制器 | 143 | 会话 CRUD + `ask` 问答编排 |
| `bus/BusProxy.java` | 组件 | 34 | 总线调用的**容错包装**（异常吞掉返回 null） |
| `nats/NatsClient.java` | 组件 | 75 | NATS 连接、同步请求-应答、异步发布 |
| `kafka/KafkaEventPublisher.java` | 组件 | 55 | Kafka 事件发布（持久化事件通道） |
| `kafka/KafkaEventConsumer.java` | 组件 | 66 | Kafka 事件消费（审计/回放用） |
| `orchestration/NlpClient.java` | 客户端 | 56 | 调 `nlp-service` 做意图识别（含降级） |
| `orchestration/BodyClient.java` | 客户端 | 44 | 调 `body-service` 做知识检索（含降级） |
| `orchestration/OutboundHttp.java` | 工具类 | 46 | **跨服务 HTTP 统一出口**（HTTP/1.1 + 超时） |
| `common/ErrorCode.java` | 枚举 | 30 | 统一错误码定义 |
| `common/BizException.java` | 异常 | 41 | 携带错误码 + 明细的业务异常 |
| `common/GlobalExceptionHandler.java` | 切面 | 54 | 错误码 → HTTP 状态码映射与统一响应体 |
| `grpc/GrpcHealthServer.java` | 组件 | 32 | gRPC Health 探针 |
| `test/…/BusProxyTest.java` | 测试 | 21 | 总线代理序列化与委托 |
| `test/…/SessionControllerTest.java` | 测试 | 76 | 租户校验、跨租户拒绝、ask 链路 |
| `test/…/OutboundHttpTest.java` | 测试 | 101 | **缺陷 D-1 回归守卫** |
| `test/…/UpstreamDegradeTest.java` | 测试 | 56 | 上游降级与不降级两种语义 |

## 4. 逐文件说明

### 4.1 `SessionManagerApplication.java` · 启动类 · 19 行

- **职责**：Spring Boot 入口，`@EnableDiscoveryClient` 注册到 Nacos。
- **注意**：类注释中「Phase 4: 会话状态机 FSM」尚未实现 —— 当前会话状态只有 `ACTIVE` / `CLOSED` 两个值，
  由请求路径决定，没有独立的状态机校验。这是 Phase 4 的待办，不是现状。
- 本模块**没有** `@ConfigurationProperties` 类，配置全部通过 `@Value` 直接注入（见 §5）。

### 4.2 `controller/SessionController.java` · 控制器 · 143 行

模块最核心的文件，也是唯一掌握完整问答流程的地方。

**Redis 键结构**：

| 键 | 类型 | 内容 |
|---|---|---|
| `session:{sessionId}` | Hash | `tenant_id`、`status`、`created_at`、`updated_at`、`last_activity_at`、`message_count` |
| `session:{sessionId}:messages` | List | 每条消息的 JSON 字符串（`rightPush` 追加） |

两者 TTL 均为 **2 小时**，每次 `touchSession` 一起续期。

**接口**：

| 方法 | 路径 | 说明 |
|---|---|---|
| `create` | `POST /api/session` | 生成 UUID 会话，写 Hash 与 TTL，探测总线连通性，发 Kafka `session.created` 事件 |
| `get` | `GET /api/session/{sessionId}` | 返回会话 Hash（先过归属校验） |
| `close` | `DELETE /api/session/{sessionId}` | 删除会话 Hash 与消息 List（先过归属校验） |
| `ask` | `POST /api/session/{sessionId}/ask` | 问答主链路 |

**`create` 的总线探测设计**：创建会话时向 `lifeform.rpc.sense.ping` 发一次请求-应答，
把结果作为 `bus_channel`（`nats` / `direct`）与 `bus_reply` 返回。这既是冒烟探针，
也让调用方一眼看出总线是否通。**总线不通不影响会话创建**（`BusProxy` 会吞掉异常返回 null）。

**`ask` 的执行顺序**（不要随意调整）：

```text
1. 校验 question 非空          → 空则 AGENT_BAD_REQUEST
2. requireOwnedSession         → 越权/不存在直接拒绝（在调用上游之前）
3. nlpClient.recognize         → 意图（可能是降级的 FALLBACK）
4. bodyClient.retrieve         → top_k=3 知识片段
5. buildAnswer(chunks)         → 模板拼装（无 LLM 参与）
6. appendMessage × 2           → 分别落 user 与 assistant 两条消息
7. touchSession                → 刷新 updated_at / last_activity_at / message_count 与 TTL
```

要点：

- **先校验再调上游**：把租户校验放在最前，避免为越权请求白跑两次跨服务调用。
- **`buildAnswer` 是模板而非模型生成**：取第一条 chunk 的 `content`（超 240 字截断）拼成
  「根据知识库《标题》检索结果：…」；无命中时返回固定话术「当前知识库未找到足够信息，请补充文档后重试。」
  真正的 LLM 生成是 Phase 4 的 M4 目标。
- **`latency_ms` 只记在 assistant 消息上**，且是「意图识别 + 检索 + 拼装」的总耗时，
  不包含消息落库本身。
- 内部类 `record AskRequest(String question)`：请求体形状。

**已知不足**：`appendMessage` 失败时抛 `AGENT_INTERNAL_ERROR`，但此时上游调用已经发生、
前一条消息可能已写入 —— 目前没有回滚机制。Phase 4 引入状态机时应一并处理。

### 4.3 `bus/BusProxy.java` · 组件 · 34 行

- **职责**：`NatsClient` 之上的**容错包装**，把 `Map` 负载序列化为 JSON 后转发，并把所有异常吞掉。
- **行为差异（有意设计）**：

| 方法 | 失败时 | 原因 |
|---|---|---|
| `request` | 返回 `null` | 调用方据此判断总线是否可用（如 `create` 的 `bus_channel`） |
| `publish` | 静默忽略 | 注释明确：「Phase 1 总线投递是**尽力而为**，Kafka 才是持久事件通道」 |

- **协作**：被 `SessionController.create` 调用；依赖 `NatsClient`。

### 4.4 `nats/NatsClient.java` · 组件 · 75 行

- **职责**：NATS core 协议的 Java 客户端封装（基于 `jnats`），提供同步请求-应答与异步发布。
- **连接管理**：`@PostConstruct init()` 连接 `NATS_URL`（默认 `nats://127.0.0.1:4222`），
  连接名 `session-manager`，`maxReconnects=5`；`@PreDestroy close()` 关闭。
- **降级策略（关键）**：连接失败**只记 WARN 不抛异常** —— 服务照常启动，后续调用被判为不可用。
  这与 `gateway-service` 的 gRPC 健康服务「启动失败即失败」形成对比，因为总线是可选依赖。
- **超时**：`request` 的超时硬编码 **3 秒**；`request` 与 `publish` 失败均返回空值/忽略，不向上抛。
- **协作**：被 `BusProxy` 独占地使用 —— 其他类不应直接用 `NatsClient`。

### 4.5 `kafka/KafkaEventPublisher.java` · 组件 · 55 行

- **职责**：领域事件的持久化发布通道（与 NATS 的「尽力而为」互补）。
- **主题命名约定**：`lifeform.{domain}.{event}`，由 `publish(domain, event, key, payload)` 拼装。
  例如 `publish("session", "created", sessionId, event)` → 主题 `lifeform.session.created`。
- **配置**：`KAFKA_BOOTSTRAP`（默认 `127.0.0.1:9092`），`acks=all`，字符串序列化器。
- **降级**：初始化失败只记 WARN，`producer` 为 null 时 `publish` 直接返回（事件静默丢弃）。
  发送是**异步回调**方式，发送失败只记 WARN。
- **常量提醒**：本模块的 Kafka 配置全部走**环境变量**（`System.getenv`），不是 `application.yml` ——
  需要改 bootstrap 时改环境变量，改 yml 无效。

### 4.6 `kafka/KafkaEventConsumer.java` · 组件 · 66 行

- **职责**：订阅 `lifeform.session.created` 并打印日志，用于**事件链路可观测性**（不是业务消费）。
- **开关**：`KAFKA_CONSUMER_ENABLED`（默认 `true`）。本地不需要时设为 `false` 可少一个后台线程。
- **实现**：`@PostConstruct` 启动一个名为 `kafka-session-event-consumer` 的**守护线程**跑 `pollLoop`；
  消费者组 `session-manager-event-audit`，`auto.offset.reset=earliest`。
- **停止**：`@PreDestroy` 置位 `running=false` + `interrupt()` + `consumer.wakeup()` 三重唤醒，
  保证 `poll(500ms)` 能及时退出。
- **协作**：与 `KafkaEventPublisher` 成对，验证「发得出去、收得回来」。当前只是 `log.info`，
  没有业务处理逻辑 —— 真正的消费者（如感官事件入库）会在 Phase 3 出现。

### 4.7 `orchestration/NlpClient.java` · 客户端 · 56 行

- **职责**：调用 `nlp-service` 的 `POST /api/nlp/intent` 做意图识别。
- **请求**：`{"text": question, "session_id": sessionId, "tenant_id": tenantId}`，
  同时带 `X-Tenant-Id` 请求头（双层传递：body 内与 header 都有，因为 Python 侧两种读法都可能用）。
- **配置**：`app.nlp.base-url`（默认 `http://127.0.0.1:8000`）、`app.nlp.degrade-on-failure`（默认 `true`）。
- **返回兜底**：响应为 null **或**抛 `RestClientException` 时，按降级开关处理（见 §2.2）。
- **`fallback()` 的语义**：`{"intent":"闲聊","confidence":0.5,"engine":"FALLBACK"}` ——
  特意带上 `engine` 字段，让调用方/前端能区分「真实识别」与「降级兜底」，避免把兜底结果当成识别结果展示。

### 4.8 `orchestration/BodyClient.java` · 客户端 · 52 行

- **职责**：调用 `body-service` 的 `POST /api/body/retrieve` 做**语义检索**（Phase 3 起由
  `RetrievalService` 承载：缓存优先 → 向量召回 → 重排）。
- **请求**：`{"query": question, "top_k": 5, "use_cache": true}` + `X-Tenant-Id` 头。
  显式声明 `top_k` 与缓存优先语义（对齐 R3-08 口径）；召回复核与重排由躯体层负责。
- **响应**：原样透传作为 `citations`（引用可回溯）—— 在 Phase 2 的 `content/title/source` 之上
  补充 `chunk_id / doc_id / heading / score / rerank_score / ingest_time_iso`，结构向后兼容。
- **配置**：`app.body.base-url`（默认 `http://127.0.0.1:8083`）、`app.body.degrade-on-failure`（默认 `true`）。
- **降级**：返回 `List.of()`（空结果），使 `buildAnswer` 走「未找到足够信息」话术。

### 4.9 `orchestration/OutboundHttp.java` · 工具类 · 46 行

- **职责**：跨服务 HTTP 调用的**唯一出口**，封装 HTTP/1.1 锁定与超时。
- **成员**：

| 成员 | 值 / 说明 |
|---|---|
| `CONNECT_TIMEOUT` | 3 秒（内网调用足够，过长只会拖慢失败反馈） |
| `READ_TIMEOUT` | 10 秒 |
| `client()` | 返回 `HttpClient`，`Version.HTTP_1_1` + 连接超时 |
| `restClient(baseUrl)` | 返回 `RestClient`，基于 `JdkClientHttpRequestFactory` + 读超时 |

- **类的可见性**：`public final` + 私有构造 —— 纯静态工具类，不允许实例化。
- **强制约定**：见 §2.3。新增跨服务调用（如将来调 `sense-service`）必须经本类。
- **协作**：被 `NlpClient` / `BodyClient` 使用。**`sense-service` 侧有同名的 `HttpClients` 工具类**
  （见 [05-sense-service](05-sense-service.md) §4.24），职责相同但各模块独立实现 —— 同样是为了避免模块间共享代码依赖。

### 4.10 `common/ErrorCode.java` · 枚举 · 30 行

- **职责**：统一错误码（需求 R1-08）。响应体格式：`{"code","message","details"}`。
- **完整取值**：

| 错误码 | 默认消息 |
|---|---|
| `AGENT_BAD_REQUEST` | 请求参数不合法 |
| `AGENT_NOT_FOUND` | 资源不存在 |
| `AGENT_UNAUTHORIZED` | 未认证或 Token 无效 |
| `AGENT_FORBIDDEN` | 无权限访问 |
| `AGENT_CONFLICT` | 资源冲突 |
| `AGENT_DUPLICATE` | 资源已存在 |
| `AGENT_TIMEOUT` | 调用超时 |
| `AGENT_BUS_UNAVAILABLE` | 总线通道不可用 |
| `AGENT_UPSTREAM_UNAVAILABLE` | 上游服务不可用 |
| `AGENT_INTERNAL_ERROR` | 服务内部错误 |

- **注意**：`sense-service` 有**另一份完全相同的 `ErrorCode`**（包路径不同）。两份需要同步维护；
  若将来新增错误码，记得两边都加。

### 4.11 `common/BizException.java` · 异常 · 41 行

- **职责**：携带 `ErrorCode` + `details` 明细的业务异常，`RuntimeException` 子类。
- **四个构造器**，用途不同：

| 构造器 | 用途 |
|---|---|
| `(ErrorCode)` | 用错误码默认消息 |
| `(ErrorCode, String)` | 覆盖消息 |
| `(ErrorCode, String, Map)` | 带字段级明细（如校验失败的具体字段） |
| `(ErrorCode, String, Throwable)` | **保留根因** —— 上游调用失败时使用，避免排障时只剩包装后的错误码 |

- 最后一个是 `UpstreamDegradeTest` 显式断言的行为（`assertNotNull(error.getCause())`）。

### 4.12 `common/GlobalExceptionHandler.java` · 切面 · 54 行

- **职责**：`@RestControllerAdvice`，把异常统一转成 `{"code","message","details"}` 响应体。
- **错误码 → HTTP 状态码映射**（改错误码时需同步这里）：

| ErrorCode | HTTP |
|---|---|
| `AGENT_UNAUTHORIZED` | 401 |
| `AGENT_FORBIDDEN` | 403 |
| `AGENT_NOT_FOUND` | 404 |
| `AGENT_CONFLICT` / `AGENT_DUPLICATE` | 409 |
| `AGENT_TIMEOUT` | 504 |
| `AGENT_BUS_UNAVAILABLE` | 503 |
| `AGENT_UPSTREAM_UNAVAILABLE` | 502 |
| 其他（含 `AGENT_BAD_REQUEST`） | 400（`default` 分支） |

- **其他处理的异常**：`MethodArgumentNotValidException` → 400 且 details 为「字段 → 消息」映射；
  `IllegalArgumentException` → 400（沿用异常自带消息）；兜底 `Exception` → 500 且**只记日志、
  不把内部异常消息返回给客户端**（避免泄露实现细节）。
- **协作**：捕获 `BizException`；被所有控制器共享。

### 4.13 `grpc/GrpcHealthServer.java` · 组件 · 32 行

与 `gateway-service` 的同名类**逐行相同**（仅端口配置不同）。职责与设计取舍见
[03-gateway-service](03-gateway-service.md) §4.2，此处不重复。

### 4.14 测试文件（4 个类 · 10 个用例）

#### `bus/BusProxyTest.java` · 1 个用例

| 用例 | 验证内容 |
|---|---|
| `serializesAndDelegatesRequest` | `BusProxy.request` 把 Map 序列化为 JSON 后委托给 `NatsClient`，并原样返回应答（用 Mockito 匹配 `lifeform.rpc.sense.ping` + 含 `tenant-a` 的负载） |

#### `controller/SessionControllerTest.java` · 3 个用例

| 用例 | 验证内容 | 守卫的约定 |
|---|---|---|
| `createUsesVerifiedTenantHeader` | 创建的会话 Hash 中 `tenant_id` 等于**请求头传入值** | 租户来源正确（不是从请求体取） |
| `rejectsCrossTenantRead` | 会话属 `tenant-a`、以 `tenant-b` 读取 → `BizException(AGENT_FORBIDDEN)` | **跨租户越权读防线**（§2.1） |
| `askReturnsAnswerFromLocalRetrieval` | mock 意图 + 检索后，返回的 `intent` 正确、answer 含检索内容，且**恰好写入 2 条消息** | 问答链路完整性与消息落库次数 |

第三个用例的 `verify(list, times(2)).rightPush(...)` 是个精准断言 —— 落库条数变化（多写/少写）会立刻被发现。

#### `orchestration/OutboundHttpTest.java` · 2 个用例

| 用例 | 验证内容 |
|---|---|
| `clientIsLockedToHttp11` | `OutboundHttp.client().version()` 必须是 `HTTP_1_1` |
| `restClientSendsPlainHttp11RequestWithIntactBody` | 裸 `ServerSocket` 抓原始报文：请求行必须是 `POST /api/nlp/intent HTTP/1.1`，**不含 `h2c` / `http2-settings`**，且**请求体完整** |

**这是全项目最有价值的一个测试**。缺陷 D-1 的特征是「端口能连、健康检查通过、但请求体丢失导致 422」——
常规集成测试极难发现。它直接断言线缆上的字节，把缺陷锁死在源头。第二个用例还顺带验证了
`Content-Length` 解析与完整收发，是一个自包含的最小 HTTP 服务端实现。

#### `orchestration/UpstreamDegradeTest.java` · 4 个用例

| 用例 | 验证内容 |
|---|---|
| `nlpClientDegradesToFallbackIntent` | 降级开启 + 上游拒绝连接 → `intent=闲聊`、`engine=FALLBACK` |
| `nlpClientRaisesUpstreamUnavailableWhenDegradeDisabled` | 降级关闭 → `AGENT_UPSTREAM_UNAVAILABLE`，且 **`getCause()` 非空**（根因保留） |
| `bodyClientDegradesToEmptyResult` | 降级开启 → 空列表 |
| `bodyClientRaisesUpstreamUnavailableWhenDegradeDisabled` | 降级关闭 → `AGENT_UPSTREAM_UNAVAILABLE` |

制造故障的手法很干净：`closedPort()` 先 `new ServerSocket(0)` 拿到一个空闲端口再**立即释放**，
从而得到一个当前无监听者的端口，稳定复现「连接被拒绝」。

## 5. 配置项

本模块的配置分散在三处，**改配置时注意区分**：

| 来源 | 配置项 |
|---|---|
| `application.yml` | `server.port`(8081)、`grpc.port`(9092)、`app.nlp.base-url`、`app.nlp.degrade-on-failure`、`app.body.base-url`、`app.body.degrade-on-failure`、`spring.data.redis.host/port`、`spring.cloud.nacos.discovery.server-addr` |
| 环境变量（`System.getenv`） | `NATS_URL`(默认 `nats://127.0.0.1:4222`)、`KAFKA_BOOTSTRAP`(默认 `127.0.0.1:9092`)、`KAFKA_CONSUMER_ENABLED`(默认 `true`) |
| 环境变量（经 yml 占位符） | `REDIS_HOST`、`REDIS_PORT`、`NACOS_ADDR`、`GRPC_PORT`、`NLP_BASE_URL`、`BODY_BASE_URL`、`NLP_DEGRADE_ON_FAILURE`、`BODY_DEGRADE_ON_FAILURE`、`OTLP_ENDPOINT` |

**易踩的坑**：NATS 与 Kafka 的配置**只读环境变量**，不读 `application.yml`。
想在不重启容器的前提下改 NATS 地址是做不到的。

## 6. 修改指引

| 需求 | 改动位置 |
|---|---|
| 新增会话相关接口 | `SessionController`，**必须调用 `requireOwnedSession`** |
| 调整会话 TTL | `SessionController.SESSION_TTL`（当前硬编码 2 小时，未配置化） |
| 调整召回数量 | `BodyClient.retrieve` 里的 `top_k`（当前 5，随请求显式声明；如需配置化可提为 `app.body.top-k`） |
| 新增跨服务调用 | 新建 Client 类，**必须用 `OutboundHttp.restClient`** |
| 新增错误码 | `common/ErrorCode` + `GlobalExceptionHandler` 的 switch（注意与 `sense-service` 同名文件同步） |
| 新增领域事件 | `KafkaEventPublisher.publish(domain, event, key, payload)`，主题自动拼为 `lifeform.{domain}.{event}` |
| 实现会话状态机（Phase 4） | `SessionController` 目前只有 `ACTIVE`/`CLOSED` 两个字面值，状态机需新增校验层 |
