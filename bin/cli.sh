#!/usr/bin/env bash
# =============================================================================
# HutuLock CLI 启动脚本
#
# 用法：
#   ./bin/cli.sh                                    交互模式
#   ./bin/cli.sh 127.0.0.1:8881 127.0.0.1:8882     启动时自动连接
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
JAR="${PROJECT_DIR}/hutulock-cli/target/hutulock-cli-1.0.0.jar"

if [ ! -f "${JAR}" ]; then
    echo "[ERROR] JAR not found: ${JAR}"
    echo "Run: mvn clean package -DskipTests"
    exit 1
fi

JVM_OPTS="-Xms64m -Xmx128m -Dfile.encoding=UTF-8"

exec java ${JVM_OPTS} -jar "${JAR}" "$@"
