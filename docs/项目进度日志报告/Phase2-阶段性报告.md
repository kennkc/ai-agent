# Phase 2 感官期 · 阶段性报告

| 项 | 内容 |
|---|---|
| 阶段 | Phase 2 · 感官期（Sensory Stage） |
| 项目 | Agent-Lifeform · AI Agent 生命体架构 |
| 仓库 | `E:\ai_workspace\project_space\ai-agent-lifeform\ai-agent` |
| 分支 | `workbuddy/main` |
| 报告日期 | 2026-09-12 |
| 代码基线 | `850f49d feat(phase2): 感官期五感采集、质检暂存与意图识别双级联（R2-01~R2-10）` |
| 需求范围 | R2-01 ~ R2-10（依据《AI-Agent生命体架构-阶段性需求拆解分析》v1.6） |
| 阶段结论 | **通过**（1 项受外部引擎依赖降级为待补验证，详见 §6） |

---

## 1. 阶段目标回顾

Phase 1（神经期）打通了「网关 → 会话 → 意图 → 检索」的脊柱数据总线，但生命体**只有反射、没有感官**——
数据只能靠人喂（`/api/body/ingest` 手工灌库），无法自主获取外部信息。

Phase 2 的目标是**接通五感**：让生命体能够按规则自主采集外部数据、把异构数据标准化、在入库前拦截低质内容、
并把意图识别从"单一规则匹配"升级为"规则优先 + 模型兜底"的双级级联。

设计文档定义的活动流程（`Phase2-感官期/需求设计文档.md` §4）：

```
采集触发(R0定时 / R1指令)
  → 渠道选择(触觉/视觉/...)
  → 采集执行(抓取/OCR/读取)
  → 数据标准化(五字段打标)
  → 质检(基础: 非空/长度/编码) ── 失败 → 重试(3次) → 失败 → 死信
  → 通过 → 隔离暂存(MinIO) → 通知入库(Phase 3 消费)
```

本阶段的全部实现即围绕这条链路落地。

---

## 2. 交付物清单

### 2.1 sense-service（Java 主服务，新增 36 个源文件 + 7 个测试类）

| 包 | 交付物 | 对应需求 |
|---|---|---|
| `channel` | `SenseChannel`（可插拔抽象）、`ChannelRegistry`、`TouchChannel`、`VisualChannel`、`AudioChannel`、`NoseChannel`、`TasteChannel` | R2-01 ~ R2-03 |
| `normalize` | `DataNormalizer`（五字段打标） | R2-04 |
| `quality` | `QualityGate`（内容/长度/编码/重复/密度五维质检） | R2-10 |
| `schedule` | `R0Scheduler`（规则驱动定时轮询） | R2-05 |
| `dynamic` | `R1DynamicHandler`、`SenseCommand` | R2-06 |
| `health` | `ChannelHealthMonitor`（探针 + 隔离 + 恢复） | R2-09 |
| `staging` | `StagingStore`、`StagingBackend`、`MinioStagingBackend`、`LocalStagingBackend` | R2-10 |
| `deadletter` | `DeadLetterStore` | R2-09 |
| `event` | `SenseEventPublisher` | R2-10 |
| `metrics` | `SenseMetrics` | 观测 |
| `security` | `OutboundGuard`（SSRF 防护） | 安全 |
| `util` | `TextExtractor`（jsoup 正文提取） | R2-02 |
| `config` | `SenseProperties` | 配置 |
| `controller` | `SenseController`（采集入口）、`SenseAdminController`（运营/观测接口） | R2-02 / R-C02 |
| `collect` | `CollectPipeline`（采集主链路编排） | 全链路 |
| `model` | `CollectedData`（五字段载体 + 到 Map 的契约序列化） | R2-04 |

### 2.2 nlp-service（Python）

| 文件 | 作用 | 对应需求 |
|---|---|---|
| `app/intent/rules.py` | 规则引擎，12 场景（关键词 + 正则 + 场景映射） | R2-07 |
| `app/intent/l0_model.py` | L0 轻量分类模型 | R2-08 |
| `app/intent/cascade.py` | 双级级联调度（规则优先 → 模型兜底） | R2-08 |
| `app/intent/corpus.py` | 训练/留出语料 | R2-08 |
| `app/ocr.py` | OCR 代理，无引擎时诚实上报 `available=false` | R2-03 |
| `tests/test_intent.py`、`tests/test_ocr.py` | 19 项测试 | — |

