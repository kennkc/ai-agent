# 06 · body-service 躯体服务

> 模块路径：`services/java/body-service/`
> 源文件：**4 个主代码 + 1 个测试**（主代码 122 行）
> HTTP 端口：**8083** · gRPC 端口：**9094**
> 主要依赖：Spring Web（仅此一项业务依赖，无 Redis / 无数据库 / 无 Nacos 之外的外部组件）

## 1. 模块职责

`body-service` 是生命体的**知识与记忆的躯体**，当前处于 **MVP 阶段**：

1. **知识入库**（`ingest`）：接收文档，切分为片段并存起来。
2. **知识检索**（`retrieve`）：按查询串做**基于字符命中的简易打分**，返回 top-K 片段。

它是 VS1 问答链路的**最后一环**：`session-manager` 调它拿到知识片段，再拼装成模板回答。

## 2. 当前实现的定位（重要）

**这是一个刻意做的「可替换实现」，不是最终形态。** 理解这一点可以避免两类误解：

| 维度 | 现状 | 目标（Phase 3 躯体期） |
|---|---|---|
| 存储 | **进程内 `ConcurrentHashMap`**（重启即丢） | Qdrant / pgvector 向量库 |
| 检索 | 字符串包含 + 字符命中率打分 | 语义向量相似度检索 |
| 切分 | 按空行 + 300 字硬切 | 语义分块（按标题层级/句义） |
| 多租户 | Map 的 key 是 `tenantId` | 向量库的租户分区/命名空间 |

**存在的意义**：让 Phase 1 的 VS1 问答链路能端到端跑通（`session → intent → retrieval → answer`），
而把「检索质量」这件事整体推迟到 Phase 3。届时替换点是 `BodyStore` 一个类 ——
`BodyController` 暴露的 HTTP 契约（`/api/body/ingest`、`/api/body/retrieve`）不需要变化。

**不要在 MVP 上做性能或召回优化** —— 那属于 Phase 3 的范围，此处投入会直接废弃。

## 3. 文件清单

| 文件 | 类型 | 行数 | 职责 |
|---|---|---:|---|
| `BodyServiceApplication.java` | 启动类 | 7 | Spring Boot 入口（单行体，最简启动类） |
| `controller/BodyController.java` | 控制器 | 39 | 入库 / 检索 / 健康检查三个接口 |
| `store/BodyStore.java` | 组件 | 52 | 内存存储与打分检索的核心实现 |
| `grpc/GrpcHealthServer.java` | 组件 | 24 | gRPC Health 探针 |
| `test/…/BodyStoreTest.java` | 测试 | 14 | 租户隔离检索 |

## 4. 逐文件说明

### 4.1 `BodyServiceApplication.java` · 启动类 · 7 行

- **职责**：Spring Boot 入口，`@EnableDiscoveryClient` 注册到 Nacos。
- **特点**：全项目最简启动类，全部逻辑压缩到一行。没有 `@ConfigurationProperties`，
  配置只有端口与 Nacos 地址（见 §6）。
- **对比**：不像 `sense-service` 需要 `@EnableScheduling`（有定时任务）与 `@ConfigurationPropertiesScan`（配置类多），
  本模块两者都不需要。

### 4.2 `controller/BodyController.java` · 控制器 · 39 行

- **职责**：HTTP 入口，把请求转成 `BodyStore` 调用并把记录（`record`）映射为 `Map` 响应。
- **接口**：

| 方法 | 路径 | 请求体 | 响应 |
|---|---|---|---|
| `ingest` | `POST /api/body/ingest` | `IngestRequest(doc_id, title, content, source)` | `{doc_id, chunk_count, success}` |
| `retrieve` | `POST /api/body/retrieve` | `RetrieveRequest(query, top_k)` | 片段数组 `[{chunk_id, doc_id, title, content, source}]` |
| `health` | `GET /api/body/health` | — | `{service:"body-service", status:"ok"}` |

- **租户来源**：三个接口全部从 `@RequestHeader("X-Tenant-Id")` 读取，默认值 `"default"`。
  **默认值的存在意味着未携带该头时不会报错，而是落到 default 租户** ——
  这在生产环境是危险的（网关注入失效时会静默串租户），但作为 MVP 的便利性取舍被接受。
- **参数细节**：

  - `retrieve` 中 `top_k` 为 null 时默认 **3**（与 `session-manager` 的 `BodyClient` 硬编码值一致）。
  - `ingest` 的 `success` 定义为 `chunk_count > 0`，即内容为空/全空白时返回 `success=false` 但**仍是 200**。
    调用方需要读 `success` 字段判断，不能只看 HTTP 状态码。
- **DTO 用 `record` 而非类**：`IngestRequest` / `RetrieveRequest` 是控制器内部的嵌套 record，
  字段名即 JSON 键名（`doc_id` / `top_k` 是 snake_case，与前端约定一致）。
- **协作**：只依赖 `BodyStore`，不依赖任何其他服务。

### 4.3 `store/BodyStore.java` · 组件 · 52 行

检索 MVP 的核心实现。

**存储结构**：

```java
Map<String, List<StoredChunk>> tenantChunks   // ConcurrentHashMap：tenantId → 片段列表
```

