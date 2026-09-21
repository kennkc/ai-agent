#!/usr/bin/env bash
# Agent-Lifeform - local start script
# Phase 0/1 hardening + VS1 + Vue work-platform + Phase 5 四肢期 + MC-01 collab-bus / wp-bff
#
# 端口单一来源：WP_BFF_PORT 只定义一次，WP_BFF_URL（Vite 代理目标）由它派生。
# 两个变量互不校验时，端口一错前端就静默回落 Mock（见 web/work-platform/vite.config.ts 的启动自检）。
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:-all}"
export JWT_SECRET="${JWT_SECRET:-agent-lifeform-dev-secret-key-2026-change-me-in-prod}"
export DEV_TOKEN_ENDPOINT_ENABLED="${DEV_TOKEN_ENDPOINT_ENABLED:-true}"
export NLP_BASE_URL="${NLP_BASE_URL:-http://127.0.0.1:8000}"
export BODY_BASE_URL="${BODY_BASE_URL:-http://127.0.0.1:8083}"
export WP_BFF_PORT="${WP_BFF_PORT:-8090}"
export WP_BFF_URL="${WP_BFF_URL:-http://127.0.0.1:$WP_BFF_PORT}"

# 前置门禁：跨服务超时预算（contracts/timeout-budget.yaml）
#
# 为什么放在启动前：超时错配**不触发任何功能断言**（单测全绿、契约 0 FAIL），
# 只在运行期表现为「把一个只是慢的接口叙述成不可用」。放在启动前，能让它在
# 「你正要看效果」之前就暴露。失败不静默忽略，也不硬拦 —— 需要显式 SKIP_BUDGET_GATE=1 跳过。
preflight() {
  local py="${WP_PYTHON_BIN:-python3}"
  if ! command -v "$py" >/dev/null 2>&1; then
    echo "[0/6] 前置门禁：(跳过：未找到 $py)"
    return 0
  fi
  # 用相对路径调用：Git Bash(MSYS) 会把绝对路径参数改写成 `E:\e\AI\...`，
  # 传给 Windows 版 python.exe 就变成不存在的路径（与本仓避坑清单同源）。
  local rc=0
  echo "[0/6] 超时预算门禁..."
  if ! ( cd "$ROOT" && "$py" scripts/timeout-budget-check.py ); then
    rc=1
    echo "  !! 超时预算门禁未通过：请先修 contracts/timeout-budget.yaml 或改回命名档位"
  fi
  echo "[0/6] 文档一致性门禁..."
  if ! ( cd "$ROOT" && "$py" scripts/doc-consistency-check.py --no-twin ); then
    rc=1
    echo "  !! 文档一致性门禁未通过：编号撞号 / 章节号重复，请先修"
  fi
  if [ "$rc" != "0" ]; then
    if [ "${SKIP_BUDGET_GATE:-0}" = "1" ]; then
      echo "  !! 门禁未通过，但已按 SKIP_BUDGET_GATE=1 继续（请勿把本次结果当作可信基线）"
      return 0
    fi
    echo "  !! 跳过请设 SKIP_BUDGET_GATE=1"
    return 1
  fi
}

JAVA_SERVICES="gateway-service session-manager sense-service body-service tool-executor collab-bus"

start_infra() { echo "[1/6] infrastructure..."; docker compose -f "$ROOT/docker-compose.yml" up -d; sleep 15; docker compose -f "$ROOT/docker-compose.yml" ps; }

start_java() {
  echo "[2/6] Java services: $JAVA_SERVICES"
  cd "$ROOT/services/java"
  mvn clean package -DskipTests -q
  for svc in $JAVA_SERVICES; do (cd "$svc" && nohup java -jar target/*.jar > "/tmp/$svc.log" 2>&1 &); done
}

start_python() {
  echo "[3/6] nlp-service..."
  cd "$ROOT/services/python/nlp-service"
  if [ -x "$ROOT/services/python/venv/Scripts/python.exe" ]; then PY="$ROOT/services/python/venv/Scripts/python.exe"
  elif [ -x "$ROOT/services/python/venv/bin/python" ]; then PY="$ROOT/services/python/venv/bin/python"
  else PY="python3"; "$PY" -m pip install -q -r requirements.txt; fi
  nohup "$PY" -m uvicorn app.main:app --host 0.0.0.0 --port 8000 >/tmp/nlp-service.log 2>&1 &
}

# wp-bff：工作平台 BFF（中间件探针 / 链路追踪 / 知识库 / 大脑 / 工具域）。
# 缺了它，前端 API 模式会全线回落 Mock —— 曾经就是这样静默失效的，故单独成步并显式回显端口。
start_bff() {
  echo "[4/6] wp-bff on :$WP_BFF_PORT  (vite 代理目标 = $WP_BFF_URL)"
  cd "$ROOT/services/node/wp-bff"
  if [ ! -d node_modules ]; then npm install --silent; fi
  nohup env WP_BFF_PORT="$WP_BFF_PORT" "${WP_NODE_BIN:-node}" server.js >/tmp/wp-bff.log 2>&1 &
}

start_frontend() {
  echo "[5/6] Vue work-platform on :${WP_DEV_PORT:-3001} ..."
  cd "$ROOT/web/work-platform"
  if [ ! -d node_modules ]; then npm install --silent; fi
  nohup env WP_BFF_URL="$WP_BFF_URL" npm run dev -- --host 0.0.0.0 --port "${WP_DEV_PORT:-3001}" >/tmp/work-platform.log 2>&1 &
}

verify() { echo "[6/6] health check..."; sleep 8; "$ROOT/scripts/healthcheck.sh"; }

case "$MODE" in
  gate) preflight ;;
  infra) start_infra ;;
  java) start_java ;;
  python) start_python ;;
  bff) start_bff ;;
  frontend) start_frontend ;;
  all) preflight && start_infra && start_java && start_python && start_bff && start_frontend && verify ;;
  *) echo "usage: $0 [all|gate|infra|java|python|bff|frontend]"; exit 1 ;;
esac