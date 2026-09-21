# 10 · collab-bus 协作总线服务（Phase 6 前置 / MC-01）

> 模块路径：`services/java/collab-bus/`
> 源文件：**20 个主代码（2347 行）+ 5 个测试（763 行 / 38 个用例，全绿）**
> HTTP 端口：**8085**（`COLLAB_HTTP_PORT`）· **无 gRPC 端口**（未提供服务实现，pom 的 grpc 依赖仅为与其它模块对齐）
> 外部依赖：**NATS + JetStream**（传输与持久化）· **PostgreSQL**（域元数据 / 幂等状态 / 心跳快照 / 实例租约，共 4 张表）
> 阶段来源：**Phase 6 前置（不属任何 Phase 交付范围）** —— MC-01 协作总线独立切片，需求号 `R-MC01-01 ~ R-MC01-05`
> 上游入口：**工作平台 BFF `services/node/wp-bff`（8090）** —— `/api/wp/collab/domains`、`/api/wp/collab/{domain_id}`；
> **网关 `gateway-service` 没有 `/api/collab/**` 路由**，本服务不经网关对外

## 1. 模块职责

`collab-bus` 是生命体的**协作总线**（设计术语「C26 独立切片」）：多 Agent 在同一个**协作域**
（collaboration domain）里分工干活时，本服务负责三件事 —— **域的生命周期**、**消息的可靠投递**、
**成员进度的聚合**。它不承载业务语义：`dispatch / result / negotiate` 三类消息目前只走通传输层，
真实业务处理器由 Phase 6 注册（见 §5.3）。

| 需求 | 能力 | 落点 |
|---|---|---|
| R-MC01-01 | 域生命周期：创建 / 列表 / 详情 / 关闭；按域隔离的 JetStream stream | `domain/*` + `controller/DomainController` |
| R-MC01-02 | 可靠投递：JetStream 持久化 + `request_id` 幂等 + 有限重投 + 死信 | `delivery/*` + `nats/NatsConnection` |
| R-MC01-03 | 心跳聚合：降频窗口 / 失联标记 / 加权进度聚合 | `heartbeat/*` |
| R-MC01-04 | 可靠性加固：durable **pull** consumer、手工 ack/nak、P99 门禁 | `delivery/ReliableConsumer` |
| R-MC01-05 | 工作平台接入：BFF 代理协作域列表 / 详情 + 前端真实域选择与降级 | `services/node/wp-bff`（非本模块代码） |

对外契约登记在 `contracts/collab-bus-openapi.yaml`；Phase 6 端点优先级见
`contracts/phase6-endpoint-priority.yaml` 与 `docs/Phase6-必需端点排期冻结稿.md`。

## 2. 三条不变量（先记住这三条，再看代码）

后续所有设计选择都可以用这三条解释：

1. **不做内存降级（与审计类旁路数据相反）**。域元数据、幂等状态、心跳快照都是**核心状态**，
   PG 不可用时 `Repository.available()` 立刻转 false，服务层统一抛
   `AGENT_COLLAB_BUS_UNAVAILABLE`(503)，**绝不退化成进程内 Map**。
   理由写在 `CollabDomain` 类注释里：内存降级会造成「域创建了、重启后找不回」这类语义级伪实现。
2. **降级必须可见**。`NatsConnection.status()` 固定返回
   `nats_connected / jetstream_available / degraded / last_error`，`/api/collab/health` 把
   bus + consumer + heartbeat 三段状态拼在一起如实上报（沿用 2026-09-16 的降级可见化约定）。
3. **消息不静默丢弃**。消费失败先 `nak` 延迟重投；重投超过上限（默认 `retry-max=3`，即最多
   4 次尝试）写入死信主题 `collab.<domain>.deadletter` **并把幂等状态标记为 `dlq`**，
   死信发布自身失败时抛 503 而不是吞掉（宁可人工介入，不假装成功）。

## 3. 域的创建顺序（为什么先落 `creating`）

