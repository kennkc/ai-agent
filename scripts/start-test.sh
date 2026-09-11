#!/usr/bin/env bash
# Agent-Lifeform - test environment start script (Linux)
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:-all}"
export JWT_SECRET="${JWT_SECRET:-agent-lifeform-test-secret-key-2026-change-me-in-prod}"
export DEV_TOKEN_ENDPOINT_ENABLED="${DEV_TOKEN_ENDPOINT_ENABLED:-false}"
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-test}"

start_infra() {
  docker compose -f "$ROOT/docker-compose.yml" up -d
  sleep 15
  docker compose -f "$ROOT/docker-compose.yml" ps
}

start_java() {
  cd "$ROOT/services/java"
  mvn clean package -DskipTests -q
  for svc in gateway-service session-manager sense-service body-service; do
    (cd "$svc" && nohup java -jar target/*.jar > "/tmp/$svc.log" 2>&1 &)
  done
}

start_python() {
  cd "$ROOT/services/python/nlp-service"
  if [ -x "$ROOT/services/python/venv/bin/python" ]; then PY="$ROOT/services/python/venv/bin/python"; else PY="python3"; fi
  nohup "$PY" -m uvicorn app.main:app --host 0.0.0.0 --port 8000 > /tmp/nlp.log 2>&1 &
}

verify() {
  bash "$ROOT/scripts/healthcheck.sh"
  python3 "$ROOT/scripts/contract-check.py"
  python3 "$ROOT/scripts/contract-check.py" --work-platform --openapi "$ROOT/contracts/work-platform-bff-openapi.yaml"
}

case "$MODE" in
  infra) start_infra ;;
  java) start_java ;;
  python) start_python ;;
  verify) verify ;;
  all) start_infra && start_java && start_python && verify ;;
  *) echo "usage: $0 [all|infra|java|python|verify]"; exit 1 ;;
esac
