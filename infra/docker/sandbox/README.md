# infra/docker/sandbox · 沙箱镜像说明（Phase 5 四肢期）

## 它是什么

`tool-executor` 在执行不可信代码（R5-05 `code` 工具）时使用的**最小运行时镜像**。
真正的隔离来自 `docker run` 的运行参数，**不是**来自镜像本身。

## 构建

```bash
docker build -t agent-sandbox:latest infra/docker/sandbox/
```

## 运行参数（由 `DockerSandboxBackend` 拼装，勿手工改）

| 参数 | 作用 | 对应需求 |
|---|---|---|
| `--network none` | 禁网（默认；仅显式 `networkEnabled` 才放开） | R5-03「网络访问被拦」 |
| `--read-only` + `--tmpfs /tmp:rw,size=16m` | rootfs 只读，只给一小块可写 tmpfs | R5-03「容器隔离」 |
| `--cap-drop ALL` + `--security-opt no-new-privileges` | 去能力、禁提权 | R5-03「提权被拦」 |
| `--user 1000:1000` | 非 root | R5-03 |
| `--memory 256m --memory-swap 256m` | 内存硬限 | 部署文档 §2 `SANDBOX_MEMORY_MB` |
| `--cpus 0.5 --pids-limit 64` | CPU 限 + 防 fork 炸弹 | R5-02 资源约束 |

## 镜像不可用时会发生什么

`DockerSandboxBackend.available()` 同时探测 `docker info` 与 `docker image inspect agent-sandbox:latest`。
**任一不满足即降级**到 `RestrictedProcessBackend`，并且：

- 结果字段 `sandbox_backend=process-restricted`、`degraded=true`；
- 工作平台执行视图显示**沙箱降级**徽标；
- 降级后端**不是隔离边界**，只提供静态预检 + 临时目录隔离 + 硬超时。

这条降级路径**必须可见**，不得把受限子进程冒充为沙箱（《异常流程归纳》§2.4 降级可见性）。