### 2.3 Console 前端与契约

| 文件 | 作用 | 对应需求 |
|---|---|---|
| `web/console/index.html` | 感官视图：五感矩阵 + 渠道下钻 | R-C02 |
| `web/console/js/provider.js` | `ConsoleDataProvider` mock/api 双数据源 | R-C09 |

### 2.4 基础设施与工程化

| 文件 | 作用 |
|---|---|
| `services/java/proto-contracts/pom.xml` | protobuf 插件移出默认生命周期，保留 `proto-gen` profile |
| `services/java/proto-contracts/src/main/java/com/agent/**/v1/*.java` | 生成源码入库（6 proto / **90 文件**） |
| `scripts/mvn-dev.sh` | Maven 直调包装（绕过损坏的 `mvn.cmd`） |
| `scripts/build-sense.sh` | sense-service 单模块构建脚本 |
| `docs/demo/Phase2-DEMO.md` | 阶段验收演示脚本 |
| `PROGRESS.md`、`README.md` | 进度与说明同步更新 |
| `.gitignore` | 新增忽略 `.build/`、`data/sense-*/` |

### 2.5 本报告目录

| 文件 | 说明 |
|---|---|
| `README.md` | 目录索引与维护规范 |
| `Phase2-阶段性报告.md` / `.html` | 本报告 |
| `Phase2-开发执行日志.md` / `.html` | 执行流程归档（时间线 + 决策记录） |
| `Phase2-测试验收报告.md` / `.html` | 测试与验收证据 |

---

## 3. 需求覆盖情况

| 需求 | 名称 | 优先级 | 验收标准 | 实现位置 | 结论 |
|---|---|---|---|---|---|
| R2-01 | SenseChannel 渠道抽象 | Must | 渠道可插拔；新增渠道不改核心代码 | `SenseChannel` + `ChannelRegistry`（Spring Bean 自动发现） | ✅ |
| R2-02 | 触觉渠道采集 | Must | URL/文件/文本三类源可采集并标准化 | `TouchChannel` + `TextExtractor` | ✅ |
| R2-03 | 视觉渠道 OCR | Should | 基础 OCR 返回文本 | `VisualChannel` → nlp-service `/api/nlp/ocr` | ⚠️ 待装引擎 |
| R2-04 | 数据标准化打标 | Must | 五字段完整 | `DataNormalizer` | ✅ |
| R2-05 | R0 默认采集调度 | Must | 定时按规则触发；调度日志完整 | `R0Scheduler` | ✅ |
| R2-06 | R1 动态采集 | Should | 指令→渠道选择→采集→回传 | `R1DynamicHandler` + `SenseCommand` | ✅ |
| R2-07 | 意图识别-规则引擎 | Must | 10 个预置场景识别准确 | `rules.py`（12 场景） | ✅ |
| R2-08 | L0 意图分类模型 | Should | 延迟 < 50ms；准确率 ≥ 85% | `l0_model.py` + `cascade.py` | ✅ |
| R2-09 | 渠道健康检查与重试 | Must | 宕机自动隔离；恢复自动重连 | `ChannelHealthMonitor` + resilience4j 退避 + `DeadLetterStore` | ✅ |
| R2-10 | 采集数据隔离暂存 | Should | 低质数据被拦截；批次可回滚 | `QualityGate` + `StagingStore` | ✅ |

**覆盖率：Must 6/6 = 100%；Should 3/4 完成 + 1 项受外部依赖阻塞；合计 9/10 = 90% 可直接验收。**

---

## 4. 关键设计落地说明

### 4.1 渠道可插拔（R2-01）

`SenseChannel` 定义统一契约（`type / label / icon / available / healthy / collect / close`），
`ChannelRegistry` 通过 Spring 容器**自动发现**所有 `SenseChannel` 实现 Bean 并按 `ChannelType` 建索引。

**效果**：新增渠道只需新增一个 `@Component` 实现类，`CollectPipeline`、`SenseController`、
`SenseAdminController`、Console 感官视图**零改动**即可识别（五感矩阵卡片由 `/api/sense/channels` 动态渲染）。

