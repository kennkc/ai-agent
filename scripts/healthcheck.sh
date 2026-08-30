#!/usr/bin/env bash
# AI Agent 生命体 - 健康检查脚本 (Phase 0 演示验证)
set -e

echo "=== Agent-Lifeform 健康检查 ==="

check_http() {
    local name="$1" url="$2" expect="$3"
    local code
    code=$(curl -s --noproxy "*" -o NUL -w "%{http_code}" --max-time 5 "$url" 2>/dev/null)
    [ -z "$code" ] && code="000"
    if [ "$code" = "$expect" ]; then
        echo "  ✅ $name: HTTP $code"
    else
        echo "  ❌ $name: HTTP $code (期望 $expect)"
    fi
}

check_port() {
    local name="$1" port="$2"
    if (echo >/dev/tcp/127.0.0.1/$port) 2>/dev/null; then
        echo "  ✅ $name: 端口 $port 开放"
    else
        echo "  ❌ $name: 端口 $port 未开放"
    fi
}

echo ""
echo "[基础设施]"
check_port "Redis" 6379
check_port "PostgreSQL" 5432
check_port "Qdrant" 6333
check_port "NATS" 4222
check_port "Nacos" 8848
check_port "MinIO" 9000
check_port "Kafka" 9092
check_port "Jaeger" 16686

echo ""
echo "[Java 服务]"
check_http "gateway-service (actuator)" "http://127.0.0.1:8080/actuator/health" "200"
check_http "session-manager (actuator)" "http://127.0.0.1:8081/actuator/health" "200"
check_http "sense-service (actuator)" "http://127.0.0.1:8082/actuator/health" "200"

echo ""
echo "[Python 服务]"
check_http "nlp-service (/healthz)" "http://127.0.0.1:8000/healthz" "200"

echo ""
echo "=== 功能验证 ==="
echo "  1. 创建会话:  curl -X POST http://127.0.0.1:8081/api/session"
echo "  2. 触发采集:  curl -X POST http://127.0.0.1:8082/api/sense/collect -H 'Content-Type: application/json' -d '{\"data_source\":\"https://example.com\"}'"
echo "  3. 意图识别:  curl -X POST http://127.0.0.1:8000/api/nlp/intent -H 'Content-Type: application/json' -d '{\"text\":\"今天天气怎么样\"}'"
echo ""
echo "=== 演示脚本: docs/demo/Phase0-DEMO.md ==="
