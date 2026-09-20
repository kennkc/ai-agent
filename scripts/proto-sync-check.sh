#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# proto 契约生成物同步门禁
#
# 解决的问题（对应 docs/技术债台账.md 待办 §6-5 / §6-6）：
#   契约源 proto/*.proto 改了，但生成产物忘了重新生成 / 提交 —— 此前没有任何检查会发现。
#
# 用法：
#   bash scripts/proto-sync-check.sh python     # Python 侧：重生成 + 产物断言 + 导入自检
#   bash scripts/proto-sync-check.sh java       # Java  侧：比对生成物与入库产物（需先自行生成）
#   bash scripts/proto-sync-check.sh all        # 两侧都查（默认）
#
# Java 模式前置（本仓库 Java 产物入库在 src/main/java，故先生成到 target 再比对）：
#   cd services/java
#   ../../scripts/mvn-dev.sh -Pproto-gen -pl proto-contracts -am generate-sources
#
# 退出码：0 = 通过；1 = 存在不同步或生成失败
#
# 设计约束：只读仓库（不修改任何入库文件），临时产物写在 gitignored 的 .build/ 下。
# ─────────────────────────────────────────────────────────────────────────────
set -u

SCRIPT_DIR="${0%/*}"
[ "$SCRIPT_DIR" = "$0" ] && SCRIPT_DIR="."
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

PROTO_DIR="$ROOT/proto"
PY_EXPECT=12          # 6 个 _pb2.py + 6 个 _pb2_grpc.py
JAVA_SRC="$ROOT/services/java/proto-contracts/src/main/java"
JAVA_GEN_ROOT="$ROOT/services/java/proto-contracts/target/generated-sources"
JAVA_EXPECT=90
# 临时目录用「相对仓库根」的路径：本脚本会 cd 到仓库根后使用它。
# 必须如此 —— Windows 原生 Python 不认 Git Bash 的 POSIX 路径（/e/...），
# 传给 protoc 会报 “No such file or directory”（proto-gen.sh 也是同一坑）。
TMP_REL=".build/proto-sync-check"
PY_GEN_REL="services/python/nlp-service/generated"

MODE="${1:-all}"
FAIL=0

ok()  { echo "  [OK]   $*"; }
bad() { echo "  [FAIL] $*"; FAIL=1; }
info(){ echo "  [--]   $*"; }

pick_python() {
  # 探测顺序：仓库约定路径 services/python/venv（setup-python-env.ps1 默认建在这里）
  #          -> 历史兼容 nlp-service/.venv（早期脚本产物，已不再默认创建）
  #          -> 系统 python3（CI 走这条）
  if   [ -x "$ROOT/services/python/venv/bin/python" ];                        then echo "$ROOT/services/python/venv/bin/python"
  elif [ -x "$ROOT/services/python/venv/Scripts/python.exe" ];                then echo "$ROOT/services/python/venv/Scripts/python.exe"
  elif [ -x "$ROOT/services/python/nlp-service/.venv/bin/python" ];           then echo "$ROOT/services/python/nlp-service/.venv/bin/python"
  elif [ -x "$ROOT/services/python/nlp-service/.venv/Scripts/python.exe" ];   then echo "$ROOT/services/python/nlp-service/.venv/Scripts/python.exe"
  else echo "python3"; fi
}

# ── 契约源清单与指纹（两侧共用，便于人工核对）────────────────────────────────
show_sources() {
  echo "[契约源] $PROTO_DIR"
  local files n
  files="$(find "$PROTO_DIR" -name '*.proto' | sort)"
  n="$(printf '%s\n' "$files" | grep -c . )"
  printf '%s\n' "$files" | while IFS= read -r f; do
    [ -n "$f" ] && printf '         %-46s %s\n' "${f#$PROTO_DIR/}" "$(wc -l < "$f" | tr -d ' ') 行"
  done
  info "共 $n 个 .proto"
}