### 4.2 采集主链路（R2-02 ~ R2-04、R2-09、R2-10）

`CollectPipeline` 串联完整链路，每步都有明确的失败出口：

```
渠道健康检查 → 隔离则直接拒绝
  → 渠道 collect（resilience4j 指数退避重试，max-attempts=3）
      失败 → 死信队列 DeadLetterStore（保留 tenant/channel/reason/attempts）
  → DataNormalizer 五字段打标
  → QualityGate 质检
      不通过 → 暂存标记 REJECTED（保留 reject_reason 供下钻查看）
  → 通过 → StagingStore 落盘（MinIO 优先，不可用回落 local）
  → SenseEventPublisher 发事件 lifeform.sense.collected（Phase 3 入库消费）
  → SenseMetrics 计数（批次/通过/拒绝/条目/速率/最近时间）
```

**质检判定采用单一事实来源**：`QualityGate.rejectReason(content)` 返回拒绝原因或 `null`，
`pass()` 直接委托它（`return rejectReason(content) == null`），避免"评分通过但硬规则拒绝"这类不一致。

加权公式：`score = 0.45·内容分 + 0.20·长度分 + 0.20·可打印分 + 0.15·信息密度分`

拒绝原因枚举：`EMPTY_CONTENT` / `ENCODING_GARBLED` / `REPETITIVE_GARBAGE` / `BELOW_QUALITY_THRESHOLD`

### 4.3 双模式采集（R2-05 / R2-06）

| 模式 | 触发方式 | 实现 | 特点 |
|---|---|---|---|
| R0 默认采集 | 定时轮询（`@Scheduled`，默认 60s tick） | `R0Scheduler` + `application.yml` 规则表 | 规则含 `channel / data-source / query / tenant-id / interval-seconds / freshness / enabled`，各规则独立间隔；提供 `runNow(ruleId)` 供演示与验收手动触发 |
| R1 动态采集 | 外部指令（BrainCommand 雏形） | `R1DynamicHandler` + `SenseCommand` | 支持单渠道或 `channels[]` 多渠道路由，含单轮渠道上限（默认 3）与条目上限（默认 10），`DOWN` 渠道自动跳过并计入 `skipped` |

### 4.4 意图识别双级级联（R2-07 / R2-08）

```
文本 → 规则引擎（rules.py，12 场景，高置信直出）
        未命中 ↓
       L0 轻量分类模型（l0_model.py）
        仍不可判 ↓
       归入「情感闲聊」（兜底，不返回空意图）
```

级联结果携带 `engine` 字段（`rule` / `l0`），便于观测各引擎命中占比。

---

## 5. 验证结果汇总

### 5.1 构建

```
[INFO] proto-contracts .................................... SUCCESS
[INFO] gateway-service .................................... SUCCESS
[INFO] session-manager .................................... SUCCESS
[INFO] sense-service ...................................... SUCCESS
[INFO] body-service ....................................... SUCCESS
[INFO] BUILD SUCCESS
[INFO] Total time:  03:04 min
```

### 5.2 测试

| 层次 | 范围 | 数量 | 失败 | 错误 |
|---|---|---:|---:|---:|
| Java 单元测试 | sense-service | **45** | 0 | 0 |
| Java 单元测试 | session-manager | 4 | 0 | 0 |
| Java 单元测试 | gateway-service | 2 | 0 | 0 |
| Java 单元测试 | body-service | 1 | 0 | 0 |
| **Java 合计** | | **52** | **0** | **0** |
| Python 测试 | nlp-service 意图识别 | 15 | 0 | 0 |
| Python 测试 | nlp-service OCR | 4 | 0 | 0 |
| **Python 合计** | | **19** | **0** | **0** |
| **总计** | | **71** | **0** | **0** |

### 5.3 硬指标达标情况

| 指标 | 目标 | 实测 | 结论 |
|---|---|---|---|
| L0 意图识别准确率（留出集 60 样本） | ≥ 85% | **90.0%** | ✅ 超出 5.0pp |
| L0 意图识别延迟 | P99 < 50ms | **P99 0.052ms** | ✅ 大幅超出 |
| 场景覆盖数 | ≥ 10 | **12** | ✅ |
| 契约校验 `contract-check.py` | 0 FAIL | **0 FAIL**（3 项既有 WARN） | ✅ |
| 前端语法校验 `node --check` | 通过 | 通过 | ✅ |

