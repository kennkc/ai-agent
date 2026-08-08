#!/usr/bin/env bash
# AI Agent 生命体 - 一键启动脚本 (Phase 0)
# 用法: ./scripts/start.sh [all|infra|java|python]
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:-all}"

echo "=============================================="
echo " Agent-Lifeform Phase 0 - 生命体骨架启动"
echo "=============================================="

start_infra() {
    echo "[1/4] 启动基础设施 (Redis/PG/Qdrant/NATS/Nacos/MinIO)..."
    docker compose -f "$ROOT/docker-compose.yml" up -d
    echo "      等待基础设施健康..."
    sleep 15
    docker compose -f "$ROOT/docker-compose.yml" ps
}

start_java() {
    echo "[2/4] 构建并启动 Java 服务 (gateway/session/sense)..."
    cd "$ROOT/services/java"
    mvn clean package -DskipTests -q
    for svc in gateway-service session-manager sense-service; do
        echo "      启动 $svc ..."
        (cd "$svc" && nohup java -jar target/*.jar > /tmp/$svc.log 2>&1 &)
    done
    echo "      Java 服务已后台启动, 日志: /tmp/*.log"
}

start_python() {
    echo "[3/4] 启动 Python 服务 (nlp-service)..."
    cd "$ROOT/services/python/nlp-service"
    pip install -q -r requirements.txt
    nohup uvicorn app.main:app --host 0.0.0.0 --port 8000 > /tmp/nlp-service.log 2>&1 &
    echo "      nlp-service 已后台启动, 日志: /tmp/nlp-service.log"
}

verify() {
    echo "[4/4] 健康检查..."
    sleep 8
    "$ROOT/scripts/healthcheck.sh"
}

case "$MODE" in
    infra)  start_infra ;;
    java)   start_java ;;
    python) start_python ;;
    all)    start_infra && start_java && start_python && verify ;;
    *)      echo "用法: $0 [all|infra|java|python]"; exit 1 ;;
esac

echo "=============================================="
echo " ✅ Phase 0 启动完成！演示说明见 docs/demo/Phase0-DEMO.md"
echo "=============================================="