用 `ConcurrentHashMap` 保证并发写入安全；但**列表本身是 `ArrayList`**，
`computeIfAbsent` 之后对列表的 `add` **没有同步保护** —— 高并发写入同一租户存在丢片段的可能。
MVP 阶段可接受，Phase 3 换实现时一并消解。

**方法**：

| 方法 | 说明 |
|---|---|
| `ingest(tenantId, docId, title, content, source)` | 空内容返回 0；否则切分后逐个入列，返回片段数 |
| `retrieve(tenantId, query, topK)` | 空查询返回空列表；否则打分、过滤 `score > 0`、降序、取前 `clamp(topK, 1, 10)` |
| `split(content)` | 私有：按空行切块，单块超 300 字硬切 |
| `score(query, content)` | 私有：打分函数 |

**打分算法**（两档，很朴素但确定性好）：

```text
若 content 完整包含 query            → 1.0（最高分，字面命中）
否则 去掉 query 中空白后，统计其字符在 content 中出现的比例 → 命中字符数 / query 长度
```

特点与局限：

- 字符级匹配，**无分词、无词序、无权重** —— 中文按单字命中，容易被常用字虚高。
- 只做 `> 0` 过滤，即**只要命中一个字符就会返回**，召回宽松、精度低。
- 确定性好、零依赖、无需索引 —— 这正是 MVP 想要的性质。

**`topK` 的钳制**：`Math.max(1, Math.min(topK, 10))` —— 下限 1、**上限 10**。
即使调用方传 1000 也不会返回超过 10 条，这是对内存与响应体的保护。

**内部类型**：

| 类型 | 可见性 | 说明 |
|---|---|---|
| `record StoredChunk(chunk_id, doc_id, title, content, source)` | `public` | 对外返回的片段结构 |
| `record ScoredChunk(StoredChunk chunk, double score)` | `private` | 排序用的中间结构，不对外暴露 |

**协作**：被 `BodyController` 使用；无下游依赖。

### 4.4 `grpc/GrpcHealthServer.java` · 组件 · 24 行

与 `gateway-service` / `session-manager` / `sense-service` 的同名类逻辑相同（仅端口不同），
压缩到 24 行（方法体合并为单行）。职责与设计取舍见 [03-gateway-service](03-gateway-service.md) §4.2。

### 4.5 `test/…/BodyStoreTest.java` · 测试 · 14 行 · 1 个用例

| 用例 | 验证内容 |
|---|---|
| `retrievesWithinTenantOnly` | 以 `tenant-a` 入库后：`tenant-a` 能检索到、`tenant-b` **检索为空** |

**这是全项目唯一的租户隔离测试之一**（另一个在 `session-manager` 的 `SessionControllerTest`）。
它守卫的是多租户架构的底线：**A 租户的知识不能被 B 租户检索到**。
将来换向量库实现时，这个用例必须继续通过 —— 它是判断「替换实现是否保持语义」的最小验收标准。

## 5. 接口调用示例

```bash
# 入库
curl -X POST http://127.0.0.1:8083/api/body/ingest \
  -H "Content-Type: application/json" -H "X-Tenant-Id: demo" \
  -d '{"doc_id":"doc-1","title":"架构说明","content":"agent gateway session knowledge retrieval","source":"manual"}'
# → {"doc_id":"doc-1","chunk_count":1,"success":true}

# 检索
curl -X POST http://127.0.0.1:8083/api/body/retrieve \
  -H "Content-Type: application/json" -H "X-Tenant-Id: demo" \
  -d '{"query":"knowledge","top_k":3}'
# → [{"chunk_id":"...","doc_id":"doc-1","title":"架构说明","content":"...","source":"manual"}]
```

经网关访问时把主机换成 `http://127.0.0.1:8080`（路由 `/api/body/**`），
并且**必须带 `Authorization: Bearer <token>`** —— 网关会校验令牌并覆写 `X-Tenant-Id`。

## 6. 配置项

配置极简，仅 `src/main/resources/application.yml`：

| 配置 | 值 |
|---|---|
| `server.port` | 8083 |
| `grpc.port` | `${GRPC_PORT:9094}` |
| `spring.application.name` | `body-service` |
| `spring.cloud.nacos.discovery.server-addr` | `${NACOS_ADDR:127.0.0.1:8848}` |
| `management.endpoints.web.exposure.include` | `health,info` |

**注意本模块没有 OTLP 追踪配置**（`management.otlp.tracing.endpoint` 缺失），
而 `gateway-service` / `session-manager` / `sense-service` 都有 ——
即 `body-service` 的调用**不会出现在 Jaeger 链路里**。若要做全链路追踪，这里是缺口。

## 7. 修改指引

| 需求 | 改动位置 |
|---|---|
| 接入向量库（Phase 3） | 替换 `BodyStore` 实现，保持 `BodyController` 契约不变 |
| 调整切分策略 | `BodyStore.split()`（当前：空行 + 300 字硬切） |
| 提高检索质量 | `BodyStore.score()`（当前：字符命中率，需换成向量相似度才能真正解决） |
| 调整召回上限 | `BodyStore.retrieve()` 里的 `Math.min(topK, 10)` 钳制 |
| 补齐链路追踪 | `application.yml` 增加 `management.otlp.tracing.endpoint` |
| 让租户头成为强制项 | 去掉 `@RequestHeader` 的 `defaultValue = "default"`（**需评估对现有调用的影响**） |