### 5.4 详细证据

完整测试用例清单与验收命令见同目录 **`Phase2-测试验收报告.md`**，
端到端演示脚本见 **`docs/demo/Phase2-DEMO.md`**。

---

## 6. 当前开发发现的问题与解决思路

> 本节是本阶段复盘的核心。问题按**性质**分为四类：构建工具链、实现缺陷、集成契约、方法论误判。
> 每条记录「现象 → 定位过程 → 根因 → 解决 → 教训」，便于后续同类问题快速命中。

### 类别 A：构建工具链

#### A-1（阻塞级）Maven 构建永久卡死 —— protobuf 插件删除目录原生阻塞

| 项 | 内容 |
|---|---|
| **现象** | 构建在 `sense-service` 之前卡死，**60s+ 到 74s+ 无任何输出**，不报错也不结束；多次重跑同样位置卡住 |
| **定位过程** | ① 用 `jps` 找到 Maven 进程，`jcmd <PID> Thread.print` 抓主线程栈；② 读到栈底为 `java.io.WinNTFileSystem.delete0(Native Method)` ← `FileUtils.deleteDirectory` ← `cleanDirectory` ← `AbstractProtocMojo.makeProtoPathFromJars`；③ 逐项排除网络（阿里云镜像 200 正常）、磁盘空间（充足）、Maven 并发（单进程仍复现）、调用通道（Bash 沙箱与 PowerShell 通道均复现）、protoc 子进程残留（无） |
| **根因** | **Windows 文件系统在删除 `target/protoc-dependencies` 时原生层长时间不返回**。不是网络、不是依赖下载、不是 JVM 死锁，而是 OS 级删除阻塞（杀软实时扫描、目录句柄、NTFS 元数据均可能参与） |
| **解决思路** | **让构建不再调用该插件**，而非继续与 OS 行为对抗：① 用 `protoc` + `protoc-gen-grpc-java` 直接生成 **90 个 Java 源文件（6 proto）**；② 把产物落到 `proto-contracts/src/main/java` 并**提交进仓库**；③ 从默认 `<build><plugins>` **移除** `protobuf-maven-plugin`；④ 保留 `proto-gen` profile 作为契约变更时的再生入口；⑤ 保留运行时依赖（`protobuf-java`/`grpc-protobuf`/`grpc-stub`/`javax.annotation-api`） |
| **结果** | 构建从「永久卡死」变为 **3:04 全量通过**；CI 不再依赖网络下载 protoc |
| **教训** | ① Windows 上做大规模目录删除的 Maven 插件是高危卡死点，**jstack 抓栈是最快定位手段**；② 对抗 OS 层阻塞不如**改变构建策略**（生成产物入库）；③ 代价是产物进入代码评审范围，**必须在 README 写清再生命令**，否则后人误以为漏提交而重新启用插件，卡死复现 |
| **沉淀** | 已固化为技能 `~/.workbuddy/skills/windows-maven-build-hang-repair/SKILL.md` |

#### A-2 `mvn.cmd` 损坏导致无法构建

| 项 | 内容 |
|---|---|
| **现象** | 本机 `mvn` 命令无输出/报错，构建无法启动 |
| **根因** | 本机 Maven 安装的 `mvn.cmd` 损坏 |
| **解决** | 编写 `scripts/mvn-dev.sh`，通过 `plexus-classworlds-2.9.0.jar` 直调 `org.codehaus.plexus.classworlds.launcher.Launcher` 拉起 Maven，绕开 `mvn.cmd` |
| **教训** | 包装脚本**不要加 `-Dstyle.color=never` 之类多余参数**——部分 PowerShell 通道会拆分参数导致阶段解析失败 |

#### A-3 多模块 `-pl` 报 "Could not find the selected project in the reactor"

| 项 | 内容 |
|---|---|
| **现象** | `mvn -pl sense-service test` 报找不到模块 |
| **根因** | 模块名解析依赖根 `pom.xml`，而根 pom 在 `services/java/`，不在执行目录 |
| **解决** | **必须在含根 pom 的目录下执行** `-pl`；命令固定为 `cd services/java && ../../scripts/mvn-dev.sh -pl <module> -am test` |

