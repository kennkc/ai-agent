#!/usr/bin/env bash
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/services/python/nlp-service/generated"
mkdir -p "$OUT"
if [ -x "$ROOT/services/python/venv/bin/python" ]; then PY="$ROOT/services/python/venv/bin/python"; elif [ -x "$ROOT/services/python/venv/Scripts/python.exe" ]; then PY="$ROOT/services/python/venv/Scripts/python.exe"; else PY="python3"; fi
"$PY" -m grpc_tools.protoc -I "$ROOT/proto" --python_out="$OUT" --grpc_python_out="$OUT" \
  "$ROOT"/proto/common/v1/*.proto "$ROOT"/proto/session/v1/*.proto "$ROOT"/proto/brain/v1/*.proto \
  "$ROOT"/proto/sensor/v1/*.proto "$ROOT"/proto/body/v1/*.proto "$ROOT"/proto/limb/v1/*.proto
echo "Python proto generated to $OUT"
