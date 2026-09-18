# 05 · sense-service 感官服务

> 模块路径：`services/java/sense-service/`
> 源文件：**35 个主代码 + 9 个测试**（主代码约 2,900 行；全项目最大模块）
> HTTP 端口：**8082** · gRPC 端口：**9093**
> 主要依赖：Spring Web、jsoup（HTML 正文提取）、MinIO SDK、resilience4j-retry、Kafka clients、Lombok

## 1. 模块职责

`sense-service` 是生命体的**五感采集器官**，是 Phase 2（感官期）的主体交付物，对应需求 **R2-01 ~ R2-10**。

它负责一条完整的管道：

```text
触发（R0 定时 / R1 指令）
  → 渠道路由（含故障降级）
  → 采集（指数退避重试）
  → 标准化五字段打标（R2-04）
  → 质检（硬规则 + 软评分）
  → 隔离暂存（MinIO / 本地，可回滚）
  → 事件发布（lifeform.sense.collected）
  → 指标登记
重试耗尽 / 质检拒绝 → 死信队列
```

治理设施环绕这条管道：`ChannelHealthMonitor`（探针 / 隔离 / 恢复）、`DeadLetterStore`、`SenseMetrics`
（供 Console 感官视图使用）。**注意：本模块不做知识入库** —— 采集结果落暂存区后由 Phase 3 躯体期消费。

## 2. 五感渠道的语义映射

「五感」不是装饰性命名，每个渠道对应一类**真实的采集形态**：

| 渠道 | 语义 | 实现状态 | 数据源形态 | 时效标记 |
|---|---|---|---|---|
| `TOUCH` 触觉 | 文档 / 网页 / 文本 | ✅ 已实现 | URL、`file://` 本地文件、纯文本 | 文件=BATCH，其余=NEAR_REALTIME |
| `VISUAL` 视觉 | 图片 → OCR → 文本 | ✅ 已实现（依赖 OCR 引擎） | 图片 URL、本地图片 | NEAR_REALTIME |
| `NOSE` 嗅觉 | 舆情 / 情报采集 | ✅ 已实现（依赖情报源配置） | 多个 RSS/页面/API 批量抓取 | NEAR_REALTIME |
| `TASTE` 味觉 | **数据质检**（向内） | ✅ 已实现 | 暂存区批次抽样 | BATCH |
| `AUDIO` 听觉 | 语音 → 转写 | ⏳ 预留（Phase 3 接 ASR） | — | — |

两点特别值得注意：

1. **味觉是唯一「向内」的渠道** —— 它不采集外部数据，而是对暂存区已采集的批次做**质检复核**，
   产出质量报告。五感矩阵里它的数据源是「系统自己」，所以 `healthy()` 恒为 `true`。
2. **嗅觉是「聚合渠道」** —— 它复用 `TouchChannel.fetchUrlContent` 抓取多个情报源，把结果合并为一条采集结果，
   `qualityScore` 定义为「成功抓取的源占比」（`ok / sources.size()`）。未配置任何情报源时，它 `available()=false`
   → 被健康监控标记为 DOWN。

## 3. 关键设计约束（改代码前必读）

### 3.1 未实现的渠道必须诚实上报，不能伪造结果

预留渠道（`AudioChannel`）的标准写法：

```java
@Override public boolean available() { return false; }   // 参与路由判定
@Override public boolean healthy()  { return false; }    // 参与健康判定
// collect() 返回 accepted=false + 明确 error 说明，不返回假数据
```

这条约定贯穿全模块：**能力不可用时如实返回 `available=false` / `DEGRADED`**，
让 Console 五感矩阵能显示真实状态。这条约束同样适用于 OCR（`VisualChannel.healthy()` 探到引擎未装时返回 false）。

### 3.2 质检判定必须单一事实来源

`QualityGate` 有两条判定路径：硬规则 `rejectReason()` 与软评分 `score()`。
**`pass()` 必须委托 `rejectReason()`，不能用 `score() >= threshold` 代替**。

原因（这是实现期踩过的真实缺陷）：如果 `pass()` 用评分判定、`rejectReason()` 用硬规则判定，
两者会在「内容重复乱码」这类场景**给出不一致结论** —— 评分因内容非空而偏高，硬规则却已判定为垃圾，
结果就是 `pass()` 放行、落库时 `rejectReason()` 又不为空，状态自相矛盾。当前实现：

```java
public boolean pass(String content) { return rejectReason(content) == null; }  // 单一事实来源
```

**新增任何质检规则时，只加到 `rejectReason()` 里，不要另开判定分支。**

### 3.3 出站请求一律经 `OutboundGuard` + `HttpClients`

| 工具 | 作用 | 强制原因 |
|---|---|---|
| `OutboundGuard.assertPublicHttpUrl(uri, allowedHosts)` | 拦截 loopback / 私网 / link-local / multicast / CGNAT / IPv6 ULA / 非 http(s) | **SSRF 防护**，`dataSource` 是用户输入 |
| `OutboundGuard.assertInternalHttpUrl(uri)` | 仅校验 http(s) + host 可解析 | 用于**配置来源**的可信端点（如 OCR 服务） |
| `HttpClients.builder(timeout)` | 预置 **HTTP/1.1** + 连接超时 | 缺陷 D-1：默认 h2c 升级会导致 uvicorn 丢弃请求体 → 422 |

**两个断言函数不可混用**：`assertInternalHttpUrl` 允许私网地址（因为 OCR 服务就在本机），
若误用于用户提交的 `dataSource`，等于开放 SSRF。反之用 `assertPublicHttpUrl` 检查内部端点会因私网被拦而必然失败。

### 3.4 路由降级顺序固定为「触觉优先」

`CollectPipeline.route()` 在目标渠道不可用或被隔离时，按固定顺序找替代渠道：
**`TOUCH` 永远排第一**，其余按枚举声明顺序（VISUAL / AUDIO / NOSE / TASTE）。

选择触觉优先的理由：它是唯一**不依赖任何外部引擎或配置**的渠道（URL/文件/文本三种形态都能处理），
最可能成功。若所有渠道都不可用，返回 `failFast`（记录死信 + 落暂存 REJECTED），而不是抛异常 —— 保证调用方始终能拿到结构化结果。

### 3.5 本地文件读取有双层边界

`TouchChannel.resolveInFileRoot()` 是唯一的文件访问入口，防护两层：

1. **前缀剥离**：把 `file://` / `file:` / `local://` 前缀去掉，反斜杠归一，去前导斜杠。
   **注意不能用 `URI.getPath()`** —— 对 `file://page.html` 这种无 host 写法，`page.html` 会被当作 host、`path` 返回空，
   导致读取失败。这是实现期修复过的缺陷。
2. **规范化 + 白名单校验**：`root.resolve(raw).normalize()` 后必须 `startsWith(root)`，否则抛
   `SecurityException("path escapes sense file-root")`，拦截 `../../etc/passwd` 类穿越。

## 4. 文件清单

### 4.1 主代码（35 个）