```text
POST /api/collab/domains
  │
  ├─ requireReady()        PG 可用？JetStream 可用？    ← 任一不可用 → 503（不插库）
  ├─ repository.insert()   写 collab_domain，state = creating
  ├─ addStream()           建 JetStream stream COLLAB_DOM_XXXX（File 存储，maxAge 24h）
  ├─ consumer.startDomain()建 durable pull consumer（并申请实例租约）
  └─ markActive()          creating/failed → active
        └─ 任一步失败 → stopDomain() + 删 stream + markFailed() → 抛 503（带 domain_id / state=failed）
```

**为什么要这个中间态**：如果先写 `active` 再建 stream，中途失败就会留下「库里有 active 域、
总线里没有 stream」的半成品 —— 之后所有发布都会失败且无从区分。
`DomainService.recoverIncompleteDomains()`（`ApplicationReadyEvent`）在启动时对账：
把 `active` 域重新挂上 consumer，把遗留的 `creating` 域补成 `active`，`failed` 域跳过不动。

**关闭域的顺序是反的**：先改 PG 状态（`close`），再停 consumer。
注释写明理由 —— **NATS 暂时不可用不应阻止域收口**，否则总线故障期间连域都关不掉。

## 4. 投递状态机（R-MC01-02 的核心）

幂等状态在 `collab_seen_request` 表，主键 `(domain_id, request_id)`：
**同一 `request_id` 在不同域中互不影响**。状态流转：

```text
claim(domain_id, request_id, member_id, maxAttempts)
  ├─ 首次插入成功        → CLAIMED     attempts=1        → 执行业务
  ├─ 已有 pending/failed → attempts+1
  │     ├─ ≤ 上限        → RETRY                        → 执行业务
  │     └─ > 上限        → EXHAUSTED                    → 直接 DLQ + ack
  └─ 已有 processed/dlq  → PROCESSED / DEAD_LETTER      → 直接 ack，不执行副作用
```

- **业务成功** → `markProcessed` → `ack`
- **业务失败且未达上限** → `markFailed` → 抛 `BizException` → 消费端 `nakWithDelay(retry-delay-ms)`
- **业务失败且已达上限** → `deadLetter()`（发布 DLQ + `markDeadLetter`）→ `ack`

三条容易被误读的点：

- **`claim()` 里 `status` 会从 `failed` 被改回 `pending`**，所以「失败」不是终态；
  这正是把原来的「看到 request_id 就永久丢弃」改成状态机的原因 —— 否则「重投 ≤ N → 死信」永远不成立。
- **DLQ 用的是独立 message id（`requestId + ".dlq"`）**，否则死信会在同一个 stream 内被
  JetStream 判为与原始消息重复而直接丢掉。
- **`consume()` 是兼容旧行为的薄封装**：只返回「本次是否真正完成一次业务」，`deliver()` 才是全状态版本。

### 4.1 durable pull consumer 与多实例租约

每个域一个 **durable pull consumer**（`durable = collab-worker-<domainId>`），
用虚拟线程跑 `pullLoop`：`fetch(batchSize=32, timeout=500ms)` → 逐条 `handle` → 循环。
选 pull 而非 push 的理由写在 `ReliableConsumer` 类注释：push 在进程重启后可能因
deliver subject / ackWait 配置漂移而**无法重新绑定**，未 ack 消息就变成孤儿。

启动时对既有 consumer 做一次兼容处理：若发现旧 push consumer（`deliver_subject` 非空）且
`pending=0 && ack_pending=0`，自动删除并迁移为 pull；**若有未处理消息则拒绝自动迁移并抛错**
（宁可人工介入，不冒险丢消息）。绑定既有 durable 时使用**服务端快照配置**（`bind(true)`），
避免客户端默认值触发 NATS 的「不可修改」校验。

多实例安全靠 `collab_domain_lease` 表：

- 每个进程一个 owner UUID（`UUID.randomUUID()`，进程级稳定）；
- `tryAcquire(domainId, leaseMs)` 用 `INSERT ... ON CONFLICT DO UPDATE ... WHERE lease_until < now()
  OR owner_id = EXCLUDED.owner_id RETURNING owner_id` 做**原子抢占**；
