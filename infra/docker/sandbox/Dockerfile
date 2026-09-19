# ─────────────────────────────────────────────────────────────────────────────
# Agent-Lifeform · 沙箱基础镜像（Phase 5 四肢期 R5-03 / R5-05）
#
# 隔离由 **运行参数** 保证（见 tool-executor 的 DockerSandboxBackend）：
#   --network none / --read-only / --cap-drop ALL / --security-opt no-new-privileges
#   --user 1000:1000 / --memory 256m / --pids-limit 64
# 本镜像只负责提供一个"最小、无包管理器、非 root"的运行时，减少逃生面。
#
# 构建：docker build -t agent-sandbox:latest infra/docker/sandbox/
# ─────────────────────────────────────────────────────────────────────────────
FROM python:3.12-slim

# 只装沙箱内允许的标准库以外依赖（当前为空：仅标准库，白名单包按需添加）
# 刻意不安装 pip / setuptools 之外的网络与系统工具，减少逃逸面
RUN python -m pip uninstall -y pip setuptools wheel || true

# 非 root 运行（与 --user 1000:1000 对齐；双重保险）
RUN useradd -m -u 1000 sandbox
USER sandbox
WORKDIR /workspace

# 只读 rootfs：不能写镜像层；可写区由 --tmpfs /tmp 提供
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HOME=/tmp

CMD ["python3", "-"]