#### A-4 `rm -rf target` 被安全策略拦截

| 项 | 内容 |
|---|---|
| **现象** | 手工清理构建目录被 agent 安全策略 fail-closed 拦截 |
| **解决** | **改用 Maven 自带 `clean` 目标**，不做裸删除 |

### 类别 B：实现缺陷（由单元测试暴露）

三个缺陷的共同价值在于：**它们都不是编译错误，而是"能跑但结果错"的逻辑不一致**——
若没有测试断言真实行为（而非仅断言"不抛异常"），这三个缺陷会一路带到 Phase 3。

#### B-1 `QualityGate` 双判定源不一致 → 低质数据被错放

| 项 | 内容 |
|---|---|
| **现象** | `QualityGateTest` 中「重复乱码」「短低质文本」两个用例失败：应被拒绝的内容却通过了 |
| **根因** | `pass()` 用 `score >= threshold` 判定，而 `rejectReason()` 用硬规则（非空/可打印比/重复/长度）判定，**两套逻辑对同一内容给出相反结论** |
| **解决** | **以 `rejectReason()` 为单一事实来源**，`pass()` 改为委托：`return rejectReason(content) == null`。这样"是否通过"和"为何拒绝"永远自洽，且拒绝原因可直接透出到 Console 下钻面板 |
| **教训** | 同一语义存在两条判定路径时，必然出现不一致；**判定逻辑必须单点收口** |

#### B-2 `TouchChannel` 的 `file://` 路径解析返回空

| 项 | 内容 |
|---|---|
| **现象** | `TouchChannelTest.readsHtmlFileWithBodyExtraction` 失败，本地文件采集读不到内容 |
| **根因** | 用 `URI.create("file://page.html").getPath()` 解析——在 `file://page.html` 这种**无 host 的简写形式**下，`page.html` 被当作 **host** 而非 path，`getPath()` 返回空字符串 |
| **解决** | 放弃 URI 解析，改为**前缀子串剥离**：依次剥掉 `file://` / `file:` / `local://`，再把反斜杠归一为正斜杠、去掉前导斜杠，最后 `root.resolve(raw).normalize()` 并校验仍在沙箱根内（防目录穿越） |
| **教训** | `URI` 对非标准简写形式的语义与直觉不符；**沙箱路径解析必须自己做白名单前缀处理**，顺带完成越界校验 |

#### B-3 `LocalStagingBackend.rollback` 正序删除触发 `DirectoryNotEmptyException`

| 项 | 内容 |
|---|---|
| **现象** | `StagingStoreTest.rollbackRemovesWholeBatch` 失败，批次回滚返回 `false` |
| **根因** | `Files.walk` 返回**父目录先于子文件**的顺序，正序删除时先删父目录 → 子项仍在 → `DirectoryNotEmptyException` |
| **解决** | 按路径**逆序排序**（`Comparator.reverseOrder()`）后再删，保证"先子后父" |
| **教训** | 递归删除的天然顺序陷阱；**逆序删除是安全默认** |

### 类别 C：集成契约

#### C-1 前端 API 数据源缺失 `X-Tenant-Id` 头

| 项 | 内容 |
|---|---|
| **现象** | 审查 `provider.js` 与后端 `SenseAdminController` 契约时发现：前端 `request()` 只发 `Authorization`，未发 `X-Tenant-Id` |
| **影响** | Console 以 `?ds=api` 接入时，后端会退化为 `default` 租户分区；多租户场景下**读到别人的数据或读不到自己的数据**，且不会报错（静默错） |
| **解决** | 在 `request()` 的统一 header 中补 `X-Tenant-Id: this.tenantId`（并让 `opts.headers` 可覆盖，保留调用方控制权）；同时清理 `collect()` 中冗余的重复取 token |
| **教训** | 前后端契约审查要**逐字段比对**而非只看路径；"能返回 200"不等于"取数正确" |

#### C-2 文件误置包路径

