#!/usr/bin/env bash
# =============================================================================
# HutuLock Server 启动脚本
#
# 用法：
#   ./bin/server.sh <nodeId> <clientPort> <raftPort> [peer ...] [选项]
#
# 选项：
#   --proxy <types>   启用代理增强，逗号分隔（logging / metrics / all）
#                     等价于 JVM 参数 -Dhutulock.proxy=<types>
#   --config <path>   指定 hutulock.yml 路径（默认 classpath 内置）
#   --jvm <opts>      追加 JVM 参数（引号包裹）
#
# 示例（单节点）：
#   ./bin/server.sh node1 8881 9881
#
# 示例（开启代理）：
#   ./bin/server.sh node1 8881 9881 --proxy logging,metrics
#
# 示例（3 节点集群，分别在三台机器上执行）：
#   ./bin/server.sh node1 8881 9881 node2:192.168.1.2:9882 node3:192.168.1.3:9883
#   ./bin/server.sh node2 8882 9882 node1:192.168.1.1:9881 node3:192.168.1.3:9883
#   ./bin/server.sh node3 8883 9883 node1:192.168.1.1:9881 node2:192.168.1.2:9882
# =============================================================================

set -euo pipefail

# ---- 路径解析 ----
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
JAR="${PROJECT_DIR}/hutulock-server/target/hutulock-server-1.0.0.jar"
LOG_DIR="${PROJECT_DIR}/logs"

# ---- 参数校验 ----
if [ $# -lt 3 ]; then
    echo "Usage: $0 <nodeId> <clientPort> <raftPort> [peerId:host:raftPort ...] [--proxy types] [--config path] [--jvm opts]"
    echo ""
    echo "Examples:"
    echo "  $0 node1 8881 9881"
    echo "  $0 node1 8881 9881 --proxy logging,metrics"
    echo "  $0 node1 8881 9881 node2:127.0.0.1:9882 node3:127.0.0.1:9883 --proxy all"
    exit 1
fi

NODE_ID="$1"
CLIENT_PORT="$2"
RAFT_PORT="$3"
shift 3

# ---- 解析 peers 和选项 ----
PEERS=()
PROXY_TYPES=""
EXTRA_JVM=""
CONFIG_PATH=""

while [ $# -gt 0 ]; do
    case "$1" in
        --proxy)
            PROXY_TYPES="$2"; shift 2 ;;
        --config)
            CONFIG_PATH="$2"; shift 2 ;;
        --jvm)
            EXTRA_JVM="$2"; shift 2 ;;
        --*)
            echo "[ERROR] Unknown option: $1"; exit 1 ;;
        *)
            PEERS+=("$1"); shift ;;
    esac
done

# ---- 检查 JAR ----
if [ ! -f "${JAR}" ]; then
    echo "[ERROR] JAR not found: ${JAR}"
    echo "Please build first: mvn clean package -DskipTests"
    exit 1
fi

# ---- JVM 参数 ----
JVM_OPTS="${JVM_OPTS:-}"
JVM_OPTS="${JVM_OPTS} -Xms256m -Xmx512m"
JVM_OPTS="${JVM_OPTS} -XX:+UseG1GC"
JVM_OPTS="${JVM_OPTS} -XX:+HeapDumpOnOutOfMemoryError"
JVM_OPTS="${JVM_OPTS} -XX:HeapDumpPath=${LOG_DIR}/heapdump-${NODE_ID}.hprof"
JVM_OPTS="${JVM_OPTS} -Dfile.encoding=UTF-8"

# 代理开关
if [ -n "${PROXY_TYPES}" ]; then
    JVM_OPTS="${JVM_OPTS} -Dhutulock.proxy=${PROXY_TYPES}"
fi

# 自定义配置文件
if [ -n "${CONFIG_PATH}" ]; then
    if [ ! -f "${CONFIG_PATH}" ]; then
        echo "[ERROR] Config file not found: ${CONFIG_PATH}"
        exit 1
    fi
    JVM_OPTS="${JVM_OPTS} -cp $(dirname ${CONFIG_PATH}):${JAR}"
fi

# 追加用户自定义 JVM 参数
if [ -n "${EXTRA_JVM}" ]; then
    JVM_OPTS="${JVM_OPTS} ${EXTRA_JVM}"
fi

# ---- 创建日志目录 ----
mkdir -p "${LOG_DIR}"

# ---- 启动信息 ----
echo "============================================"
echo " HutuLock Server"
echo " Node ID    : ${NODE_ID}"
echo " Client Port: ${CLIENT_PORT}"
echo " Raft Port  : ${RAFT_PORT}"
echo " Peers      : ${PEERS[*]:-none}"
echo " Proxy      : ${PROXY_TYPES:-disabled}"
echo " JAR        : ${JAR}"
echo " Log Dir    : ${LOG_DIR}"
echo "============================================"

exec java ${JVM_OPTS} -jar "${JAR}" "${NODE_ID}" "${CLIENT_PORT}" "${RAFT_PORT}" "${PEERS[@]:-}"
