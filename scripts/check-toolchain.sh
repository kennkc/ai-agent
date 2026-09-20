#!/usr/bin/env bash
# 校验本机开发/部署工具版本是否满足 versions.lock.json 的最低要求。
set -u

MANIFEST="${1:-$(dirname "$0")/../versions.lock.json}"
fail=0
warn=0

check_cmd() {
  local name="$1"
  local command="$2"
  shift 2
  if command -v "$command" >/dev/null 2>&1; then
    "$command" "$@" 2>&1 | head -n 1 | sed "s/^/PASS ${name}: /"
  else
    echo "FAIL ${name}: 未找到命令 $command"
    fail=$((fail + 1))
  fi
}

echo "=== Agent-Lifeform toolchain check ==="
check_cmd Java java -version
check_cmd Maven mvn -version
check_cmd Python python --version
check_cmd Node node --version
check_cmd npm npm --version
check_cmd Git git --version

if command -v docker >/dev/null 2>&1; then
  docker --version | sed 's/^/PASS Docker: /'
  if docker compose version >/dev/null 2>&1; then
    docker compose version | sed 's/^/PASS Docker Compose: /'
  else
    echo "WARN Docker Compose: 未检测到 Compose v2 插件"
    warn=$((warn + 1))
  fi
else
  echo "WARN Docker: CLI 不在此 shell PATH 中，应用服务控制页不可用"
  warn=$((warn + 1))
fi

echo
echo "版本基线文件：$MANIFEST"
echo "结果：FAIL=$fail WARN=$warn"
exit $((fail > 0 ? 1 : 0))