- `reconcile()` 每 `domain-reconcile-interval-ms`(5s) 跑一次：发现活跃域 → 自有则 `renew`、
  非自有则 `startDomain`（抢不到就跳过）；域被关闭 / failed → `stopDomain`；
- `@PreDestroy` 清掉本实例全部租约，让接管不必等租期自然过期。
- **fail-closed**：租约后端不可用时 `reconcile()` 直接 `log.error` 并 return，
  `startDomain` 抛 `IllegalStateException` —— 宁可不起 consumer，也不出现两个实例消费同一域。

## 5. 心跳聚合（R-MC01-03）

### 5.1 降频窗口：限流不等于丢数据

```text
report()
  ├─ 距上次接受 < 5s（min-interval-ms）→ throttled=true：只覆盖 pending 缓冲区，不写 PG
  └─ 否则                            → 直接 PG upsert
后台 flusher 每 5s 跑一次 flushPendingAt()：把缓冲区最后值落库
查询（members / aggregate）时 snapshot() = PG 快照 ⊕ pending 缓冲区（pending 覆盖 PG）
```

关键语义：**窗口内被限流的上报，其「最后一次值」仍会被采纳** —— 所以 `throttled=true` 不代表数据丢失，
响应体里同时给出 `accepted / throttled / persisted / queued / throttle_window_ms` 五个字段说明去向。
`pending` 是短时效旁路状态，进程崩溃最多丢一个窗口，失联会由 `stale` 暴露。

### 5.2 `stale` 是查询时派生的状态，不写库

`MemberHeartbeat.staleAt(now, staleMs)` 只是 `reportedAt + 30s <= now` 的判定；
`memberView()` 返回 `state = stale ? "stale" : item.state()`，同时用 `reported_state` 保留原始上报状态。
`REPORTABLE_STATES = {idle, working, blocked, done}` —— **`stale` 不允许客户端上报**（会被 400 拒）。
失联成员**保留不删除**，聚合结果里计入 `stale_count`，其最后已知进度仍参与加权平均。

聚合口径：`progress = Σ(weight × progress) / Σ(weight)`，`weight` 必须落在 `(0, 100]`，
缺省 1.0（等权即算术平均），结果四舍五入到两位小数。

### 5.3 消息类型与业务处理器的边界

`MessagePublisher.TYPES = {dispatch, result, heartbeat, negotiate}`，主题形状：

```text
collab.<domain_id>.<type>.<member_id>        # member_id 为空时用 "-"
collab.<domain_id>.deadletter                # 死信
```

消费端 `ReliableConsumer.dispatch()` 目前**只实现了 `heartbeat`**（解析 JSON 后交给
`HeartbeatService.report`）；`dispatch / result / negotiate` 走到 `log.debug("消息传输层已消费（业务处理器预留）")`。
这是**刻意留白**：传输层（持久化 / 幂等 / 重投 / 死信）已闭环并验收，业务处理器按 Phase 6 设计注册，
**不是未实现的伪实现** —— 消息确实被消费并 ack 了，只是没有副作用。

## 6. 数据模型（4 张 PG 表，全部由 `@PostConstruct` 建表）

| 表 | 主键 | 作用 | PG 不可用时 |
|---|---|---|---|
| `collab_domain` | `domain_id` | 域元数据；唯一真相源 | 503（不降级） |
| `collab_seen_request` | `(domain_id, request_id)` | 幂等 + 投递状态机（`pending/processed/failed/dlq`） | 503（不降级） |
| `collab_member_heartbeat` | `(domain_id, member_id)` | 成员心跳快照（原子 upsert） | 503（不降级） |
| `collab_domain_lease` | `domain_id` | 多实例域租约（owner + `lease_until`） | **fail-closed**：不起 consumer |

三张业务表都带索引（`(tenant_id, state)` / `(status, updated_at)` / `(domain_id, reported_at DESC)`），
`IdempotencyRepository` 还带一组 `ADD COLUMN IF NOT EXISTS` 迁移语句，兼容早期只存 `seen` 标记的表结构。
详细结构见 [08-数据存储与归档](08-数据存储与归档.md) 与仓库根 `db/`。

## 7. 文件清单

### 7.1 主代码（20 个，2347 行）

