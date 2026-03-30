#!/usr/bin/env bash
# =============================================================================
# HutuLock CLI 启动脚本
#
# 用法：
#   ./bin/cli.sh                                    交互模式
#   ./bin/cli.sh 127.0.0.1:8881 127.0.0.1:8882     启动时自动连接
#   ./bin/cli.sh --jvm "-Xmx256m" 127.0.0.1:8881   自定义 JVM 参数
#
# 选项：
#   --jvm <opts>   追加 JVM 参数（引号包裹）
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
JAR="${PROJECT_DIR}/hutulock-cli/target/hutulock-cli-1.0.1-SNAPSHOT.jar"

if [ ! -f "${JAR}" ]; then
    echo "[ERROR] JAR not found: ${JAR}"
    echo "Run: mvn clean package -DskipTests"
    exit 1
fi

# ---- 解析选项 ----
EXTRA_JVM=""
PASS_ARGS=()

while [ $# -gt 0 ]; do
    case "$1" in
        --jvm)
            EXTRA_JVM="$2"; shift 2 ;;
        --*)
            echo "[ERROR] Unknown option: $1"; exit 1 ;;
        *)
            PASS_ARGS+=("$1"); shift ;;
    esac
done

JVM_OPTS="-Xms64m -Xmx128m -Dfile.encoding=UTF-8 ${EXTRA_JVM}"

exec java ${JVM_OPTS} -jar "${JAR}" "${PASS_ARGS[@]:-}"