| 项 | 内容 |
|---|---|
| **现象** | `R0Scheduler.java`、`R1DynamicHandler.java`、`SenseCommand.java` 被创建在 `services/java/com/agent/sense/...`（缺 `sense-service/src/main/java` 这一段），不在任何模块源码根内 |
| **影响** | 文件存在但**不参与编译**，编译期不报错、运行期类缺失 |
| **解决** | 迁移至 `services/java/sense-service/src/main/java/com/agent/sense/{schedule,dynamic}/` |
| **教训** | 新建文件后**必须验证其落在模块源码根内**；"文件已创建"不等于"已纳入构建" |

#### C-3 网关路由误判（方法论问题，见 D-1）

同一现象的契约确认：网关 `gateway-service/src/main/resources/application.yml` 中路由为统一入口 `http://127.0.0.1:8080`，
`/api/sense/**` → `lb://sense-service`，前端 `provider.js` 的 API 路径与之对齐，**链路闭环**。

### 类别 D：方法论误判与纠正

> 这一类不产生代码缺陷，但直接决定排查效率，值得单独归档。

#### D-1 错误的空结果导致错误结论：grep 路径写错 → 误判"网关缺 sense 路由"

| 项 | 内容 |
|---|---|
| **经过** | 我用 `grep -rn "sense" services/java/gateway/src/main/resources/*.yml` 检索网关路由，返回空 → **据此下结论"网关缺少 sense 路由，是 Phase2 集成缺口"**。随后 `find` 目录时才发现模块实际名为 **`gateway-service`**，路径写错了，路由其实一直存在 |
| **纠正** | 改用 `find <模块目录> -type f` 先确认真实路径，再 grep 内容 |
| **教训** | ① **空结果永远不是结论**——`grep` 无输出可能是"确实没有"也可能是"路径写错"；② 下判断前要用第二种手段交叉验证（`find` / `ls` / `git ls-files`） |

#### D-2 环境依赖不可用时的判断尺度

| 项 | 内容 |
|---|---|
| **现象** | OCR 测试显示 `available=False`（未装 PaddleOCR/Tesseract）——是否算"测试失败"？ |
| **决策** | **不算失败**。测试断言的正确行为是「**引擎不可用时必须诚实上报 `available=false`，不伪造识别结果**」，而不是"必须识别出文字" |
| **落地** | `VisualChannel` 与 `app/ocr.py` 均按此契约实现；Console 上视觉渠道显示 `DEGRADED`，不假装 UP |
| **教训** | 设计"能力可选降级"的系统时，**测试应断言降级行为的正确性**，而不是把外部依赖缺失一律当失败 |

#### D-3 本机 Git Bash 工具缺失（环境类）

| 项 | 内容 |
|---|---|
| **现象** | `ls` / `head` / `tail` 全部 `command not found`，`paste` / `bc` / `dirname` 缺失 |
| **纠正** | ① 本轮定位到根因是 **PATH 未设置**，`export PATH="/usr/bin:/bin:$PATH"` **一条命令即恢复**（此前会话曾误判为"Git Bash 精简版不可用"）；② 统计类操作改用托管 Python 绝对路径；③ `Glob` 工具对带盘符的绝对 `path` 参数返回空 → 改用 `git ls-files` / `find` 替代 |
| **教训** | 环境"不可用"要先确认**是不是配置问题**，再判定为能力缺失 |

---

## 7. 问题归类统计

| 类别 | 数量 | 阻塞级 | 已解决 | 沉淀形式 |
|---|---:|---:|---:|---|
| A 构建工具链 | 4 | 1（A-1） | 4 | 技能 `windows-maven-build-hang-repair` + `scripts/mvn-dev.sh` |
| B 实现缺陷 | 3 | 0 | 3 | 单测固化（45 项 sense-service 测试） |
| C 集成契约 | 3 | 0 | 3 | 统一 header / 目录规约 / 路由确认 |
| D 方法论 | 3 | 0 | 3 | 本报告 §6-D |
| **合计** | **13** | **1** | **13** | — |

**注**：A-1 是唯一阻塞级问题，累计消耗了本阶段最多的排查成本（多轮构建失败 + jstack 取证 + 通道交叉验证）。

---

## 8. 风险与遗留项

