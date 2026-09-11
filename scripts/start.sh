#!/usr/bin/env bash
# Agent-Lifeform - local start script (Phase 0/1 hardening + VS1)
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:-all}"
export JWT_SECRET="${JWT_SECRET:-agent-lifeform-dev-secret-key-2026-change-me-in-prod}"
export DEV_TOKEN_ENDPOINT_ENABLED="${DEV_TOKEN_ENDPOINT_ENABLED:-true}"
export NLP_BASE_URL="${NLP_BASE_URL:-http://127.0.0.1:8000}"
export BODY_BASE_URL="${BODY_BASE_URL:-http://127.0.0.1:8083}"

start_infra() {
  echo "[1/4] starting infrastructure..."
  docker compose -f "$ROOT/docker-compose.yml" up -d
  sleep 15
  docker compose -f "$ROOT/docker-compose.yml" ps
}

start_java() {
  echo "[2/4] building and starting Java services..."
  cd "$ROOT/services/java"
  mvn clean package -DskipTests -q
  for svc in gateway-service session-manager sense-service body-service; do
    (cd "$svc" && nohup java -jar target/*.jar > "/tmp/$svc.log" 2>&1 &)
  done
}

start_python() {
  echo "[3/4] starting nlp-service..."
  cd "$ROOT/services/python/nlp-service"
  if [ -x "$ROOT/services/python/venv/Scripts/python.exe" ]; then
    PY="$ROOT/services/python/venv/Scripts/python.exe"
  elif [ -x "$ROOT/services/python/venv/bin/python" ]; then
    PY="$ROOT/services/python/venv/bin/python"
  else
    PY="python3"
    "$PY" -m pip install -q -r requirements.txt
  fi
  nohup "$PY" -m uvicorn app.main:app --host 0.0.0.0 --port 8000 > /tmp/nlp-service.log 2>&1 &
}

verify() {
  echo "[4/4] health check..."
  sleep 8
  "$ROOT/scripts/healthcheck.sh"
}

case "$MODE" in
  infra) start_infra ;;
  java) start_java ;;
  python) start_python ;;
  all) start_infra && start_java && start_python && verify ;;
  *) echo "usage: $0 [all|infra|java|python]"; exit 1 ;;
esac