| 文件 | 类型 | 行数 | 职责 |
|---|---|---:|---|
| `CollabBusApplication.java` | 启动类 | 26 | Spring Boot 入口 + `@EnableDiscoveryClient`（Nacos 默认关闭） |
| `common/ErrorCode.java` | 枚举 | 34 | 统一错误码 + **4 个 MC-01 专属码** |
| `common/BizException.java` | 异常 | 28 | 携带错误码 + `details`，由全局处理器转错误信封 |
| `common/GlobalExceptionHandler.java` | 切面 | 42 | `BizException` / `IllegalArgumentException` / 兜底 500 → `{code,message,details}` |
| `nats/NatsConnection.java` | 组件 | 140 | NATS 连接管理：**惰性连接 + 断线重连 + 能力状态如实上报** |
| `domain/CollabDomain.java` | record | 41 | 域元数据 + 四个状态常量 + `active()` / `closed()` |
| `domain/DomainRepository.java` | 仓储 | 132 | `collab_domain` 建表 / 增查列删 / `markActive` / `markFailed` / `close`（幂等） |
| `domain/DomainService.java` | 服务 | 187 | R-MC01-01 域生命周期 + stream 编排 + **创建补偿** + **启动对账** |
| `delivery/MessagePublisher.java` | 服务 | 155 | R-MC01-02 发布（JetStream message id 去重）+ 死信发布 + 主题命名 |
| `delivery/IdempotencyRepository.java` | 仓储 | 187 | 幂等状态机存储：`claim` 原子领取 + 四态标记 + 过期清理 |
| `delivery/IdempotentConsumer.java` | 服务 | 133 | 消费编排：claim → 执行 → processed/failed/DLQ |
| `delivery/ReliableConsumer.java` | 组件 | 348 | R-MC01-02/04 durable pull consumer + 租约对账 + 虚拟线程拉取循环 |
| `delivery/DomainLeaseRepository.java` | 仓储 | 113 | 多实例域租约：原子抢占 / 续约 / 释放 / 退出清理 |
| `heartbeat/MemberHeartbeat.java` | record | 43 | 心跳快照 + `REPORTABLE_STATES` + `staleAt()` 派生判定 |
| `heartbeat/HeartbeatRepository.java` | 仓储 | 125 | `collab_member_heartbeat` 建表 / 原子 upsert / 列表 |
| `heartbeat/HeartbeatService.java` | 服务 | 372 | R-MC01-03 降频窗口 + 后台 flush + 合并快照 + 加权聚合 |
| `heartbeat/HeartbeatController.java` | 控制器 | 57 | 心跳上报 / 成员列表端点（租户取 `X-Tenant-Id`） |
| `controller/DomainController.java` | 控制器 | 84 | 域创建 / 列表 / 详情（**拼心跳聚合**）/ 关闭 |
| `controller/CollabController.java` | 控制器 | 41 | `/api/collab/health`：服务 + 总线 + consumer + 心跳四段状态 |
| `observability/CollabBusMetrics.java` | 组件 | 59 | Micrometer 指标（域数 / 消息数 / 重投 / 心跳 / 积压） |

### 7.2 测试（5 个文件，38 个用例，全绿）

| 文件 | 行数 | 用例 | 覆盖验收点 |
|---|---:|---:|---|
| `delivery/DeliveryTest.java` | 197 | 11 | R-MC01-02 单测：主题命名 / 关闭域 409 / 跨租户 404 / 状态机四态 / 重投 / 死信 / PG 不可用 503 |
| `domain/DomainServiceTest.java` | 143 | 8 | R-MC01-01 单测：PG 与 JetStream 不可用不插库 / stream 失败补偿 / 关闭幂等 / 命名约定 |
| `heartbeat/HeartbeatTest.java` | 202 | 10 | R-MC01-03 单测：窗口合并 / flush 落最后值 / 查询含 pending / stale 保留 / 越界拒绝 |
| `delivery/DeliveryIntegrationTest.java` | 146 | 6 | **真实 PG + NATS**：publish ack / 幂等只执行一次 / **离线 50 条全量恢复** / 自动 DLQ / 死信 ack |
| `heartbeat/HeartbeatIntegrationTest.java` | 75 | 3 | **真实 PG**：三成员加权聚合 / stale 可见 / 限流后仍更新最后值 |

