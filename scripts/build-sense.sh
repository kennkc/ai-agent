#!/usr/bin/env bash
# 分离式构建入口：供 PowerShell Start-Process 托管调用，避免被会话回收。
# 用法: scripts/build-sense.sh <maven-args...>
# 结果写入 .build/sense-build.log，退出码写入 .build/sense-build.exit
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="$ROOT/.build"
mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/sense-build.log"
EXIT_FILE="$LOG_DIR/sense-build.exit"

cd "$ROOT/services/java" || exit 90
: > "$LOG"
bash "$ROOT/scripts/mvn-dev.sh" "$@" >> "$LOG" 2>&1
code=$?
echo "$code" > "$EXIT_FILE"
exit "$code"
