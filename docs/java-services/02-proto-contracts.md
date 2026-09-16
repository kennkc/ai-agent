# 02 · proto-contracts 契约模块

> 模块路径：`services/java/proto-contracts/`
> 源文件数：**90 个 `.java`**（全部为 `protoc` 生成产物，无手写代码）、0 测试
> 对应契约目录：`proto/`（6 份 `.proto`）

## 1. 模块职责

本模块是两个角色合一：

1. **跨语言契约的单一事实来源**：`.proto` 定义消息与服务，Java 侧（本模块）与 Python 侧（`nlp-service`）各自从同一份契约生成代码，
   保证两侧字段名、字段号、服务方法签名不会漂移。
2. **被所有 Java 服务依赖的编译期基础**：`gateway-service`、`session-manager`、`sense-service`、`body-service` 的 `pom.xml`
   都依赖本模块，用于 gRPC 健康探针与未来的服务间 gRPC 调用。

**本模块不独立部署**，没有 `main` 方法、没有 `application.yml`，只产出 jar。

## 2. 契约清单

| 契约文件 | 包 | service | 消息 / 枚举 |
|---|---|---|---|
| `proto/body/v1/body.proto` | `com.agent.body.v1` | `BodyService` | `IngestRequest/Response`、`RetrieveRequest/Response`、`RetrievedChunk`（5） |
| `proto/brain/v1/brain.proto` | `com.agent.brain.v1` | `BrainService` | `RecognizeIntentRequest/Response`、`IntentResult`、`PlanTaskRequest/Response`、`TaskStep`（6） |
| `proto/common/v1/health.proto` | `com.agent.common.v1` | `HealthService` | `HealthCheckRequest/Response`、`Error`（3） |
| `proto/limb/v1/limb.proto` | `com.agent.limb.v1` | `LimbService` | `ExecuteToolRequest/Response`、`ToolResult`、`RegisterToolRequest/Response`（5） |
| `proto/sensor/v1/sensor.proto` | `com.agent.sensor.v1` | `SensorService` | `CollectRequest/Response`、`RegisterChannelRequest/Response`、`ChannelHealthRequest/Response` + `ChannelType`、`CollectMode` 枚举（8） |
| `proto/session/v1/session.proto` | `com.agent.session.v1` | `SessionService` | `Session`、`Message`、`Citation` + Create/Append/Get/List/UpdateStatus 各 Request/Response（13） |

除 `common/health.proto`（Phase 0 已投入使用）外，`brain` / `limb` / `sensor` / `session` / `body` 的 gRPC 服务方法
目前**仅定义了契约，尚未实现服务端** —— 当前服务间调用走 HTTP 与 NATS。契约先行是为了让后续阶段（尤其 Phase 5 四肢层）
在实现 gRPC 时不必回头改接口。

## 3. 生成文件的命名规律

理解命名规律后，90 个文件可以按「每个 message 2~3 个文件 + 每个 proto 2 个文件」快速定位：

| 生成文件 | 来源 | 作用 |
|---|---|---|
| `<Message>.java` | 每个 `message` | 消息的不变类（immutable class），含 Builder |
| `<Message>OrBuilder.java` | 每个 `message` | 消息的只读视图接口，供 gRPC 传输层与方法签名使用 |
| `<ProtoFile>OuterClass.java` | 每个 `.proto` | 文件级外层类，存放文件描述符与静态注册入口 |
| `<Service>Grpc.java` | 每个 `service` | gRPC 桩类：`newBlockingStub` / `newStub` / 服务端 `ImplBase` |

一个特殊的命名细节：`session.proto` 中**存在名为 `Session` 的 message**，与「由文件名推导出的外层类名 `Session`」冲突，
因此 `protoc` 把外层类改名为 **`SessionOuterClass.java`**。这是本模块唯一一处需要留心的地方 ——
在 `session` 包内 `Session` 指的是消息类，不是外层类。

各包文件数分布：

| 包 | 文件数 | 其中 Grpc 桩 |
|---|---:|---:|
| `com.agent.body.v1` | 12 | 1（`BodyServiceGrpc`） |
| `com.agent.brain.v1` | 14 | 1（`BrainServiceGrpc`） |
| `com.agent.common.v1` | 8 | 1（`HealthServiceGrpc`） |
| `com.agent.limb.v1` | 12 | 1（`LimbServiceGrpc`） |
| `com.agent.sensor.v1` | 16 | 1（`SensorServiceGrpc`） |
| `com.agent.session.v1` | 28 | 1（`SessionServiceGrpc`） |
| **合计** | **90** | **6** |

## 4. 为什么生成源码要入库（关键工程决策）