> 两个集成测试类由 `@EnabledIfEnvironmentVariable(named = "COLLAB_IT", matches = "true")` 守卫，
> 默认（本地 `mvn test`）**不执行**，因此基线统计是「29 执行 / 9 条件跳过 / 0 失败」。
> 需要真跑时：`COLLAB_IT=true` 且 PG + NATS 就绪。

## 8. 逐文件说明（关键实现）

### 8.1 `nats/NatsConnection.java` · 传输底座 · 140 行

构造函数里只调一次 `connectQuietly()`，**失败不抛出**（`connection = null` + 记 `lastError`），
所以 NATS 不在线**不会让服务启动失败**；`connection()` 每次调用时若发现未连接会**惰性重试一次**。
`Options` 用 `maxReconnects(-1)` + `reconnectWait(2000ms)` + `connectionTimeout(3s)`。
`jetStream()` / `jetStreamManagement()` 在未连接时抛 `IllegalStateException`（由上层转 503）。
`status()` 是给前端「总线状态灯」用的事实来源，`note` 字段会明确写出降级后果。

### 8.2 `domain/DomainService.java` · 域生命周期 · 187 行

除 §3 的创建/关闭顺序外，两个值得记住的点：

- `streamName(domainId)` = `COLLAB_` + 域名大写、`-` 换 `_`（`dom-abc123` → `COLLAB_DOM_ABC123`）；
  `subjectPattern` = `collab.<domainId>.>`。命名是**域隔离的唯一依据**（一个域一个 stream）。
- `summary()` 是给 BFF 用的运行态视图，额外带上 `stream` 与 `subject_pattern`，方便排障时直接照抄。
- 构造参数 `domain-concurrency-limit`(8) 写入域元数据（本切片**仅登记**，Phase 6 才用于限流），
  `stream-max-age-hours`(24) 决定新建 stream 的 `maxAge`（该键**未在 yml 声明**，见 §10 第 3 条）。

### 8.3 `delivery/IdempotencyRepository.java` · 幂等状态 · 187 行

`claim()` 用「先 `INSERT ... ON CONFLICT DO NOTHING`，再条件 `UPDATE`」两步实现原子领取，
不需要显式事务：插入成功即 `CLAIMED`；插入被冲突挡下才走 `UPDATE ... WHERE status IN ('pending','failed')`，
`affected=0` 说明该行是终态（`processed` / `dlq`），返回重复结果。
`last_error` 截断到 2000 字符，`cleanupOlderThanHours()` 按 `updated_at` 清理过期幂等记录
（应与 stream 保留期一致）。`ClaimResult.shouldExecute()` / `duplicate()` 把状态判定收在 record 里，
调用方不必自己写 switch。

### 8.4 `delivery/ReliableConsumer.java` · 可靠消费 · 348 行

本模块最重的文件，四段职责：

| 段 | 方法 | 说明 |
|---|---|---|
| 对账 | `startAll` / `reconcile` / `reconcileSafe` | `ApplicationReadyEvent` 起一次 + 5s 定时；**租约不可用直接 fail-closed** |
| 生命周期 | `startDomain` / `stopDomain` / `stopAll` | 全部 `synchronized`；`stopDomain` 保留 durable 进度供恢复继续消费 |
| 拉取 | `pullLoop` | 虚拟线程 `Thread.ofVirtual()`，批次 32 / 500ms 超时；异常 sleep 后重试 |
| 处理 | `handle` / `dispatch` | 读 headers → `IdempotentConsumer.deliver` → 按结果 `ack` 或 `nakWithDelay` |

`ConsumerConfiguration`：`AckPolicy.Explicit` + `DeliverPolicy.All` + `ackWait=30s` +
`maxDeliver = retryMax + 1` + 退避 `250ms / 1s / 2s` + `filterSubject = collab.<domain>.*.*`
（**注意是三段通配**：`collab.<domain>.<type>.<member>`，而 stream 的 subjects 是 `collab.<domain>.>`）。
`requestId` 缺失时用 `msg-<stream>-<seq>` 兜底，保证「没有 header 的消息」也能进入幂等状态机。
`status()` 暴露 `active_domains / domain_ids / lease_owner / lease_backend_available`。