# ── Python 侧 ────────────────────────────────────────────────────────────────
check_python() {
  echo "[Python 侧] 重新生成并与既有产物比对"
  cd "$ROOT" || { bad "无法进入仓库根 $ROOT"; return; }
  local PY; PY="$(pick_python)"
  info "解释器：$PY"

  if ! "$PY" -c "import grpc_tools" >/dev/null 2>&1; then
    bad "grpc_tools 不可用 —— 请先安装构建期依赖：pip install -r services/python/nlp-service/requirements-dev.txt"
    return
  fi

  rm -rf "$TMP_REL"; mkdir -p "$TMP_REL"

  if ! "$PY" -m grpc_tools.protoc -I proto \
           --python_out="$TMP_REL" --grpc_python_out="$TMP_REL" \
           proto/common/v1/*.proto proto/session/v1/*.proto proto/brain/v1/*.proto \
           proto/sensor/v1/*.proto proto/body/v1/*.proto proto/limb/v1/*.proto ; then
    bad "protoc 生成失败"
    rm -rf "$TMP_REL"; return
  fi

  local n; n="$(find "$TMP_REL" -name '*.py' | grep -c . )"
  [ "$n" = "$PY_EXPECT" ] && ok "产出 $n 个文件（期望 $PY_EXPECT）" \
                          || bad "产出 $n 个文件，期望 $PY_EXPECT"

  if [ -d "$PY_GEN_REL" ]; then
    if diff -rq "$TMP_REL" "$PY_GEN_REL" >/dev/null 2>&1; then
      ok "与既有 generated/ 逐字节一致"
    else
      bad "与既有 generated/ 不一致（说明既有产物非由当前契约源生成）："
      diff -rq "$TMP_REL" "$PY_GEN_REL" 2>&1 | sed 's/^/         /'
      info "修复：bash scripts/proto-gen.sh"
    fi
  else
    info "仓库内无 generated/（已 gitignore，属正常）—— 跳过逐字节比对"
  fi

  # 导入自检：证明产物真的可被 Python 加载（覆盖“换机后 generated/ 缺失无人察觉”）
  # 用「cd 到产物目录」的方式，而不是 PYTHONPATH —— 实测（2026-09-17）Windows 原生 Python 的
  # PYTHONPATH **不认 Git Bash 的 POSIX 绝对路径**（/e/... → ModuleNotFoundError）；
  # 相对路径（generated、./generated、../x/generated）与 Windows 绝对路径（E:\...）均可用。
  # cd 后 cwd 会自动进入 sys.path，跨平台语义一致，故统一采用。
  local out
  if out="$( cd "$TMP_REL" && "$PY" -c '
import importlib
for m in ["common.v1.health_pb2","session.v1.session_pb2","brain.v1.brain_pb2",
          "sensor.v1.sensor_pb2","body.v1.body_pb2","limb.v1.limb_pb2"]:
    importlib.import_module(m)
print("imported-6-modules")
' 2>&1 )" && printf '%s' "$out" | grep -q "imported-6-modules"; then
    ok "6 个 _pb2 模块导入自检通过"
  else
    bad "导入自检失败："
    printf '%s\n' "$out" | sed 's/^/         /'
  fi

  rm -rf "$TMP_REL"
}

# ── Java 侧 ──────────────────────────────────────────────────────────────────
check_java() {
  echo "[Java 侧] 比对 target 生成物与入库产物（$JAVA_EXPECT 个文件）"

  # 注意：target/ 目录可能存在（若跑过其他 Maven 目标）但其中并无 protoc 产物，
  # 故判据是“能找到 .java”，而不是“目录存在”——否则会把 90 个入库产物全部误报为「契约已无」。
  local gen_n
  gen_n="$(find "$JAVA_GEN_ROOT" -name '*.java' 2>/dev/null | grep -c . )"
  if [ "$gen_n" = 0 ]; then
    bad "在 $JAVA_GEN_ROOT 下未找到任何 .java 生成物 —— 生成步骤尚未执行"
    info "请先生成：cd services/java && ../../scripts/mvn-dev.sh -Pproto-gen -pl proto-contracts -am generate-sources"
    return
  fi

  local total=0 missing=0 differ=0
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    local rel="${f#*com/agent/}"
    [ "$rel" = "$f" ] && continue          # 不在 com/agent/ 下，跳过
    total=$((total + 1))
    local tgt="$JAVA_SRC/com/agent/$rel"
    if [ ! -f "$tgt" ]; then
      missing=$((missing + 1)); echo "         [未入库]   com/agent/$rel"
    elif ! diff -q --strip-trailing-cr "$f" "$tgt" >/dev/null 2>&1; then
      differ=$((differ + 1));  echo "         [内容不同] com/agent/$rel"
    fi
  done < <(find "$JAVA_GEN_ROOT" -name '*.java' | sort)

  # 反向：入库有、生成物没有 → 说明契约已删字段/文件但产物未同步
  local orphan=0
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    local rel="${f#$JAVA_SRC/}"
    if ! find "$JAVA_GEN_ROOT" -path "*$rel" -print -quit | grep -q .; then
      orphan=$((orphan + 1)); echo "         [契约已无] $rel"
    fi
  done < <(find "$JAVA_SRC/com/agent" -name '*.java' | sort)

  [ "$total" -gt 0 ] && ok "生成物 $total 个文件" || bad "生成物为空 —— 生成步骤未生效"
  [ "$total" = "$JAVA_EXPECT" ] || info "注意：生成物 $total 个，文档记载为 $JAVA_EXPECT 个，请核对契约是否变更"
  [ "$missing" = 0 ] || bad "$missing 个生成物未入库 —— 需同步回 src/main/java"
  [ "$differ"  = 0 ] || bad "$differ 个生成物与入库内容不同 —— 契约源已变但产物未重新生成/提交"
  [ "$orphan"  = 0 ] || bad "$orphan 个入库产物在契约中已不存在 —— 需删除"
  [ $((missing + differ + orphan)) = 0 ] && ok "双向一致：契约源 ↔ 入库产物"
}

# ── 主流程 ───────────────────────────────────────────────────────────────────
echo "════════ proto 契约生成物同步门禁 ════════"
show_sources
echo

case "$MODE" in
  python) check_python ;;
  java)   check_java ;;
  all)    check_python; echo; check_java ;;
  *)      echo "用法：bash scripts/proto-sync-check.sh [python|java|all]"; exit 2 ;;
esac

echo
if [ "$FAIL" = 0 ]; then
  echo "════════ 结论：通过 ════════"
else
  echo "════════ 结论：未通过（契约源与生成物不同步）════════"
fi
exit "$FAIL"