这是本项目最需要注意的一个非常规决策，**不是疏忽，而是刻意的规避措施**。

### 4.1 问题现象

按常规做法，`protobuf-maven-plugin` 挂在默认生命周期上，每次 `mvn package` 都会：

1. 把 `protoc` 与 `protoc-gen-grpc-java` 可执行文件解压到 `target/protoc-dependencies/`；
2. 构建时**先删除**该目录再重新解压。

在本机 Windows 上，第 2 步的目录删除会永久阻塞。用 `jstack` 抓取主线程栈可见调用链：

```text
java.io.WinNTFileSystem.delete0(Native Method)
  → java.io.WinNTFileSystem.delete(File)
  → org.codehaus.plexus.util.FileUtils.forceDelete(...)
  → org.codehaus.plexus.util.FileUtils.deleteDirectory(...)
  → org.codehaus.plexus.util.FileUtils.cleanDirectory(...)
  → org.xolstice.maven.plugins.protobuf.AbstractProtocMojo.makeProtoPathFromJars(...)
```

即 `protoc` 依赖目录的清理在**原生层**长时间不返回，表现为「Maven 没有任何输出、永久卡死」。

已排除的可能性（逐一实测，均非根因）：Aliyun 镜像网络问题（返回 200）、磁盘空间不足、
Maven 并发（单进程可复现）、Bash 沙箱（PowerShell 通道同样复现）、`protoc` 子进程残留。

### 4.2 采用的方案

**不对抗操作系统行为，而是把它从构建路径上移除：**

1. 用 `protoc` 命令行**直接生成** 90 个源文件，复制进 `src/main/java` 并提交入库；
2. 从默认生命周期**移除** `protobuf-maven-plugin`；
3. 保留一个 `proto-gen` profile 作为再生入口，只在契约变更时手动触发。

效果：构建从「永久卡死」变为约 **39 秒**稳定通过（`clean package`）。

### 4.3 代价与待办

| 代价 | 缓解方式 |
|---|---|
| 契约变更后必须手动重新生成并提交产物，否则两侧不同步 | 保留 `proto-gen` profile，见下节命令 |
| 90 个生成文件在 PR 中产生较大 diff 噪音 | 评审时按「只看 `proto/` 与 `Grpc.java`」策略跳过纯生成文件 |
| 忘记同步的风险 | **建议后续在 CI 中加入校验**：用 `-Pproto-gen` 重新生成后执行 `git diff --exit-code`，有差异即失败 |

## 5. 契约变更流程

```bash
# 1) 先改契约
#    编辑 proto/<pkg>/v1/<name>.proto

# 2) 重新生成 Java 产物（需 JDK21）
cd services/java
../../scripts/mvn-dev.sh -pl proto-contracts -am clean package -Pproto-gen

# 3) 确认产物变化
git status --short services/java/proto-contracts/src/main/java

# 4) 同步 Python 侧产物
#    nlp-service 的 proto 生成产物路径：services/python/nlp-service/generated/（已被 .gitignore 忽略，需重新生成）

# 5) 提交：proto 文件 + 生成产物 一起提交，不要只提交其中一个
```

**注意**：`proto-gen` profile 会因为插件要清理 `target/protoc-dependencies` 而**触发同一处卡死风险**。
若该命令无输出卡住，参考项目根 `docs/demo/` 下验收文档的故障排查章节，或直接用 `protoc` 命令行生成
（参数要点：`--proto_path` 需同时包含 `proto/` 与 protobuf 的 `well-known-types` 依赖目录，
`--plugin=protoc-gen-grpc-java=<解压出的可执行文件>`，`--java_out` 与 `--grpc-java_out` 指向
`proto-contracts/src/main/java`）。

## 6. 依赖关系

本模块的 `pom.xml` 声明了：

| 依赖 | 用途 |
|---|---|
| `protobuf-java` | 生成代码的运行时（`com.google.protobuf.*`） |
| `grpc-protobuf` | gRPC 与 protobuf 的桥接 |
| `grpc-stub` | gRPC 桩基类 |
| `javax.annotation-api` | 生成代码中 `@javax.annotation.Generated` 注解所需 |

四个业务模块通过 `<dependency>proto-contracts</dependency>` 引入，因此**修改本模块会触发全部模块重编译**。

## 7. 归档说明

按本文档系列的约定，本模块的 90 个文件**不逐个展开描述** —— 它们的职责由 `.proto` 唯一决定，
逐个描述只会复制契约内容。需要了解某个消息的字段时，**直接读 `proto/` 下的 `.proto` 文件**，
那才是可读性最好的事实来源（生成出来的 Java 反而是难读的那一侧）。