### 8.5 `heartbeat/HeartbeatService.java` · 心跳聚合 · 372 行

`reportAt()` 是**可注入时间**的核心（`report()` 只是喂 `Instant.now()`），单测因此能在
`T0` / `T0+1s` / `T0+31s` 上精确验证窗口与 stale。`status()` 暴露
`pending_updates / last_flush_at / last_flush_count / last_flush_error / min_interval_ms / stale_after_ms` ——
**「合并是否真的生效」是可以从健康端点直接看出来的**。
`forgetDomain()` 在域停止时按 `domainId|` 前缀清掉两个 Map，避免长期运行后键泄漏。
`@PreDestroy` 里 `flusher.shutdownNow()` 后再 `flushPendingSafely()` 做最后一次落库。

### 8.6 错误码（`common/ErrorCode.java`）

通用码沿用项目约定（400/401/403/404/405/409/500/503/504），额外 4 个 MC-01 专属码：

| 码 | HTTP | 触发 |
|---|---:|---|
| `AGENT_COLLAB_DOMAIN_NOT_FOUND` | 404 | 域不存在**或不属于当前租户**（不泄露存在性） |
| `AGENT_COLLAB_DOMAIN_CLOSED` | 409 | 向已关闭域发布消息 / 上报心跳 |
| `AGENT_COLLAB_BUS_UNAVAILABLE` | 503 | PG 或 JetStream 不可用；域创建失败 |
| `AGENT_COLLAB_HEARTBEAT_INVALID` | 400 | 心跳字段非法；消息类型不在 `TYPES` 内 |

注释里专门强调 **404 与 5xx 必须可区分** —— 这是前端判定「端点未实现」与「服务故障」的前提。

## 9. 运行与配置

### 9.1 端口与服务名

| 项 | 值 | 覆盖变量 |
|---|---|---|
| HTTP | `8085` | `COLLAB_HTTP_PORT` |
| gRPC | — | 未启用 |
| Nacos 服务名 | `collab-bus` | `NACOS_ENABLED`（**默认 `false`**，本地单体不注册） |

> 8085 是 MC-01 切片新分配的端口；本服务**不经网关**，因此不占用网关路由表。

### 9.2 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `NATS_URL` | `nats://127.0.0.1:4222` | JetStream 必须开启（容器 `-js --store_dir /data`） |
| `PG_URL` / `PG_USER` / `PG_PASSWORD` | `jdbc:postgresql://127.0.0.1:5432/lifeform` / `agent` / `agent123` | 4 张协作表所在库 |
| `COLLAB_HEARTBEAT_MIN_INTERVAL_MS` | `5000` | 心跳降频窗口 |
| `COLLAB_MEMBER_STALE_MS` | `30000` | 成员失联阈值（`state` 派生为 `stale`） |
| `COLLAB_RETRY_MAX` | `3` | 重投上限（总尝试次数 = 该值 + 1） |
| `COLLAB_DOMAIN_CONCURRENCY_LIMIT` | `8` | 域并发上限（**本切片仅登记**） |
| `COLLAB_DOMAIN_LEASE_MS` | `30000` | 多实例域租约时长 |
| `COLLAB_DOMAIN_RECONCILE_INTERVAL_MS` | `5000` | 租约对账周期 |

未被 `application.yml` 声明、只能靠代码默认值生效的项（**想改必须加进 yml**）：
`app.collab.consumer-durable-prefix`(collab-worker)、`app.collab.retry-delay-ms`(500)、
`app.collab.ack-wait-ms`(30000)、`app.collab.consumer-batch-size`(32)、
`app.collab.consumer-poll-timeout-ms`(500)。

### 9.3 指标（`/actuator/prometheus`）