| 文件 | 类型 | 行数 | 职责 |
|---|---|---:|---|
| `SenseServiceApplication.java` | 启动类 | 23 | 入口 + 服务发现 + `@EnableScheduling` + 配置扫描 |
| **channel/** | | | |
| `channel/SenseChannel.java` | 接口 | 83 | 渠道抽象契约 + `ChannelType` 枚举 + 请求/结果 DTO |
| `channel/ChannelRegistry.java` | 组件 | 66 | 渠道注册表（Spring 自动注入全部实现） |
| `channel/TouchChannel.java` | 渠道 | 194 | 触觉：URL / 文件 / 文本三源 |
| `channel/VisualChannel.java` | 渠道 | 143 | 视觉：取图 → OCR → 文本 |
| `channel/NoseChannel.java` | 渠道 | 102 | 嗅觉：多情报源批量抓取聚合 |
| `channel/TasteChannel.java` | 渠道 | 75 | 味觉：暂存区批次质检复核 |
| `channel/AudioChannel.java` | 渠道 | 39 | 听觉：预留（Phase 3 接 ASR） |
| **collect/** | | | |
| `collect/CollectPipeline.java` | 组件 | 181 | **采集主流程**（路由/重试/质检/暂存/事件/死信） |
| **config/** | | | |
| `config/SenseProperties.java` | 配置类 | 116 | `sense.*` 全部配置绑定（R2-01~R2-10） |
| **controller/** | | | |
| `controller/SenseController.java` | 控制器 | 124 | 采集入口（collect / command / health） |
| `controller/SenseAdminController.java` | 控制器 | 225 | 运营观测接口（Console 感官视图数据面） |
| **deadletter/** | | | |
| `deadletter/DeadLetterStore.java` | 组件 | 84 | 死信队列（重试耗尽登记） |
| **dynamic/** | | | |
| `dynamic/SenseCommand.java` | record | 23 | R1 动态采集指令（BrainCommand 雏形） |
| `dynamic/R1DynamicHandler.java` | 组件 | 111 | R1 指令 → 渠道选择 → 采集 → 摘要回传 |
| **event/** | | | |
| `event/SenseEventPublisher.java` | 组件 | 95 | Kafka 采集事件发布（`lifeform.sense.collected`） |
| **grpc/** | | | |
| `grpc/GrpcHealthServer.java` | 组件 | 32 | gRPC Health 探针 |
| **health/** | | | |
| `health/ChannelHealthMonitor.java` | 组件 | 119 | 渠道探针 / DEGRADED / 隔离 / 恢复 |
| **metrics/** | | | |
| `metrics/SenseMetrics.java` | 组件 | 125 | 采集指标聚合（Console 数据来源） |
| **model/** | | | |
| `model/CollectedData.java` | 模型 | 78 | 采集数据模型（五字段 + 状态 + 统计） |
| **nats/** | | | |
| `nats/MinimalNatsClient.java` | 客户端 | 143 | **自研**最小 NATS 协议实现 |
| `nats/NatsBusServer.java` | 组件 | 67 | 总线应答服务（ping / collect 两类主题） |
| **normalize/** | | | |
| `normalize/DataNormalizer.java` | 组件 | 68 | 五字段标准化打标（R2-04） |
| **quality/** | | | |
| `quality/QualityGate.java` | 组件 | 116 | 质检门槛（硬规则 + 加权评分） |
| **schedule/** | | | |
| `schedule/R0Scheduler.java` | 组件 | 133 | R0 定时调度（规则驱动 + 调度日志） |
| **security/** | | | |
| `security/OutboundGuard.java` | 工具类 | 84 | SSRF 出站守卫 |
| **staging/** | | | |
| `staging/StagingBackend.java` | 接口 | 34 | 暂存后端抽象 |
| `staging/StagingStore.java` | 组件 | 68 | 暂存入口（自动选后端 + 实时降级） |
| `staging/MinioStagingBackend.java` | 后端 | 188 | MinIO 实现（主） |
| `staging/LocalStagingBackend.java` | 后端 | 188 | 本地文件实现（降级） |
| **util/** | | | |
| `util/HttpClients.java` | 工具类 | 24 | HTTP/1.1 客户端构建器（缺陷 D-1） |
| `util/TextExtractor.java` | 工具类 | 117 | HTML 正文提取 + 编码嗅探解码 |
| **common/** | | | |
| `common/ErrorCode.java` | 枚举 | 31 | 统一错误码 |
| `common/BizException.java` | 异常 | 34 | 业务异常 |
| `common/GlobalExceptionHandler.java` | 切面 | 93 | 统一异常响应 + **路由层 404/405/415 分流**（见 [07](07-运行时配置与横切约定.md) §5） |

### 4.2 测试（9 个类 · 53 个用例）

（路径前缀 `src/test/java/com/agent/sense/`）

| 测试类 | 用例数 | 覆盖需求 |
|---|---:|---|
| `channel/TouchChannelTest.java` | 12 | R2-01 / R2-02 / R2-06 |
| `quality/QualityGateTest.java` | 7 | R2-04 / R2-10 |
| `collect/CollectPipelineTest.java` | 6 | R2-02 / R2-06 / R2-09 / R2-10 |
| `normalize/DataNormalizerTest.java` | 6 | R2-04 |
| `staging/StagingStoreTest.java` | 6 | R2-10 |
| `util/TextExtractorTest.java` | 6 | R2-02 |
| `common/GlobalExceptionHandlerTest.java` | 5 | 路由层错误语义（2026-09-18 缺陷回归） |
| `health/ChannelHealthMonitorTest.java` | 4 | R2-09 |
| `util/HttpClientsTest.java` | 1 | 缺陷 D-1 回归守卫 |

## 5. 逐文件说明

### 5.1 启动类

#### `SenseServiceApplication.java` · 23 行

```java
@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling            // 必需：R0Scheduler 与 ChannelHealthMonitor 都依赖 @Scheduled
@ConfigurationPropertiesScan // 必需：自动发现 SenseProperties（无需在别处显式登记）
```

- 两个注解是**功能性的**，不是可选配置：去掉 `@EnableScheduling` 会导致 R0 调度与健康探针静默失效
  （不报错、只是不执行）；去掉 `@ConfigurationPropertiesScan` 会导致 `sense.*` 配置全部不生效。
- **对比**：`gateway-service` 用 `@EnableConfigurationProperties(XxxProperties.class)` 显式登记，
  本模块用扫描。新增配置类时本模块无需改动启动类。

### 5.2 channel 包 — 渠道抽象与五感实现

#### `SenseChannel.java` · 接口 · 83 行

渠道可插拔的核心契约（需求 R2-01）。包含一个接口 + 一个枚举 + 两个 DTO。

**接口方法**：

| 方法 | 说明 |
|---|---|
| `ChannelType type()` | 渠道类型（注册表的 key） |
| `boolean register(Map<String,String> config)` | 注册（幂等） |
| `CollectResult collect(CollectRequest)` | 执行采集（**核心**） |
| `boolean healthy()` | 健康检查（供探针调用） |
| `void close()` | 资源释放 |
| `boolean available()` | **默认 `true`**：渠道是否已实现。预留渠道覆写为 `false` |

`healthy()` 与 `available()` 的区别是理解本模块的关键：

- `available()` = **静态属性**（这个渠道实现了吗），不该随运行状态变化；
- `healthy()` = **动态状态**（现在能用吗），随外部依赖变化。

路由层两个都检查：`channel.available() && !healthMonitor.isIsolated(type)`。

**`ChannelType` 枚举**：五个值各带中文 `label` 与 emoji `icon`（供 Console 五感矩阵直接使用）。

`parse(String)` 的容错行为值得注意：

| 输入 | 结果 |
|---|---|
| `null` / 空白 | **默认返回 `TOUCH`**（不报错） |
| 枚举名（如 `"visual"`，大小写不敏感） | 对应渠道 |
| **中文名**（如 `"视觉"`） | 对应渠道 —— 兼容 Console 中文传参 |
| 其他 | 抛 `IllegalArgumentException` |

在 `parse` 之外调用（如 `R0Scheduler.run`、`R1DynamicHandler`）时，非法渠道名会向上抛异常。

**`CollectRequest`**（`@Data`）：`dataSource`、`query`、`params`、`tenantId`、`mode`（`R0_DEFAULT` / `R1_DYNAMIC`）。

**`CollectResult`**（`@Data`）—— 渠道的返回值契约，两个字段有**哨兵默认值**需要留意：

| 字段 | 默认 | 约定 |
|---|---|---|
| `confidence` | **`-1`** | 负数表示「渠道未上报」，标准化层据此改用 `qualityScore` |
| `freshness` | `null` | 空表示「渠道未上报」，标准化层按 `mode` 推断 |
| `accepted` | `false` | 采集是否成功（**驱动重试与质检两条分支**） |
| `error` | — | 失败原因，同时用于死信登记与错误信息 |
| `failed()` | 派生 | `!accepted` |

**`confidence = -1` 这个设计很重要**：它让「渠道报了 0 分」与「渠道没报」可区分 ——
若用 `0` 表示未上报，标准化层就无法判断该不该回退到 `qualityScore`。

#### `ChannelRegistry.java` · 组件 · 66 行

- **职责**：渠道注册表。构造器注入 Spring 容器里**全部** `SenseChannel` Bean，
  按 `type()` 放入 `EnumMap`，并逐个调用 `register(Map.of())`。
- **可插拔性的实现点**：新增渠道只需「实现 `SenseChannel` + 标注 `@Component`」，
  **本类无需修改** —— 这正是 R2-01 要求的效果。
- **线程安全**：`Collections.synchronizedMap(EnumMap)`。注册只发生在构造期，实际并发压力在读取，够用。
- **方法**：

| 方法 | 说明 |
|---|---|
| `find(type)` | 返回 `Optional<SenseChannel>` |
| `require(type)` | 不存在则抛 `IllegalStateException` |
| `all()` | 全部渠道（返回不可变副本） |
| `types()` | 已注册的渠道类型 |
| `available()` | **只返回 `available()==true` 的渠道**（用于 Console 区分「预留」与「可用」） |
| `healthSnapshot()` | `ChannelType → healthy()` 快照，**单个渠道抛异常会被捕获为 `false`** |
| `size()` | 注册数量 |

#### `TouchChannel.java` · 渠道 · 194 行

**主渠道实现**（R2-02），三种数据源形态。这是唯一在无外部依赖时也能工作的渠道，因此被路由层设为降级首选。

**数据源判定**（`kindOf`）：

| 前缀 | `SourceKind` | 处理 |
|---|---|---|
| `http://` / `https://` | `URL` | SSRF 守卫 + 重定向跟随 + 大小上限 + 正文提取 |
| `file://` / `file:` / `local://` | `FILE` | file-root 白名单读取 |
| 其他 | `TEXT` | 直接当作文本处理 |

空白 `dataSource` 抛 `IllegalArgumentException`。

**置信度与时效的自报值**（体现「渠道最了解自己的数据质量」）：

| 数据源 | confidence | freshness |
|---|---|---|
| `TEXT` | 0.8 | NEAR_REALTIME |
| `URL` | 0.9 | NEAR_REALTIME |
| `FILE` | 0.9 | **BATCH** |

**`collect()` 的异常处理**：捕获所有异常 → `accepted=false` + `error=e.getMessage()`。
这意味着**采集失败不会抛异常**，而是以结构化结果返回，交由 `CollectPipeline` 决定重试与降级。

**公开与包内方法**：

| 方法 | 可见性 | 说明 |
|---|---|---|
| `collect(CollectRequest)` | public | 接口实现 |
| `assertPublicHttpUrl(URI)` | public | **仅为兼容 Phase 1 单测保留**，委托 `OutboundGuard` |
| `fetchUrlContent(String)` | 包内 | 供**同包渠道**（`NoseChannel`）复用 |
| `readBinaryForChannel(String, long)` | 包内 | 供 `VisualChannel` 取图 |
| `kindOf(String)` | 包内 | 数据源类型判定（测试可访问） |

**HTTP 客户端的三个约束**：

1. `HttpClients.builder(...)` → 锁定 **HTTP/1.1**（缺陷 D-1）。
2. `followRedirects(NEVER)` → **手动处理重定向**，而非交给 HttpClient。
3. 手动重定向带来的安全能力：**每跳都重新做 SSRF 校验**（`assertPublicHttpUrl` 在循环内），
   防止「公网 URL 重定向到内网地址」这种绕过手法。同时限制最多 `MAX_REDIRECTS = 3` 跳，
   并**禁止 HTTPS → HTTP 降级**（`"HTTPS downgrade redirect is forbidden"`）。

**大小上限**：响应体超过 `sense.max-bytes`（默认 2 MiB）抛 `IllegalArgumentException`。

**`resolveInFileRoot` 的实现细节**（见 §3.5）：前缀剥离而非 `URI.getPath()`，
因为后者对 `file://page.html` 返回空 path。规范化后做 `startsWith(root)` 校验，
并用 `Files.isRegularFile` 排除目录与特殊文件。

**`sniffContentType`**：按扩展名给出 Content-Type 提示（`.html/.htm` → `text/html; charset=UTF-8`；
`.txt/.md/.csv/.json` → `text/plain; charset=UTF-8`；其他返回空串让编码嗅探自行判断）。
这让本地 HTML 文件也能走正文提取路径。

**构造器有两个**：`@Autowired TouchChannel(SenseProperties)` 与无参 `TouchChannel()`
（后者用默认配置，供测试与独立使用）。

#### `VisualChannel.java` · 渠道 · 143 行

**视觉渠道**（R2-03）：图片 → OCR → 文本。

**职责边界是刻意的**：Java 侧只做「取图 → Base64 编码 → 调用 → 置信度回填」，
**OCR 引擎本身在 Python `nlp-service`**（PaddleOCR）。这样 Java 侧不引入重型依赖，
且换引擎只需改 Python 侧。

**没有覆写 `available()`** → 恒为 `true`（视觉渠道是已实现的）。
**健康状态完全取决于 OCR 探针**（`healthy()`）：

```text
① 配置 sense.ocr.enabled = false        → false
② 距上次探测 < sense.health.probe-ms    → 返回缓存的 ocrReachable（避免频繁探测）
③ 探测 nlp-service 的 /ocr/health
   要求 HTTP 200 且响应体含 "available":true
```

探测用 `assertInternalHttpUrl`（OCR 服务是本机可信端点，允许私网）—— 与用户提交的 `dataSource`
走 `assertPublicHttpUrl` 形成对比。

**URL 拼接细节**：从配置的 `sense.ocr.url`（默认 `.../api/nlp/ocr`）**剥掉 `/ocr` 后缀**再拼 `/ocr/health`，
得到 `.../api/nlp/ocr/health`。`stripSuffix` 就是这个用途。

**`collect()` 流程**：

```text
loadImage(dataSource)
  ├─ http(s):// → assertPublicHttpUrl 校验 → GET 字节流（读超时）→ 校验大小上限
  └─ 其他       → touchChannel.readBinaryForChannel(...)（复用 file-root 白名单）
invokeOcr(image, source)
  ├─ ocr.enabled=false → 抛异常（enabled 关闭时不静默返回空）
  ├─ POST { image_base64, source } 到 OCR 端点（OCR 超时 20s）
  └─ 非 200 → 抛异常
组装结果：
  content = ocr.text().strip()
  accepted = !text.isBlank()
  confidence = ocr.confidence() > 0 ? 该值 : sense.ocr.baseline-confidence（默认 0.55）
  error（当 text 为空）= "OCR returned empty text (engine=...)"   ← 带上引擎名便于定位
```

`baselineConfidence` 的存在理由：OCR 基线准确率有限，给一个「可接受但不高」的默认置信度，
而不是让未上报置信度的结果变成 0 分。

**依赖 `TouchChannel`**：因为取图（无论 URL 还是本地文件）的**安全校验逻辑与触觉渠道完全相同**，
直接复用比复制一份 SSRF 与路径穿越防护更安全。

#### `NoseChannel.java` · 渠道 · 102 行

**嗅觉渠道**：舆情 / 情报采集，是唯一的「聚合型」渠道。

**情报源解析顺序**（`feeds()`）：

1. 环境变量 `SENSE_INTEL_FEEDS`（逗号分隔）；
2. 若为空，回退到 **R0 规则里 `channel=NOSE` 且已启用的规则的 `dataSource`**。

第二级回退是个实用设计：无需额外配环境变量，直接在 `application.yml` 的 R0 规则里写情报源即可。

**可用性定义**：`available()` 与 `healthy()` **都**返回 `!feeds().isEmpty()` ——
**没配情报源 = 渠道不可用**。这不违反「诚实上报」，反而正是它的体现：
渠道没有可用数据源时不该假装健康。

**`collect()` 的聚合语义**：

```text
sources = request.dataSource（若指定）否则 feeds()
  ├─ 空 → accepted=false, error="no intelligence feed configured (SENSE_INTEL_FEEDS)"
  └─ 逐个 touchChannel.fetchUrlContent(url)
       ├─ 成功且非空 → 追加内容，ok++
       └─ 失败/空     → failed++（记录 WARN，不中断循环）
结果：
  itemCount     = ok
  qualityScore  = ok / sources.size()     ← 用「成功率」当质量分
  accepted      = ok > 0
  confidence    = 0.75（成功时）
  全部失败时 error = "all intelligence feeds failed (N failed)"
```

**注意单源失败不会让整体失败** —— 部分成功即接受。这是聚合语义与单源语义的取舍。

#### `TasteChannel.java` · 渠道 · 75 行

**味觉渠道 = 数据质检**（模块内唯一「向内」的渠道）。

- **常量** `SAMPLE_LIMIT = 20`：每次抽样最多 20 条，避免全量扫描暂存区。
- **`available()` / `healthy()` 恒为 `true`** —— 它的数据源是系统内部（暂存区），不依赖外部能力。
- **`collect()` 流程**：

```text
samples = stagingStore.list(tenantId, null, 20)      ← status=null 表示不限状态
  ├─ 空 → accepted=false, error="no staged data to inspect"
  └─ 非空
       accepted = 样本中 stagingStatus==ACCEPTED 的条数
       avg      = 各样本 qualityGate.score(content) 的平均值
       content  = "质检复核：样本 N 条，通过 X 条，拒绝 Y 条，平均质量分 Z。"
       itemCount = samples.size()
       qualityScore = avg
       confidence   = 0.95（自家数据，置信度给高）
```

- **它是唯一会对「已暂存数据」重新打分的渠道**（调用 `qualityGate.score` 而非复用暂存时的分数），
  因此可作为质检策略变更后的**回归复核手段**。
- **异常处理**：捕获异常后返回 `accepted=false` + `error`，与其他渠道一致。

#### `AudioChannel.java` · 渠道 · 39 行

**预留渠道的范本**（R2-03）：Phase 2 只占位，Phase 3 接 ASR。

三个刻意的返回值：

| 成员 | 值 | 目的 |
|---|---|---|
| `available()` | `false` | 路由层不会选中它 |
| `healthy()` | `false` | 健康监控标记为 **DOWN** 并隔离 |
| `collect()` | `accepted=false`，`error="AUDIO channel is reserved for Phase 3 (ASR not wired)"` | 若被强行调用，如实说明原因 |

**正确做法就是「不实现但如实上报」** —— 而不是返回空内容假装成功。
Console 五感矩阵因此能对听觉渠道显示正确的「DOWN / 预留」状态。

### 5.3 collect 包 — 采集主流程

#### `CollectPipeline.java` · 组件 · 181 行

**全模块的心脏**，把渠道、标准化、质检、暂存、事件、死信、指标串成一条管道。
它依赖 **9 个组件**（构造器注入）：`ChannelRegistry`、`ChannelHealthMonitor`、`DataNormalizer`、
`QualityGate`、`StagingStore`、`SenseMetrics`、`SenseEventPublisher`、`DeadLetterStore`、`SenseProperties`。

**`collect(requested, request)` 的完整分支**（改这里前务必读懂）：

```text
① route(requested)
     └─ 无可用渠道 → failFast(...) 并直接返回

② collectWithRetry(channel, effective)   ← resilience4j 退避重试

③ normalizer.normalize(result, request)  → 五字段打标
   data.batchId   = result.batchId ?? 新 UUID
   data.degraded  = route.degraded          ← 是否走了降级渠道
   data.attempts  = 实际尝试次数
   data.collectTimeMs = 总耗时

④ 分支 A：result.accepted == false（采集失败）
     stagingStatus = REJECTED
     rejectReason  = result.error ?? "COLLECT_FAILED"
     confidence    = 0
     deadLetterId  = deadLetterStore.add(..., "COLLECT_FAILED 类原因", attempts, mode)

   分支 B：result.accepted == true
     qualityGate.apply(data)                ← 质检落状态
     if (stagingStatus == REJECTED)
        deadLetterId = deadLetterStore.add(..., "QUALITY_REJECTED:" + rejectReason, ...)

⑤ stagingStore.put(data)                   ← 无论 ACCEPTED 还是 REJECTED 都落暂存区
⑥ metrics.record(channel.type(), data)     ← 无论成败都记指标
⑦ if (stagingStatus == ACCEPTED) eventPublisher.publishCollected(data)
```

**三个容易误读的点**：

1. **被拒绝的数据也会落暂存区**（第 ⑤ 步无条件执行）。暂存区按状态分区存放，
   拒绝数据保留下来供人工复核与质检策略调优 —— 不是"丢弃"。
2. **质检失败与采集失败都进死信，但原因前缀不同**：`COLLECT_FAILED` 类 vs `QUALITY_REJECTED:` 前缀，
   便于运维区分「渠道坏了」与「数据脏了」。
3. **只有 ACCEPTED 才发事件**（第 ⑦ 步）。这是 Phase 3 入库的唯一入口条件。

**`collectWithRetry` 的重试配置**（resilience4j）：

| 配置 | 来源 | 默认 |
|---|---|---|
| `maxAttempts` | `sense.retry.max-attempts` | 3 |
| 退避间隔 | `IntervalFunction.ofExponentialBackoff(initialMs, multiplier, maxMs)` | 200ms × 2.0，封顶 2000ms |
| 重试条件 | `retryOnResult(未 accepted)` **和** `retryExceptions(Exception)` | 两种都重试 |

**双重包装的意义**：渠道的 `collect()` 理论上不抛异常（内部已捕获），
但 supplier 仍包了一层 try/catch 把异常转成 `CollectResult(accepted=false)` ——
保证重试机制**看到的永远是结果而非异常**，逻辑单一。

**`Retry` 实例按渠道名创建**（`"sense-" + channel.type().name()`），
`attempts` 用 `AtomicInteger` 统计真实尝试次数；重试耗尽后构造 `error="retry exhausted: ..."`。

**`route()` 的判定**（包内可见，便于测试）：

```text
primary = registry.find(requested)
if (primary != null && primary.available() && !healthMonitor.isIsolated(requested))
    → 直接用，degraded=false

否则按 fallbackOrder 找替代（顺序：TOUCH 优先，其余按枚举顺序，跳过 requested 自身）
    找到 → degraded=true（并用 WARN 记录"降级到 X"）
    找不到 → Route(null, false) → 触发 failFast
```

**`failFast`**：构造一个「五字段齐但全为最差值」的 `CollectedData`（`freshness=STALE`、`confidence=0`、
`qualityScore=0`、`stagingStatus=REJECTED`），登记死信、落暂存、记指标。
**它不抛异常** —— 调用方（HTTP 或 NATS）始终能收到结构化结果。

**内部 record**：`Route(channel, degraded)`、`Attempt(result, attempts)` —— 都只在本类内使用。

### 5.4 config 包

#### `SenseProperties.java` · 配置类 · 116 行

绑定前缀 `sense` 的全部配置（R2-01~R2-10）。用嵌套静态类分组，可读性好。

**顶层字段**：

| 属性 | 默认 | 说明 |
|---|---|---|
| `fileRoot` | `${user.dir}/data/sense-inbox` | 本地文件采集根目录（**沙箱边界**） |
| `maxBytes` | 2 MiB | 单次采集字节上限 |
| `allowedHosts` | `""`（空 = 不限制域名，**仍受 SSRF 私网拦截**） | 域名白名单，逗号分隔 |

**嵌套配置组**：

| 组 | 关键项 | 默认 | 需求 |
|---|---|---|---|
| `timeouts` | `connectMs` / `readMs` | 5000 / 10000 | — |
| `retry` | `maxAttempts` / `initialMs` / `multiplier` / `maxMs` | 3 / 200 / 2.0 / 2000 | R2-09 |
| `quality` | `threshold` / `minLength` / `minPrintableRatio` | 0.6 / 20 / 0.8 | R2-04/R2-10 |
| `ocr` | `enabled` / `url` / `timeoutMs` / `baselineConfidence` | true / `127.0.0.1:8000/api/nlp/ocr` / 20000 / 0.55 | R2-03 |
| `health` | `probeMs` / `degradeAfter` / `downAfter` | 30000 / **1** / **3** | R2-09 |
| `staging` | `backend`(auto/minio/local) / `minioEndpoint` / `bucket` / `localRoot` | auto / `127.0.0.1:9000` / `lifeform-staging` / `{user.dir}/data/sense-staging` | R2-10 |
| `r1` | `maxChannelsPerRound` / `maxItemsPerRound` | 3 / 10 | R2-06 |
| `r0` | `enabled` / `tickMs` / `rules[]` | true / 60000 / 空 | R2-05 |

**`health.degradeAfter = 1` 的含义**：连续失败 **1** 次即变为 DEGRADED，**3** 次变 DOWN。
即「一次失败就报警，三次失败就隔离」—— 敏感度高，适合演示与早期发现。
生产环境可按需调大。

**`R0.Rule`**：

| 字段 | 说明 |
|---|---|
| `id` | 规则标识（手动触发与日志用） |
| `channel` | 渠道名（字符串形式，运行时 `ChannelType.parse`） |
| `dataSource` / `query` / `tenantId` | 传给渠道的采集参数 |
| `intervalSeconds` | 周期（秒），**`0` 表示每 tick 都触发** |
| `enabled` | 是否启用 |
| `freshness` | 该规则采集内容的时效标记（写入 `params`） |

### 5.5 controller 包

#### `SenseController.java` · 控制器 · 124 行

**采集入口**（R2-02 / R2-06），前缀 `/api/sense`。

| 方法 | 路径 | 说明 |
|---|---|---|
| `collect` | `POST /collect` | 单渠道采集 |
| `command` | `POST /command` | R1 动态采集指令 |
| `health` | `GET /health` | 渠道健康总览 |

**`collect` 细节**：

- 请求体是 `Map<String,String>`（非强类型 DTO），字段：`data_source`、`query`、`channel`、`mode`。
- `tenantId` 一律取 `X-Tenant-Id` 请求头，**忽略请求体里的 `tenant_id`** ——
  与 `DataNormalizer` 的约定一致（多租户安全边界）。
- `params` 复用整个请求 Map（便于渠道读取自定义参数，如 `freshness`）。
- 响应在 `data.toMap()` 基础上**移除 `content`**（避免把正文全量回传），
  改为 `content_preview`（截断 200 字）+ `five_fields_complete` 布尔值。
  **这是刻意的响应瘦身**，也让 R2-04 的五字段完整性可被外部直接断言。
- 非法渠道名 → 捕获 `IllegalArgumentException` 转 `BizException(AGENT_BAD_REQUEST)`（400 而非 500）。

**`command` 细节**：

- 渠道来源三种写法都支持：`channels: [...]`（数组）、`channel: "..."`（单值）、都没有（默认触觉）。
- `params` 收集：**除 `data_source` / `query` / `channel` / `tenant_id` 之外的所有字符串字段**。
  这是个宽松约定 —— 新增自定义参数无需改控制器。
- `command_id` 为空时由 `R1DynamicHandler` 补 UUID。

**`health` 细节**：

- 遍历注册表，逐渠道取 `healthMonitor.status(...)`。
- **任一渠道非 UP 则整体 `DEGRADED`**，`healthy` 取布尔与。
- 响应含 `channel: "TOUCH"`（**Phase 1 兼容字段**，硬编码）与 `request_id`（随机 UUID）。
- 注意与 `SenseAdminController.channelsHealth()` 的区别：此接口是**服务健康语义**，
  后者是**运营观测语义**（含隔离状态与失败计数）。

#### `SenseAdminController.java` · 控制器 · 225 行

**运营观测数据面**（R-C02 感官视图 / R-C09 统一数据模块），前缀同为 `/api/sense`，
**字段统一 snake_case**，与前端 Console 的 Mock 数据结构一一同构。

| 方法 | 路径 | 说明 |
|---|---|---|
| `channels` | `GET /channels` | D6 五感矩阵：五个渠道卡片 |
| `channelDetail` | `GET /channels/{type}` | 下钻：单渠道详情（最近批次 + 指标） |
| `channelsHealth` | `GET /channels-health` | 健康快照（含隔离与恢复状态） |
| `stats` | `GET /stats` | 采集总量 / 质检通过率 / 近 6 分钟趋势 / 暂存后端 / 死信 / 事件 |
| `batches` | `GET /batches` | 暂存批次列表（按状态过滤） |
| `rollback` | `POST /batches/{batchId}/rollback` | 批次回滚（R2-10 验收点） |
| `rules` | `GET /rules` | R0 规则清单 |
| `runRule` | `POST /rules/{ruleId}/run` | 手动触发某条 R0 规则（演示/验收） |
| `deadLetters` | `GET /deadletters?limit=20` | 死信队列 |

- **租户过滤**：`channelDetail` / `batches` / `rollback` 三个接口按 `X-Tenant-Id` 过滤数据；
  `channels` / `channelsHealth` / `stats` / `rules` / `deadLetters` 是**全局视角**（不按租户切分）。
  这是有意的：运营看板关心全局健康，而下钻数据必须按租户隔离。
- **私有辅助方法**：`card(channel)`（组装单张渠道卡片）、`parse(type)`、`format(ts)`（时间格式化）、
  `preview(content)`（截断预览）。
- **与前端契约**：字段名（`sensors` / `up_count` / `degraded_count` / `summary` / `collect_rate_trend` /
  `staging` / `dead_letter` 等）与 `web/console` 的 Mock 数据结构同构，
  因此切换到真实 API 时前端无需改动。**改动响应字段前请先确认前端消费方**。

### 5.6 deadletter 包

#### `DeadLetterStore.java` · 组件 · 84 行

**死信队列**（R2-09）：重试耗尽或质检拒绝的记录登记处，供人工与后续阶段补偿。

| 方法 | 说明 |
|---|---|
| `add(channel, tenantId, dataSource, error, attempts, mode)` | 登记一条，**返回死信 ID**（写回 `CollectedData.deadLetterId`） |
| `list(limit)` | 最近若干条 |
| `size()` / `total()` | 当前条数 / 累计条数 |
| `stats()` | 统计信息 |

- **内存实现**：数据在进程内，重启即丢。Phase 2 可以接受（死信主要供当日排障与验收），
  但**不应作为长期审计手段**。
- **内部类名为 `Record`** —— 与 `java.lang.Record` 同名。在类内部引用 `Record` 时要留意可能的选择性遮蔽，
  新增代码时建议使用全限定名或显式导入。
- **协作**：被 `CollectPipeline` 在三条路径写入（采集失败、质检拒绝、无可用渠道的 failFast）。

### 5.7 dynamic 包 — R1 动态采集

#### `SenseCommand.java` · record · 23 行

R1 动态采集指令（BrainCommand 雏形 · R2-06），由大脑 / Console / 运维发起。

字段：`commandId`、`channels`（列表）、`dataSource`、`query`、`tenantId`、`params`。

静态工厂 `of(channel, dataSource, query, tenantId)`：把单个渠道名包装成列表
（渠道为空白时给空列表 —— 后续由 `R1DynamicHandler` 补默认值）。

**设计意图**：字段形状刻意贴近「大脑下发的采集意图」，
将来接入真正的 `BrainService`（契约已在 `proto/brain/v1/brain.proto`）时，
gRPC 消息可以直接映射到本 record。

#### `R1DynamicHandler.java` · 组件 · 111 行

**R1 指令处理器**（R2-06）：指令 → 渠道选择 → 逐个采集 → 回传批次摘要。

**`dispatch(command)` 流程**：

```text
① 补默认值：commandId（空则 UUID）、tenantId（空则 "default"）
② resolveChannels(command) → 渠道集合（含上限保护，见下）
③ 逐渠道：构造 CollectRequest
     mode = "R1_DYNAMIC"
     params 追加 command_id
     pipeline.collect(type, req)
     收集结果项：channel / batch_id / source_channel / staging_status /
                 item_count / quality_score / confidence / degraded / reject_reason
④ 汇总响应：command_id / tenant_id / channel_count / batch_ids[] / results[] / duration_ms / mode
```

**`resolveChannels` 的三条规则**（防采集风暴的核心）：

| 规则 | 行为 |
|---|---|
| 显式指定优先 | 解析 `command.channels()`，**无法识别的渠道名跳过并记 WARN**（不中断整条指令） |
| 未指定则默认 | 空集合 → 默认 `TOUCH` |
| **单轮上限** | 超过 `sense.r1.max-channels-per-round`（默认 3）则**截断**并记 WARN |

用 `LinkedHashSet` 保证顺序稳定且去重。

**`dispatchJson(payload)`**：从 NATS 总线收到的 JSON 字符串入口。
解析失败时返回 `{"status":"error","message":"..."}`（**消息中的双引号会被替换为单引号**，
避免破坏 JSON 结构）—— 即总线调用永远能拿到可解析的响应。

**协作**：被 `SenseController.command`（HTTP）与 `NatsBusServer.handleRequest`（总线）两处调用。

### 5.8 event 包

#### `SenseEventPublisher.java` · 组件 · 95 行

**采集事件发布**（Phase 2 → Phase 3 入库消费的桥梁）。

- **主题常量** `TOPIC = "lifeform.sense.collected"` —— 与 Phase 1 的 `lifeform.{domain}.{event}` 命名一致。
- **初始化**：`@PostConstruct` 连接 Kafka（`KAFKA_BOOTSTRAP`，默认 `127.0.0.1:9092`），
  `acks=all`，并设置 **`max.block.ms=3000`** —— 这个值很关键：Kafka 不可达时最多阻塞 3 秒，
  避免把采集请求拖死（默认 60 秒会造成明显卡顿）。
- **降级**：初始化失败只记 WARN，`producer == null` 时 `publishCollected` 直接返回 `false`。
  **事件丢失不影响采集成功** —— 这是有意的可用性取舍。
- **事件负载**（snake_case，与 Console 字段风格一致）：
  `batch_id` / `tenant_id` / `source_channel` / `timestamp` / `freshness` / `confidence` /
  `item_count` / `quality_score` / `staging_status` / `staging_ref` / `mode`。
  **注意负载里没有 `content`** —— 只传元数据与暂存引用，正文由 Phase 3 按 `staging_ref` 去暂存区取。
  这是刻意的解耦：事件轻量化，避免大正文塞进消息通道。
- **可观测**：`publishedCount()`（累计成功数）、`available()`、`stats()`
  （返回 `topic` / `available` / `published`，供 `/api/sense/stats` 展示）。
- **`@PreDestroy close()`** 关闭 producer。

### 5.9 health 包

#### `ChannelHealthMonitor.java` · 组件 · 119 行

**渠道健康治理**（R2-09）：探针 → 降级 → 隔离 → 自动恢复。

**状态机**：

```text
                    连续失败 ≥ degradeAfter(默认 1)
        UP ─────────────────────────────────▶ DEGRADED
         ▲                                      │ 连续失败 ≥ downAfter(默认 3)
         │                                      ▼
         └──────────── 探针成功 ────────────  DOWN（隔离：不参与路由）
```

**关键实现细节**：

| 细节 | 说明 |
|---|---|
| `healthy() && available()` 才判定成功 | 预留渠道即使 `healthy()` 抛异常也算失败 |
| **不可用渠道直接 DOWN** | `if (!channel.available() \|\| fail >= downAfter) now = DOWN` —— `AudioChannel` 一次探测即 DOWN |
| 探针异常被吞掉 | `catch (Exception e) { ok = false; }` —— 探针本身出问题不该让监控线程挂掉 |
| 恢复计数 | 从非 UP 回到 UP 时 `recoveries++` 并记录 `lastRecoverAt`，同时**清零失败计数** |
| 定时探针 | `@Scheduled(fixedDelayString = "${sense.health.probe-ms:30000}", initialDelayString = "${sense.health.initial-delay-ms:10000}")` |
| 启动即探 | `@PostConstruct init()` 调一次 `probeAll()`，避免启动后 10 秒内状态未知 |

**注意**：`@Scheduled` 上的默认值（30000 / 10000）是**硬编码在注解里的**，
而 `SenseProperties.Health` 也有同名字段（`probeMs`、`degradeAfter`、`downAfter`）。
`probeMs` 通过 yml 占位符 `${sense.health.probe-ms:30000}` 生效（**yml 未配时用注解默认值**），
`degradeAfter` / `downAfter` 从 `SenseProperties` 读取。

**方法**：

| 方法 | 说明 |
|---|---|
| `probe(channel)` | 探测单个渠道，返回新状态 |
| `probeAll()` | 探测全部（定时任务与启动时调用） |
| `status(type)` | 当前状态（默认 `UP`，未探测过也返回 UP） |
| `isIsolated(type)` | `status == DOWN` —— **路由层据此跳过渠道** |
| `snapshot()` | 每个渠道的 `status` / `isolated` / `consecutive_failures` / `last_probe_at` / `last_recover_at` / `available` |
| `allStatuses()` | 状态映射副本 |
| `recoveries()` | 累计恢复次数 |

**`status()` 默认返回 UP 而非未知状态**：这是刻意的宽松设计 —— 未探测过的渠道不应被误判为故障而拦在路由外。

### 5.10 metrics 包

#### `SenseMetrics.java` · 组件 · 125 行

**采集指标聚合**，是 Console 感官视图（R-C02 / D6 五感矩阵）的数据来源。

- **按渠道维护 `Counter`**：累计批次、通过数、拒绝数、条目数、最近记录时间、连续失败数。
- **核心方法**：

| 方法 | 说明 |
|---|---|
| `record(type, data)` | 登记一次采集结果（`CollectPipeline` 在成功路径与 failFast 路径都会调用） |
| `counter(type)` | 取某渠道计数器 |
| `totalBatches()` | 总批次 |
| `overallQualityPassRate()` | 整体质检通过率 |
| `summary()` | 汇总（供 `/api/sense/stats`） |
| `rateTrend()` | **近 6 分钟按分钟采集体量**（供前端趋势图） |

- **`Counter` 的派生指标**：`passRate()`（通过率）、`recentRatePerMinute()`（近期速率）、
  `consecutiveFailures()`（连续失败）。
- **内存实现**：进程内累计，重启归零。这是观测指标而非审计数据，可接受。
- **`rateTrend()` 的 6 分钟窗口是硬编码的**，前端 `collect_rate_trend` 图表与之绑定；
  若要调整窗口需同时改前端。

### 5.11 model 包

#### `CollectedData.java` · 模型 · 78 行

**采集数据模型**（R2-04 五字段标准化打标）。整条管道传输的就是这个对象。

**两个枚举**：

| 枚举 | 取值 |
|---|---|
| `StagingStatus` | `ACCEPTED` / `REJECTED` / `PENDING`（默认 `PENDING`） |
| `Freshness` | `REALTIME` / `NEAR_REALTIME` / `BATCH` / `STALE` |

**五字段**（R2-04 的核心）：`sourceChannel`、`tenantId`、`timestamp`、`freshness`、`confidence`。

**其余字段**：`batchId`、`content`、`title`、`mode`、`stagingStatus`、`qualityScore`、`itemCount`、
`rejectReason`、`collectTimeMs`、`stagingRef`、`attempts`（默认 1）、`degraded`、`deadLetterId`。

**三个方法**：

| 方法 | 说明 |
|---|---|
| `fiveFieldsComplete()` | 五字段完整性校验（R2-04 验收口径：缺失率 = 0）。注意 `confidence >= 0` 也算"完整"，因为 -1 是未上报哨兵值 |
| `missingFields()` | 返回缺失字段的明细 Map（键为 snake_case 字段名） |
| `toMap()` | 转 snake_case Map（供 HTTP/NATS 响应）。**注意这里会把 `content` 一起输出** —— 需要瘦身的调用方自行移除 |

**`toMap()` 的字段顺序**：用 `LinkedHashMap` 保持声明顺序，让响应体稳定可读（也便于前端调试）。

### 5.12 nats 包

#### `MinimalNatsClient.java` · 客户端 · 143 行

**自研的极简 NATS 协议实现**（不是 jnats 封装）。

**为什么自研而不是用 jnats**：类注释写明原因 ——
**jnats 在 Spring Boot fat jar 环境下消息读取线程会失效**。`session-manager` 用的是 jnats
（普通 jar 启动），而本模块必须自研以规避该问题。

**实现方式**：直接实现 NATS core 协议（TCP + 文本帧解析），
包含 `connect()`（建立连接并启动读循环，**阻塞调用方直到连接完成**）、
`subscribe(subject)`、`publish(subject, payload)`、`readLoop`（解析 `MSG` / `PING` 帧）、`close()`。

**内部接口 `MessageHandler`**：收到消息时的回调契约
（由 `NatsBusServer` 传入方法引用 `this::handleRequest`）。

**维护提示**：本类只实现了 NATS core 的最小子集（无 TLS、无认证、无 JetStream）。
若将来需要这些能力，应评估「引入 jnats 并解决线程问题」与「扩展自研实现」的成本取舍。

#### `NatsBusServer.java` · 组件 · 67 行

**总线应答服务**（R1-04 脊柱神经信号接收端 / R2-06 R1 动态采集指令通道）。

- **实现 `ApplicationRunner`**：应用启动后（`run` 回调）才建立连接与订阅，避免阻塞容器启动。
- **订阅两个主题**（**具体主题而非通配**）：

| 主题 | 处理 |
|---|---|
| `lifeform.rpc.sense.ping` | 返回 `{"service":"sense-service","status":"pong","channel":"TOUCH"}` |
| `lifeform.rpc.sense.collect` | 转给 `R1DynamicHandler.dispatchJson(payload)` 执行采集 |

- **为什么用具体主题**：类注释写明 —— **Docker NATS 环境下通配订阅投递失效**，用具体主题绕过。
  这是环境适配的取舍，代价是新增 RPC 主题必须在此显式登记。
- **解析 URL**：从 `NATS_URL`（默认 `nats://127.0.0.1:4222`）手工拆分 host/port
  （`replace("nats://","").split(":")`）—— **不支持带认证信息或路径的 URL**。
- **降级**：连接失败只记 WARN，服务照常运行（总线是可选依赖）。
- **应答**：仅当存在 `replyTo` 时才 `publish` 回包，并用 `abbreviate` 把日志中的响应截断到 240 字。
- **协作**：`session-manager` 的 `create` 会话时会向 `lifeform.rpc.sense.ping` 发请求，
  由本类应答 —— 这就是「会话创建返回 `bus_channel: nats`」的来源。

### 5.13 normalize 包

#### `DataNormalizer.java` · 组件 · 68 行

**五字段标准化打标**（R2-04）：把渠道的 `CollectResult` 转成五字段完整的 `CollectedData`。

**五字段的赋值规则**（每一处都有明确取舍）：

| 字段 | 规则 |
|---|---|
| `sourceChannel` | 取 `result.sourceChannel`；为空则 `"UNKNOWN"` |
| `tenantId` | **只取 `request.tenantId`**（网关注入），空白则 `"default"` |
| `timestamp` | `System.currentTimeMillis()` —— **标准化时刻**，不是采集开始时刻 |
| `freshness` | 渠道自报优先 → `request.params["freshness"]` → 按 `mode` 推断（`R0_DEFAULT`→NEAR_REALTIME，其余→REALTIME） |
| `confidence` | 渠道自报（≥0 时）；若为 -1 哨兵值则回退到 `result.qualityScore`；最后**钳制到 [0,1] 并保留 4 位小数** |

**关于租户：注释明确写了「只信任网关注入的租户，忽略请求体」**。这是多租户安全边界在感官层的落点。

**`freshness` 的三级回退**是实用设计：渠道可以自报最准确的时效（如 `TouchChannel` 对文件报 `BATCH`），
也可以由 R0 规则通过 `params.freshness` 指定，都没有时按采集模式推断。

**辅助方法**：`tenantIdOf(request)`、`resolveFreshness(result, request)`、`fiveFieldsComplete(data)`、`getProperties()`。

**注意 `getProperties()`**：暴露配置对象但当前无人调用 —— 属于预留的测试/扩展入口。

### 5.14 quality 包

#### `QualityGate.java` · 组件 · 116 行

**质检门槛**（R2-10 暂存前置质检 / R2-04 数据质量）。两层判定 + 单一事实来源（见 §3.2）。

**硬规则 `rejectReason(content)`**（按顺序短路判定）：

| 顺序 | 条件 | 拒绝原因 |
|---:|---|---|
| 1 | null 或空白 | `EMPTY_CONTENT` |
| 2 | 可打印字符占比 < `minPrintableRatio`(0.8) | `ENCODING_GARBLED` |
| 3 | 重复乱码 | `REPETITIVE_GARBAGE` |
| 4 | 长度 < `minLength`(20) | `BELOW_QUALITY_THRESHOLD` |
| 5 | 加权评分 < `threshold`(0.6) | `BELOW_QUALITY_THRESHOLD` |
| — | 全部通过 | `null`（放行） |

**`REPETITIVE_GARBAGE` 的判定**（`isRepetitiveGarbage`，两个条件任一成立）：

- **单字符连续重复 ≥ 12 次**；
- **长度 ≥ 40 且去空白后不同字符数 ≤ 2**（如 `abababab...`）。

**注意**：内容短于 12 字符时**直接返回 false**（不做重复判定），因为短文本的重复没有统计意义。

**软评分 `score(content)`**（0~1 加权）：

| 权重 | 项 | 值 |
|---|---|---|
| 0.45 | `W_CONTENT` | 基础分（内容非空即得） |
| 0.20 | `W_LENGTH` | `lengthFactor` = `min(1, length / minLength)`（**达到 minLength 即饱和为 1**） |
| 0.20 | `W_PRINTABLE` | 可打印字符占比 |
| 0.15 | `W_DENSITY` | `informationDensity` = 非空白字符中「字母或数字」的占比 |

**提前返回 0 的三种情况**（评分阶段）：空内容、长度不足、重复乱码。
即这些情况连评分都不算 —— 与硬规则判定保持一致。

**`score()` 与 `rejectReason()` 的关系**：`rejectReason` 的第 5 条会**调用 `score()`**。
因此两者是「`rejectReason` 包含 `score`」的关系，而不是并列的两套逻辑。
这是 §3.2 那个缺陷修复后的形态。

**`apply(data)`**：对 `CollectedData` 执行质检并落状态。
注意 —— **无论接受还是拒绝，都会写入 `qualityScore`**（拒绝时分数保留，便于分析「差多远」）。
这正是 `QualityGateTest.applyMarksRejectedAndRecordsReason` 与 `applyMarksAcceptedWithScore` 两个用例覆盖的行为。

**三个静态工具方法**：`printableRatio`（`\n\r\t` 视为可打印）、`isRepetitiveGarbage`、`informationDensity` ——
都声明为 `static`（包内可见），便于测试直接调用。

### 5.15 schedule 包

#### `R0Scheduler.java` · 组件 · 133 行

**R0 默认采集调度**（R2-05）：规则驱动 + 增量同步语义。

**调度机制**：

- `@Scheduled(fixedDelayString = "${sense.r0.tick-ms:60000}", initialDelayString = "${sense.r0.initial-delay-ms:30000}")`
  → 每 tick 检查一次全部规则。
- **每条规则独立计时**：`lastRunAt`（`ConcurrentHashMap<ruleId, 时间戳>`）+ `due(rule)` 判定
  `now - last >= intervalSeconds*1000`。
- **`intervalSeconds <= 0` 的语义**：间隔视为 0 → **每 tick 都触发**。
- 「增量同步」的含义：**只有到期的规则才触发**，未到期的跳过，不做无谓采集。

**`run(rule, scope)` 的执行内容**：

```text
构造 CollectRequest：
  dataSource = rule.dataSource
  query      = rule.query
  tenantId   = rule.tenantId
  mode       = "R0_DEFAULT"
  params     = { freshness, scope, rule_id }
pipeline.collect(ChannelType.parse(rule.channel), request)
  ↓
记录 lastRunAt、triggered++
写入调度日志 record：rule_id / scope / channel / batch_id / staging_status /
                     item_count / quality_score / duration_ms / at
```

**`scope` 取值**：`"R0_SCHEDULED"`（定时触发）或 `"MANUAL"`（手动触发）—— 便于区分演示与真实调度。

**调度日志**：`ArrayDeque` 保留最近 **`MAX_LOG = 200`** 条，
**环形淘汰**（满了 `removeFirst()`），`synchronized` 保护，`log(limit)` 返回倒序（最新在前）。
用途是 Console / 排障，不落盘。

**注意一个实现细节**：`lastRunAt.put(rule.getId(), started)` 用的是**开始时刻**而非结束时刻 ——
所以规则间隔是「从上次开始到本次开始」，长时间执行的规则会有重叠可能（当前为串行循环，不会并发）。

**方法**：

| 方法 | 说明 |
|---|---|
| `tick()` | 定时入口（`r0.enabled=false` 时直接返回） |
| `run(rule, scope)` | 执行单条规则 |
| `runNow(ruleId)` | 手动触发（规则不存在抛 `IllegalArgumentException`） |
| `rules()` | 规则清单（含 `last_run_at`） |
| `log(limit)` | 调度日志（倒序、截断） |
| `stats()` | `enabled` / `tick_ms` / `rules` 数 / `triggered` 累计 / `last_tick_at` |

### 5.16 security 包

#### `OutboundGuard.java` · 工具类 · 84 行

**SSRF 出站守卫**（R2-02）。纯静态工具类（`final` + 私有构造，不可实例化）。

**`assertPublicHttpUrl(uri, allowedHosts)` 的四层校验**：

| 顺序 | 校验 | 失败信息 |
|---:|---|---|
| 1 | scheme 必须是 http(s) | `only http/https URLs are allowed` |
| 2 | host 非空 | `URL host is required` |
| 3 | 若 `allowedHosts` 非空，host 必须在白名单内（**大小写不敏感**） | `host is not in SENSE_ALLOWED_HOSTS` |
| 4 | **解析出所有 IP**（`getAllByName`），逐个检查是否被禁 | `private or local network targets are forbidden` |

**第 4 步的禁判条件**（覆盖完整）：

| 判定 | 覆盖范围 |
|---|---|
| `isAnyLocalAddress()` | `0.0.0.0` 等 |
| `isLoopbackAddress()` | `127.0.0.0/8`、`::1` |
| `isLinkLocalAddress()` | `169.254.0.0/16`、`fe80::/10` |
| `isSiteLocalAddress()` | `10/8`、`172.16/12`、`192.168/16` |
| `isMulticastAddress()` | 组播地址 |
| `isExtendedBlockedAddress()` | **自定义补充**：IPv6 ULA `fc00::/7` + IPv4 **CGNAT `100.64/10`** |

**为什么需要 `getAllByName` 遍历**：一个域名可能解析出多个 A/AAAA 记录，
只检查第一个会被「DNS 多记录绕过」攻破（第一个是公网 IP，第二个是内网 IP）。**必须全部检查。**

**`assertInternalHttpUrl(uri)`**：只校验 http(s) + host 可解析。
**只能用于配置来源的可信端点**（如 OCR 服务在本机 `127.0.0.1`）——
注释明确警告「禁止用于用户提交的 data_source」。

**`parseAllowedHosts(String)`**：逗号分隔 → 去空白 → 小写 → 不可变 `Set`。

**异常类型**：全部抛 `BizException(AGENT_BAD_REQUEST)` —— 最终映射为 HTTP 400，
语义上「请求的 URL 不合法」比 500 更准确。

### 5.17 staging 包

#### `StagingBackend.java` · 接口 · 34 行

暂存后端抽象（R2-10）。方法：`name()`、`available()`、`put(data)`、`get(batchId, tenantId)`、
`list(tenantId, status, limit)`、`rollback(batchId, tenantId)`、`stats()`。

**设计意图**：Phase 3 消费暂存区数据入库，因此需要一个「比内存可靠、又不是最终知识库」的中间层。
用接口隔离后端，使 MinIO 缺失时可自动降级到本地文件而不影响上层。

#### `StagingStore.java` · 组件 · 68 行

**暂存入口**：按可用性自动选后端，并在主后端失效时**实时降级**。

**`active()`**：`minioBackend.available() ? minio : local` —— 每次调用都重新判定，
因此 MinIO 中途挂掉能立即切换（不需要重启）。

**五个方法的降级策略**（注意差异）：

| 方法 | 主后端失败时 |
|---|---|
| `put` | `ref == null` **且**主后端不是 local **且** local 可用 → 写入 local 并记 WARN |
| `get` | 主后端取不到 → 去 local 再试 |
| `list` | 主后端**返回空** → 去 local 再试 |
| `rollback` | 主后端返回 false → 去 local 再试 |
| `stats` / `backendName` | 返回当前 active 信息 + 两个后端的详细统计 |

**`list` 的降级条件是「返回空」而非「异常」** —— 因为接口约定用空列表表示无数据。
副作用是：真正无数据时会多查一次 local，可接受。

**`stats()` 结构**：`{ active_backend, minio: {...}, local: {...} }` ——
让运维能同时看到两个后端的状态，便于判断降级是否发生。

#### `MinioStagingBackend.java` · 后端 · 188 行

**MinIO 暂存后端**（R2-10 主实现）。

- **对象键格式**（注释给出）：`staging/{tenant}/{STATUS}/batch-{batchId}/item-{n}.json`
  —— **状态直接体现在路径中**，因此「按状态查询」是前缀列举，效率高且隔离清晰。
- **配置**：`sense.staging.minio-endpoint` / `minio-access-key` / `minio-secret-key` / `bucket`。
- **`available()`**：凭据与端点齐备且能连通。
- 方法：`init()`（`@PostConstruct`，建桶）、`put` / `get` / `list` / `rollback` / `stats`，
  私有 `readObject(key)` / `keyOf(data, index)` / `bucket()` / `tenant(tenantId)`。
- **`rollback`**：按 `batch-{batchId}/` 前缀删除该批次全部对象。

#### `LocalStagingBackend.java` · 后端 · 188 行

**本地文件系统暂存后端**（MinIO 不可用时的降级实现）。

- **根目录**：`sense.staging.local-root`（默认 `{user.dir}/data/sense-staging`）。
- **方法**与 MinIO 后端一一对应，保证两个实现语义一致。
- **`rollback` 的实现要点（含一个修复过的缺陷）**：先用 `Files.walk` 找出该批次的所有路径，
  **必须按 `Comparator.reverseOrder()` 逆序删除**。
  原因：按正序删除会先删父目录、再删子项，触发
  `DirectoryNotEmptyException` 导致回滚返回 false。逆序删除从最深的子项开始，才能干净移除整个批次。
- **辅助**：`tenant(data)` / `status(data)` / `fromMap(map)` / `str(v)` / `num(v)` ——
  负责 JSON ↔ `CollectedData` 的转换与类型容错。
- **`init()`**：`@PostConstruct` 确保根目录存在。

### 5.18 util 包

#### `HttpClients.java` · 工具类 · 24 行

**统一 HTTP 客户端构建器**（缺陷 D-1 修复的落点）。

```java
public static HttpClient.Builder builder(Duration connectTimeout) {
    return HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)   // ← 关键
            .connectTimeout(connectTimeout);
}
```

- **为什么必须有**：`HttpClient` 默认协商 HTTP/2，对明文连接会先发 `Upgrade: h2c` 握手；
  uvicorn(h11) 不兼容该升级，**丢弃请求体**导致 FastAPI 报 422。
- **返回 `Builder` 而非 `HttpClient`**：让调用方能继续追加配置
  （如 `TouchChannel` 追加 `followRedirects(NEVER)`）。
- **约定**：渠道侧（取图、OCR 调用、URL 抓取）**一律经本类构建**。
- **回归守卫**：`HttpClientsTest.buildsHttp11ClientWithConnectTimeout` 断言版本为 HTTP/1.1。

#### `TextExtractor.java` · 工具类 · 117 行

**文本抽取与编码处理**（R2-02）。纯静态工具类。

**`decode(body, contentType)` 的五级编码嗅探**（逐级尝试，成功即返回）：

| 顺序 | 依据 |
|---:|---|
| 1 | HTTP 头 `Content-Type` 里的 `charset=` |
| 2 | **BOM**（UTF-8 `EF BB BF` / UTF-16BE `FE FF` / UTF-16LE `FF FE`） |
| 3 | HTML `<meta charset=...>`（只在前 4096 字节里找，用 ISO-8859-1 读取以避免二次解码问题） |
| 4 | **UTF-8 严格解码**（`CodingErrorAction.REPORT`） |
| 5 | **GBK 回退** |
| 兜底 | 用 UTF-8 宽松解码（不抛异常） |

**「严格解码」是关键**：用 `REPORT` 而非默认的 `REPLACE` 才能**判定失败并继续下一级尝试**；
若用默认行为，任何字节序列都会"成功"解码成乱码，嗅探逻辑就失效了。

**`extract(raw)` 的正文提取**：

```text
判断是否 HTML（doctype / <html / <body / <p> / <div 任一命中）
  ├─ 不是 → 原样返回 clean(raw)（纯文本不做处理）
  └─ 是
       ① 取 <title> 内容
       ② 用正则移除 <script> <style> <noscript> <svg> <head> 整块
       ③ jsoup 解析，移除导航/页脚/侧栏/表单/按钮/iframe/广告/评论等选择器
       ④ 取 body.text()；title 为空时回退 doc.title()
```

**`clean(text)` 的规范化**：`\u00a0`（不换行空格）→ 普通空格；
`\t\x0B\f\r` → 空格；连续空白压成单空格；逐行去首尾空白。

**`Extracted` record**：`(title, content)` —— 标题用于暂存/展示，正文用于质检与入库。

**已知的测试命名瑕疵**：`TextExtractorTest` 中有一个用例名为 `decodesGbkWitoutHeader`
（应为 `Without`）。仅影响可读性，功能无影响。可在后续代码整理时一并改名。

### 5.19 common 包

#### `ErrorCode.java` · 枚举 · 31 行

统一错误码（R1-08），响应体格式 `{"code","message","details"}`。
**取值与 `session-manager` 的同名枚举完全一致**（11 个：`AGENT_BAD_REQUEST`、`AGENT_NOT_FOUND`、
`AGENT_UNAUTHORIZED`、`AGENT_FORBIDDEN`、`AGENT_METHOD_NOT_ALLOWED`、`AGENT_CONFLICT`、`AGENT_DUPLICATE`、
`AGENT_TIMEOUT`、`AGENT_BUS_UNAVAILABLE`、`AGENT_UPSTREAM_UNAVAILABLE`、`AGENT_INTERNAL_ERROR`）。

**维护提示**：这是**跨模块的重复定义**（`session-manager` / `sense-service` / `body-service` **三份**），
新增错误码需三处同步。一致性由人工约定保障 —— 改动时请一并检查另外两处。

#### `BizException.java` · 异常 · 34 行

携带 `ErrorCode` + `details` 的业务异常。与 `session-manager` 版本**结构相同但少了「携带 cause」的构造器** ——
本模块没有跨服务调用的降级需求，因此未保留该构造器。

**注意一个用法细节**：`OutboundGuard` 抛 `BizException(ErrorCode.AGENT_BAD_REQUEST, "message")`
（两参数构造），与该类的构造器签名匹配。

#### `GlobalExceptionHandler.java` · 切面 · 93 行

`@RestControllerAdvice`，把异常统一转为 `{"code","message","details"}`。
**错误码 → HTTP 状态码的映射与 `session-manager` 完全一致**（见
[04-session-manager](04-session-manager.md) §4.12 的映射表），此处不重复。

处理的异常类型：`BizException`、`MethodArgumentNotValidException`、`IllegalArgumentException`、
**路由层三类（`NoResourceFoundException` / `NoHandlerFoundException` → 404，
`HttpRequestMethodNotSupportedException` → 405，`HttpMediaTypeNotSupportedException` → 415）**、
兜底 `Exception`（500 且只记日志，不泄露内部消息）。

> **为什么路由层要单独分流**（2026-09-18 修）：此前路由层异常落进兜底分支返回 500，
> 使「接口不存在」与「服务内部故障」在客户端不可区分。三服务同源缺陷，已同步修复，
> 横切口径见 [07](07-运行时配置与横切约定.md) §5.1。

## 6. HTTP 端点汇总

| 方法 | 路径 | 用途 | 租户过滤 |
|---|---|---|---|
| POST | `/api/sense/collect` | 单渠道采集 | 写：按 header 打标 |
| POST | `/api/sense/command` | R1 动态采集指令 | 写：按 header 打标 |
| GET | `/api/sense/health` | 渠道健康总览（服务健康语义） | 全局 |
| GET | `/api/sense/channels` | 五感矩阵卡片 | 全局 |
| GET | `/api/sense/channels/{type}` | 渠道下钻详情 | **按租户** |
| GET | `/api/sense/channels-health` | 健康快照（含隔离/恢复） | 全局 |
| GET | `/api/sense/stats` | 采集统计与趋势 | 全局 |
| GET | `/api/sense/batches` | 暂存批次列表 | **按租户** |
| POST | `/api/sense/batches/{batchId}/rollback` | 批次回滚 | **按租户** |
| GET | `/api/sense/rules` | R0 规则清单 | 全局 |
| POST | `/api/sense/rules/{ruleId}/run` | 手动触发 R0 规则 | — |
| GET | `/api/sense/deadletters` | 死信队列 | 全局 |

经网关访问需带 `Authorization: Bearer <token>`（`/actuator` 与 `/api/auth` 除外）。

## 7. 配置项

`application.yml` 全量配置见 §5.4 的 `SenseProperties` 表格。环境变量映射：

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `SENSE_FILE_ROOT` | `data/sense-inbox` | 本地文件采集根目录（沙箱边界） |
| `SENSE_ALLOWED_HOSTS` | 空 | 域名白名单（逗号分隔） |
| `OCR_ENABLED` | `true` | 视觉渠道 OCR 开关 |
| `OCR_URL` | `http://127.0.0.1:8000/api/nlp/ocr` | OCR 服务地址 |
| `STAGING_BACKEND` | `auto` | `auto` / `minio` / `local` |
| `MINIO_ENDPOINT` / `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` / `MINIO_BUCKET` | `127.0.0.1:9000` / 空 / 空 / `lifeform-staging` | 暂存后端 |
| `STAGING_LOCAL_ROOT` | `data/sense-staging` | 本地暂存根目录 |
| `R0_ENABLED` / `R0_TICK_MS` | `true` / `60000` | R0 调度 |
| `NATS_URL` | `nats://127.0.0.1:4222` | 总线地址（**只读环境变量**） |
| `KAFKA_BOOTSTRAP` | `127.0.0.1:9092` | 事件通道（**只读环境变量**） |
| `SENSE_INTEL_FEEDS` | 空 | 嗅觉渠道情报源（逗号分隔） |
| `NACOS_ADDR` / `NACOS_IP` / `GRPC_PORT` / `OTLP_ENDPOINT` | `127.0.0.1:8848` / `127.0.0.1` / `9093` / `localhost:4318/v1/traces` | 基础设施 |

**注意 `NACOS_IP` 显式设为 `127.0.0.1`**（其他模块不设）—— 原因是本机多网卡时，
Nacos 可能注册错误的网卡 IP，导致 `session-manager` 通过 NATS 之外的方式访问不到。

## 8. 测试基线（53 个用例）

### `TouchChannelTest` · 12 个

| 用例 | 守卫的约定 |
|---|---|
| `blocksLoopbackUrl` / `blocksPrivateNetworkUrl` / `blocksIpv6UniqueLocalUrl` / `blocksCarrierGradeNatUrl` | **SSRF 四类拦截**（loopback / 私网 / IPv6 ULA / CGNAT） |
| `acceptsPlainText` | 纯文本路径 |
| `readsFileInsideFileRoot` | file-root 内正常读取 |
| `readsHtmlFileWithBodyExtraction` | **`file://` 前缀解析 + HTML 正文提取**（修复过的缺陷） |
| `blocksPathTraversal` | **路径穿越拦截**（`../` 逃逸） |
| `reportsMissingFileAsFailure` | 文件不存在 → 失败而非抛异常 |
| `rejectsBlankDataSource` | 空 dataSource 拒绝 |
| `registrySupportsPluggableChannel` | **可插拔性**：自定义渠道注册后能被 `ChannelRegistry` 发现 |
| `channelTypeParsesChineseLabel` | `ChannelType.parse` 支持中文名 |

`registrySupportsPluggableChannel` 是 R2-01 的直接验收用例 —— 它定义一个匿名渠道实现，
验证「不改核心代码即可扩展」。

### `QualityGateTest` · 7 个

`acceptsNormalContent`、`rejectsEmptyContent`、`rejectsRepetitiveGarbage`、
`rejectsControlCharacterGarbage`、`rejectsShortLowQualityContent`、
`applyMarksRejectedAndRecordsReason`、`applyMarksAcceptedWithScore`。

后两个用例覆盖 `apply` 的两条分支（含「拒绝时也写 qualityScore」这一行为）。

### `CollectPipelineTest` · 6 个

| 用例 | 守卫的约定 |
|---|---|
| `retriesUntilSuccessAndRecordsAttempts` | 退避重试 + `attempts` 计数 |
| `exhaustedRetryGoesToDeadLetterAndIsRejected` | **重试耗尽 → 死信 + REJECTED** |
| `lowQualityContentIsInterceptedBeforeStaging` | **质检在暂存之前**（低质数据不进 ACCEPTED） |
| `isolatedChannelDegradesToAvailableChannel` | **渠道隔离 → 路由降级** |
| `noAvailableChannelFailsFastAndRegistersDeadLetter` | 无可用渠道 → failFast + 死信（不抛异常） |
| `acceptedDataIsStagedAndCountedInMetrics` | 成功路径：落暂存 + 记指标 |

测试用 `ScriptedChannel`（可编排前 N 次失败的假渠道）精确控制重试场景 —— 这比用 Mockito 桩更直观。

### `DataNormalizerTest` · 6 个

`producesFiveCompleteFields`（R2-04 核心验收）、`usesChannelReportedConfidenceWhenPresent`、
`r0ModeInfersNearRealtimeFreshness`、`explicitFreshnessFromChannelWins`、
`blankTenantFallsBackToDefault`、`toMapUsesSnakeCaseKeys`。

### `StagingStoreTest` · 6 个

`degradesToLocalBackendWhenMinioUnavailable`（**降级能力**）、`storesAndListsAcceptedData`、
`keepsRejectedDataOutOfAcceptedArea`（**状态分区隔离**）、
`rollbackRemovesWholeBatch`（**批次回滚**，覆盖修复过的逆序删除缺陷）、
`tenantIsolationOnRead`（**租户隔离**）、`statsReportsBothBackends`。

### `ChannelHealthMonitorTest` · 4 个

`degradesThenIsolatesAfterConsecutiveFailures`（状态机全程）、`recoversAutomaticallyAndCountsRecovery`
（自动恢复）、`reservedChannelIsDownEvenWhenProbeThrows`（预留渠道处理）、`snapshotContainsIsolationFields`。

### `HttpClientsTest` · 1 个

`buildsHttp11ClientWithConnectTimeout` —— 缺陷 D-1 回归守卫（与 `session-manager` 的
`OutboundHttpTest` 是同一缺陷在两侧的守卫）。

### `TextExtractorTest` · 6 个

`extractsTitleAndBodyFromHtml`、`keepsPlainTextUntouched`、`decodesUtf8ByContentTypeHeader`、
`decodesGbkWitoutHeader`（**用例名有拼写错误，应为 `Without`**）、`decodesCharsetFromHtmlMeta`、
`handlesEmptyInput`。

### `GlobalExceptionHandlerTest` · 5 个（2026-09-18 新增）

`unmappedRouteMapsTo404NotServerError`（未映射路由 → **404 而非 500**）、`noHandlerFoundAlsoMapsTo404`、
`wrongMethodMapsTo405`（方法不支持必须与 404 区分）、`unsupportedMediaTypeMapsTo415`、
`genuineServerFaultStillMapsTo500`（**修复边界守门**：真实内部故障仍须 500）。

前四条是缺陷回归，最后一条防止「为修 404 而把兜底分支整体改掉」——三模块用同一套用例模板，
差异只有示例路径与异常消息，便于对照维护。

## 9. 故障排查速查

| 现象 | 可能原因 | 排查位置 |
|---|---|---|
| 视觉渠道一直 DEGRADED | OCR 引擎未安装（PaddleOCR/Tesseract） | `GET /api/sense/channels-health` 看 `VISUAL`；OCR 探针要求响应含 `"available":true` |
| 嗅觉渠道 DOWN | 未配置情报源 | 设 `SENSE_INTEL_FEEDS`，或配 R0 规则 `channel=NOSE` |
| 听觉渠道始终 DOWN | **这是预期行为**（Phase 3 才实现） | `AudioChannel.available()==false` |
| 所有采集都 REJECTED 且原因含 `no available channel` | 全部渠道不可用/被隔离 | 查 `/api/sense/channels-health` 的 `isolated` 字段 |
| 数据落到了本地而不是 MinIO | MinIO 不可达，已自动降级 | `/api/sense/stats` 的 `staging.active_backend` |
| 采集成功但没事件 | Kafka 不可达（`max.block.ms=3000` 后放弃） | `/api/sense/stats` 的 event `available` / `published` |
| 前端趋势图无数据 | `SenseMetrics` 为内存态，重启归零 | 需要重新采集产生数据 |
| 会话创建时 `bus_channel=direct` | NATS 未连通 | 检查 `NATS_URL`；注意本模块用自研客户端，订阅的是具体主题 |

## 10. 修改指引

| 需求 | 改动位置 |
|---|---|
| **新增一个采集渠道** | 实现 `SenseChannel` + 加 `@Component`（**无需改 `ChannelRegistry`**）；若需参与降级顺序，注意 `CollectPipeline.fallbackOrder` 里 TOUCH 被强制置首 |
| 调整路由降级顺序 | `CollectPipeline.route()` 的 `fallbackOrder` |
| 新增质检规则 | **只加到 `QualityGate.rejectReason()`**（保持单一事实来源） |
| 调整质检阈值/权重 | `QualityGate` 的四个 `W_*` 常量 + `sense.quality.*` 配置 |
| 调整重试策略 | `sense.retry.*` 配置（无需改代码） |
| 调整健康判定灵敏度 | `sense.health.degrade-after` / `down-after`（默认 1 / 3） |
| 新增 R0 定时规则 | `application.yml` 的 `sense.r0.rules[]`（**无需改代码**） |
| 新增总线 RPC 主题 | `NatsBusServer` 的订阅列表 + `handleRequest` 分支（**通配订阅在此环境失效，必须显式登记**） |
| 新增暂存后端（如 S3） | 实现 `StagingBackend`，并在 `StagingStore.active()` 加入选择逻辑 |
| 调整暂存对象键结构 | `MinioStagingBackend.keyOf()` / `LocalStagingBackend` 的路径拼装（**两处需保持一致**） |
| 新增运营观测接口 | `SenseAdminController`（**字段用 snake_case，并同步前端 Console 契约**） |
| 调整事件负载字段 | `SenseEventPublisher.publishCollected`（注意 Phase 3 消费者会依赖这些字段） |
| 调整指标趋势窗口 | `SenseMetrics.rateTrend()`（当前 6 分钟，**需与前端图表同步**） |
| 新增错误码 | `common/ErrorCode` + `GlobalExceptionHandler` switch（**三份副本需同步**：`sense-service` / `session-manager` / `body-service`） |
