#!/usr/bin/env bash
set -e
echo "=== Agent-Lifeform health check ==="
check_http() { local name="$1" url="$2" expect="$3"; local code; code=$(curl -s --noproxy "*" -o NUL -w "%{http_code}" --max-time 5 "$url" 2>/dev/null); [ -z "$code" ] && code="000"; if [ "$code" = "$expect" ]; then echo "  OK  $name: HTTP $code"; else echo "  BAD $name: HTTP $code (expected $expect)"; fi; }
check_port() { local name="$1" port="$2"; if (echo >/dev/tcp/127.0.0.1/$port) 2>/dev/null; then echo "  OK  $name: port $port"; else echo "  BAD $name: port $port"; fi; }
echo "[infrastructure]"; check_port Redis 6379; check_port PostgreSQL 5432; check_port Qdrant 6333; check_port NATS 4222; check_port Nacos 8848; check_port MinIO 9000; check_port Kafka 9092; check_port Jaeger 16686
echo "[java services]"; check_http gateway-service http://127.0.0.1:8080/actuator/health 200; check_http session-manager http://127.0.0.1:8081/actuator/health 200; check_http sense-service http://127.0.0.1:8082/actuator/health 200; check_http body-service http://127.0.0.1:8083/actuator/health 200
echo "[grpc health]"; check_port gateway-grpc 9091; check_port session-grpc 9092; check_port sense-grpc 9093; check_port body-grpc 9094
echo "[python service]"; check_http nlp-service http://127.0.0.1:8000/healthz 200