| # | 项 | 类型 | 影响 | 建议处置 |
|---|---|---|---|---|
| 1 | OCR 引擎未安装（PaddleOCR / Tesseract） | 外部依赖 | R2-03 无法端到端验收；视觉渠道保持 `DEGRADED` | 需要视觉能力时安装引擎，无需改代码 |
| 2 | Docker 运行时端到端冒烟未做 | 环境 | 真实 MinIO 暂存、NATS 事件推送、`lifeform-*` 容器未验证（当前 8 个容器为 `Exited 255`） | 拉起 Docker 引擎与容器后执行 `docs/demo/Phase2-DEMO.md` §2 |
| 3 | protobuf 生成源码入库带来 diff 噪音 | 工程实践 | `.proto` 变更时需手工同步生成产物，存在忘记同步的风险 | 建议后续加入 CI 校验：`-Pproto-gen` 重新生成后 `git diff --exit-code` |
| 4 | L0 模型为轻量实现，非 BERT 级 | 设计取舍 | 「日程提醒」类场景留出集准确率仅 60%，是 12 场景中最低 | Phase 7（进化期）按设计文档升级模型，本阶段满足 ≥85% 总体指标即可 |
| 5 | 本地领先远程 1 个提交未推送 | 协作 | `850f49d` 仅在本地 | 如需协作再推送 |

---

## 9. 阶段结论与下一步

### 9.1 结论

Phase 2 感官期**达成设计目标**：

- 生命体已具备**自主采集能力**（R0 定时 + R1 指令双模式），不再依赖人工灌数据；
- 已建立**数据质量防线**（五维质检 + 隔离暂存 + 批次回滚），低质数据在入库前被拦截；
- 已建立**渠道韧性**（健康探针 + 自动隔离 + 指数退避重试 + 死信队列），单渠道故障不拖垮整体采集；
- 意图识别升级为**双级级联**，准确率 90.0%、P99 0.052ms，均超设计指标；
- 采集过程**全程可观测**（`/api/sense/*` 运营接口 + Console 五感矩阵与渠道下钻）；
- 工程质量面：**71 项测试 0 失败**，5 模块全量构建通过，契约校验 0 FAIL。

唯一未完全闭环的是 R2-03（受本机未装 OCR 引擎限制），属**外部依赖就绪度**问题而非实现缺陷。

### 9.2 下一步（Phase 3 躯体期）

Phase 3 的核心是**打通感官到躯体的闭环**——让 Phase 2 采集并暂存的数据真正进入知识库、可被语义检索：

| 方向 | 说明 |
|---|---|
| 暂存 → 正式库 | 消费 `lifeform.sense.collected` 事件，实现暂存区 ACCEPTED 批次的入库通道（Phase 2 已预留事件出口） |
| 知识库能力 | 文档切分、向量化、Qdrant 索引，替换 body-service 当前的本地检索 MVP |
| 检索质量 | 语义检索 P99 < 500ms、召回率指标；与 Console 躯体视图对接 |
| 缺口补齐 | 安装 OCR 引擎完成 R2-03 端到端验收；CI 增加 proto 生成物同步校验 |

---

## 附录 A：可复现验证命令

```bash
# 1. 全量构建 + 测试
cd services/java
../../scripts/mvn-dev.sh clean package

# 2. Python 测试
cd services/python/nlp-service
../venv/Scripts/python.exe tests/test_intent.py
../venv/Scripts/python.exe tests/test_ocr.py

# 3. 契约校验
cd <项目根>
python scripts/contract-check.py

# 4. 前端语法校验
node --check web/console/js/provider.js
```

## 附录 B：关联文档

| 文档 | 位置 |
|---|---|
| Phase2 验收演示脚本 | `docs/demo/Phase2-DEMO.md` |
| 开发执行日志 | 本目录 `Phase2-开发执行日志.md` |
| 测试验收报告 | 本目录 `Phase2-测试验收报告.md` |
| 阶段需求设计文档 | `E:\ai_workspace\project_space\AI知识库\任务指挥中心知识库\核心知识\AGENT_CONTEXT\Phase2-感官期\需求设计文档.md` |
| 开发进度总览 | 项目根 `PROGRESS.md` |
| 构建卡死修复技能 | `~/.workbuddy/skills/windows-maven-build-hang-repair/SKILL.md` |

---

*报告生成日期：2026-09-12 · 代码基线：`850f49d` · 编制：WorkBuddy*
