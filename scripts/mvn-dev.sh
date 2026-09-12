#!/usr/bin/env bash
# Maven 本地直调包装（Windows + Git Bash 绕过 mvn.cmd 损坏 + JAVA_HOME 被系统指向 JDK8 的问题）
# 用法: scripts/mvn-dev.sh clean package -DskipTests
# 可通过 LIFEORM_JAVA_HOME / LIFEORM_MVN_HOME 覆盖探测结果
set -e

detect_java_home() {
  if [ -n "$LIFEORM_JAVA_HOME" ] && [ -x "$LIFEORM_JAVA_HOME/bin/java" ]; then echo "$LIFEORM_JAVA_HOME"; return; fi
  for c in "E:/software/java/jdk-21" "C:/Program Files/Java/jdk-21" "/c/Program Files/Java/jdk-21"; do
    if [ -x "$c/bin/java" ]; then echo "$c"; return; fi
  done
  echo "$JAVA_HOME"
}

JAVA_HOME="$(detect_java_home)"
MVN_HOME="${LIFEORM_MVN_HOME:-${MVN_HOME:-E:/software/maven/3.9/apache-maven-3.9.11-bin/apache-maven-3.9.11}}"
if [ ! -f "$MVN_HOME/boot/plexus-classworlds-2.9.0.jar" ]; then
  echo "Maven home not found: $MVN_HOME (set LIFEORM_MVN_HOME)" >&2
  exit 1
fi
export JAVA_HOME

"$JAVA_HOME/bin/java" \
  -classpath "$MVN_HOME/boot/plexus-classworlds-2.9.0.jar" \
  "-Dclassworlds.conf=$MVN_HOME/bin/m2.conf" \
  "-Dmaven.home=$MVN_HOME" \
  "-Dmaven.multiModuleProjectDirectory=$PWD" \
  org.codehaus.plexus.classworlds.launcher.Launcher "$@"
