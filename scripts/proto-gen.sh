#!/usr/bin/env bash
# Python 侧 proto 生成（12 个 _pb2/_pb2_grpc 文件 → services/python/nlp-service/generated/，已 gitignore）
# Java 侧产物（90 个文件，已入库）由 Maven 负责：../../scripts/mvn-dev.sh -pl proto-contracts -am clean package -Pproto-gen
# 详见 docs/proto契约使用说明.md
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ -x "$ROOT/services/python/venv/bin/python" ]; then PY="$ROOT/services/python/venv/bin/python"; elif [ -x "$ROOT/services/python/venv/Scripts/python.exe" ]; then PY="$ROOT/services/python/venv/Scripts/python.exe"; else PY="python3"; fi
OUT_REL="services/python/nlp-service/generated"
mkdir -p "$ROOT/$OUT_REL"
# 必须 cd 到仓库根并用相对路径：Git Bash 下 $(pwd) 得到 POSIX 路径（/e/ai_workspace/...），
# 直接传给原生 Windows Python 时 protoc 无法解析（报 --proto_path 不存在 / 文件不在 proto_path 内）。
cd "$ROOT"
"$PY" -m grpc_tools.protoc -I proto --python_out="$OUT_REL" --grpc_python_out="$OUT_REL" \
  proto/common/v1/*.proto proto/session/v1/*.proto proto/brain/v1/*.proto \
  proto/sensor/v1/*.proto proto/body/v1/*.proto proto/limb/v1/*.proto
echo "Python proto generated to $ROOT/$OUT_REL"