`lifeform.collab.active.domains`(gauge) · `lifeform.collab.heartbeat.pending`(gauge) ·
`lifeform.collab.messages{direction=published|consumed}` · `lifeform.collab.consumer.retries` ·
`lifeform.collab.heartbeat.reports{outcome}` · `lifeform.collab.heartbeat.flushed` ·
`lifeform.collab.deadletter`（见 §10 第 2 条）。
HTTP 与 GC 直方图已开启（`percentiles-histogram` + `0.5/0.95/0.99`），指标页可读真实 P95/P99。

### 9.4 本地起服务

```bash
docker compose up -d nats postgres          # 依赖：lifeform-nats（-js）/ lifeform-postgres
cd services/java
../../scripts/mvn-dev.sh -pl collab-bus -am clean package
java -jar collab-bus/target/collab-bus-0.1.0-SNAPSHOT.jar     # 默认 8085
```

## 10. 已知缺口（诚实登记，勿当已完成）

1. **业务处理器未注册**：`dispatch / result / negotiate` 三类消息只走到传输层（§5.3），
   真实处理器按 Phase 6 设计接入。这属**刻意的接口冻结**，不是遗漏。
2. **`CollabBusMetrics.deadLetter()` 是死代码**：全模块**无任何调用点**（已 grep 确认），
   因此 `lifeform.collab.deadletter` 计数恒为 0 —— 死信确实发出去了（`MessagePublisher.publishToDeadLetter`），
   只是没打点。指标页若展示该指标，会低估死信量。
3. **配置键漂移**：`application.yml` 声明了 `app.collab.stream-retention-after-close-ms`
   （`COLLAB_STREAM_RETENTION_MS`），但**代码里没有任何地方读它**；代码实际读的
   `app.collab.stream-max-age-hours`（`DomainService` 构造参数）**在 yml 里不存在** ——
   因此 stream 保留期恒为代码默认 24h，设 `COLLAB_STREAM_RETENTION_MS` **无效**。
   另注：`stream-retention-after-close-ms` 语义是「域关闭后保留」，与 stream 的 `maxAge` 并不是同一件事，
   修的时候需要先定语义再接线。
4. **`concurrency_limit` 只登记不生效**：域并发上限已入库、已出现在 `summary()`，但没有任何限流逻辑消费它（Phase 6 使用）。
5. **`GET /api/collab/domains/{id}` 与 `/members` 的聚合形态不同**：详情端点把心跳聚合
   `putAll` 到域摘要里（字段平铺），成员列表端点则返回 `{total, stale_count, items}` 包装；
   前端取字段时需分别处理。这是既定接口形态，改动需同步 `contracts/collab-bus-openapi.yaml`。
6. **无 Dockerfile**：本模块与其它 Java 服务一样，只有 `pom.xml`，部署形态是 `java -jar`。

## 11. 改这里要注意什么

- **改契约前先看** `contracts/collab-bus-openapi.yaml` 与 `docs/Phase6-前置设计冻结稿.md`：
  `publish(domain, type, member, request_id, payload)` 的形状已冻结供 Phase 6 依赖。
- **不要给核心状态加内存降级**（§2 不变量 1）。要加降级，先在 `docs/技术债台账.md` 登记。
- **域状态机只增不改**：`creating → active → closed`、失败进 `failed`；`markActive` 只接受
  `creating/failed` 两个来源状态，绕过它直接改 state 会破坏启动对账。
- **测试分层**：单测用 mock（`DeliveryTest` / `DomainServiceTest` / `HeartbeatTest`），
  真链路必须走 `COLLAB_IT=true` 的集成测试；新增可靠性语义时**两边都要补**。
- 改完 Java 文件后跑 `python scripts/java-doc-coverage.py` 确认本篇清单未过期。

## 12. 相关文档

- [01-模块总览与架构](01-模块总览与架构.md) —— 模块清单、依赖与端口
- [07-运行时配置与横切约定](07-运行时配置与横切约定.md) —— 全平台配置与横切约定
- [08-数据存储与归档](08-数据存储与归档.md) —— 4 张协作表的存储总纲
- `docs/Phase6-前置设计冻结稿.md` —— MC-01 与 Phase 6 的边界
- `docs/test-reports/collab/2026-09-21/` —— 规模验证（25/100/500/1000 域）、多实例接管、告警投递实测产物
