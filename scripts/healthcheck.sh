#!/usr/bin/env bash
set -e
# 注意 `|| true`：本脚本的职责恰恰是"服务挂掉时报告挂掉"，而 curl 连不上会以非 0（如 7）退出。
# 在命令替换里，赋值语句的退出码 = curl 的退出码，配合 `set -e` 会让脚本在**第一个不可达服务处直接终止**——
# 于是它永远报不出"谁挂了"，只会安静地少输出一半。实测踩过：java 段一开始就 exit=7。
check_http() { local name="$1" url="$2" expect="$3"; local code; code=$(curl -s --noproxy "*" -o /dev/null -w "%{http_code}" --max-time 5 "$url" 2>/dev/null || true); [ -z "$code" ] && code="000"; if [ "$code" = "$expect" ]; then echo "  OK  $name: HTTP $code"; else echo "  BAD $name: HTTP $code (expected $expect)"; fi; }
check_port() { local name="$1" port="$2"; if (echo >/dev/tcp/127.0.0.1/$port) 2>/dev/null; then echo "  OK  $name: port $port"; else echo "  BAD $name: port $port"; fi; }
echo "[infrastructure]"; for p in "Redis:6379" "PostgreSQL:5432" "Qdrant:6333" "NATS:4222" "Nacos:8848" "MinIO:9000" "Kafka:9092" "Jaeger:16686"; do check_port "${p%%:*}" "${p##*:}"; done
echo "[java services]"; check_http gateway-service http://127.0.0.1:8080/actuator/health 200; check_http session-manager http://127.0.0.1:8081/actuator/health 200; check_http sense-service http://127.0.0.1:8082/actuator/health 200; check_http body-service http://127.0.0.1:8083/actuator/health 200; check_http tool-executor http://127.0.0.1:8084/api/tool/health 200
echo "[grpc health]"; check_port gateway-grpc 9091; check_port session-grpc 19092; check_port sense-grpc 9093; check_port body-grpc 9094; check_port tool-grpc 9095
echo "[python + frontend + bff]"; check_http nlp-service http://127.0.0.1:8000/healthz 200; check_http work-platform http://127.0.0.1:3001 200; check_http wp-bff http://127.0.0.1:8090/api/wp/healthz 200
