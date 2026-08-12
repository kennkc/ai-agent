#!/usr/bin/env bash
# =====================================================
#  Agent-Lifeform 测试环境一键启动脚本 (Linux)
#  用法: ./start-test.sh [all|infra|java|python|verify]
#  TEST 环境: 全量服务 + 真实 API 链路 + 契约校验
# =====================================================
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:-all}"

echo "=============================================="
echo " Agent-Lifeform TEST (Linux) - ${MODE}"
echo "=============================================="

start_infra() {
    echo "[1/4] 启动基础设施 (全量)..."
    docker compose -f "$ROOT/docker-compose.yml" up -d
    sleep 15
    docker compose -f "$ROOT/docker-compose.yml" ps
}

start_java() {
    echo "[2/4] 构建 Java 服务 (TEST profile)..."
    cd "$ROOT/services/java"
    mvn clean package -DskipTests -q -Ptest
    cd "$ROOT"
    echo "      TEST 模式: 各服务以 PROFILE=test 启动"
}

start_python() {
    echo "[3/4] 启动 Python 服务..."
    cd "$ROOT/services/python/nlp-service"
    nohup python -m uvicorn app.main:app --host 0.0.0.0 --port 8000 > /tmp/nlp.log 2>&1 &
    cd "$ROOT"
}

verify() {
    echo "[4/4] 集成验证 (TEST)..."
    bash "$ROOT/scripts/healthcheck.sh"
    echo "契约校验:"
    python3 "$ROOT/scripts/contract-check.py" --proto-dir "$ROOT/proto"
    echo "=== TEST 环境就绪 (DATA_SOURCE=api) ==="
}

case "$MODE" in
    infra)  start_infra ;;
    java)   start_java ;;
    python) start_python ;;
    verify) verify ;;
    all)    start_infra && start_java && start_python && verify ;;
    *) echo "用法: $0 [all|infra|java|python|verify]"; exit 1 ;;
esac
