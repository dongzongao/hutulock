#!/usr/bin/env bash
# =============================================================================
# HutuLock 本地 3 节点集群一键启动脚本（开发/测试用）
#
# 用法：
#   ./bin/cluster.sh start   [--proxy types]   启动 3 节点集群
#   ./bin/cluster.sh stop                      停止所有节点
#   ./bin/cluster.sh restart [--proxy types]   重启集群
#   ./bin/cluster.sh status                    查看节点状态
#   ./bin/cluster.sh logs    [nodeId]          查看日志（默认 node1）
#
# 选项：
#   --proxy <types>   启用代理增强，逗号分隔（logging / metrics / all）
#
# 示例：
#   ./bin/cluster.sh start
#   ./bin/cluster.sh start --proxy logging,metrics
#   ./bin/cluster.sh start --proxy all
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
JAR="${PROJECT_DIR}/hutulock-server/target/hutulock-server-1.0.2-SNAPSHOT.jar"
LOG_DIR="${PROJECT_DIR}/logs"
PID_DIR="${PROJECT_DIR}/run"

# ---- 集群配置 ----
# nodeId → "clientPort:raftPort:metricsPort"
declare -A NODE_CFG=(
    ["node1"]="8881:9881:9090"
    ["node2"]="8882:9882:9091"
    ["node3"]="8883:9883:9092"
)
NODE_ORDER=("node1" "node2" "node3")

# 每个节点的 Raft peers（排除自身）
declare -A NODE_PEERS=(
    ["node1"]="node2:127.0.0.1:9882 node3:127.0.0.1:9883"
    ["node2"]="node1:127.0.0.1:9881 node3:127.0.0.1:9883"
    ["node3"]="node1:127.0.0.1:9881 node2:127.0.0.1:9882"
)

JVM_BASE="-Xms128m -Xmx256m -XX:+UseG1GC -Dfile.encoding=UTF-8"

# ---- 工具函数 ----

check_jar() {
    if [ ! -f "${JAR}" ]; then
        echo "[ERROR] JAR not found: ${JAR}"
        echo "Run: mvn clean package -DskipTests"
        exit 1
    fi
}

# 从 "clientPort:raftPort:metricsPort" 中提取字段
get_client_port()  { echo "${1%%:*}"; }
get_raft_port()    { echo "${1#*:}" | cut -d: -f1; }
get_metrics_port() { echo "${1##*:}"; }

start_node() {
    local node_id="$1"
    local cfg="${NODE_CFG[$node_id]}"
    local client_port; client_port=$(get_client_port "$cfg")
    local raft_port;   raft_port=$(get_raft_port "$cfg")
    local peers="${NODE_PEERS[$node_id]}"
    local proxy_types="${2:-}"
    local pid_file="${PID_DIR}/${node_id}.pid"
    local log_file="${LOG_DIR}/${node_id}.log"

    if [ -f "${pid_file}" ] && kill -0 "$(cat "${pid_file}")" 2>/dev/null; then
        echo "[SKIP] ${node_id} is already running (PID=$(cat "${pid_file}"))"
        return
    fi

    mkdir -p "${LOG_DIR}" "${PID_DIR}"

    local jvm_opts="${JVM_BASE}"
    jvm_opts="${jvm_opts} -XX:HeapDumpPath=${LOG_DIR}/heapdump-${node_id}.hprof"
    if [ -n "${proxy_types}" ]; then
        jvm_opts="${jvm_opts} -Dhutulock.proxy=${proxy_types}"
    fi

    # shellcheck disable=SC2086
    nohup java ${jvm_opts} -jar "${JAR}" \
        "${node_id}" "${client_port}" "${raft_port}" ${peers} \
        > "${log_file}" 2>&1 &

    echo $! > "${pid_file}"
    echo "[OK]   ${node_id} started  PID=$!  client=:${client_port}  raft=:${raft_port}  proxy=${proxy_types:-disabled}"
}

stop_node() {
    local node_id="$1"
    local pid_file="${PID_DIR}/${node_id}.pid"

    if [ ! -f "${pid_file}" ]; then
        echo "[SKIP] ${node_id} is not running"
        return
    fi

    local pid
    pid=$(cat "${pid_file}")
    if kill -0 "${pid}" 2>/dev/null; then
        kill "${pid}"
        rm -f "${pid_file}"
        echo "[OK]   ${node_id} stopped (PID=${pid})"
    else
        rm -f "${pid_file}"
        echo "[SKIP] ${node_id} process not found, cleaned up PID file"
    fi
}

status_node() {
    local node_id="$1"
    local cfg="${NODE_CFG[$node_id]}"
    local client_port; client_port=$(get_client_port "$cfg")
    local metrics_port; metrics_port=$(get_metrics_port "$cfg")
    local pid_file="${PID_DIR}/${node_id}.pid"

    if [ -f "${pid_file}" ] && kill -0 "$(cat "${pid_file}")" 2>/dev/null; then
        local pid
        pid=$(cat "${pid_file}")
        local health="unknown"
        if command -v curl &>/dev/null; then
            health=$(curl -sf "http://127.0.0.1:${metrics_port}/health" 2>/dev/null \
                | grep -o '"status":"[^"]*"' | cut -d'"' -f4 || echo "unreachable")
        fi
        echo "  ${node_id}: RUNNING  PID=${pid}  client=:${client_port}  metrics=:${metrics_port}  health=${health}"
    else
        echo "  ${node_id}: STOPPED"
    fi
}

# ---- 主逻辑 ----

CMD="${1:-help}"
shift || true

# 解析剩余选项
PROXY_TYPES=""
LOG_NODE="node1"
while [ $# -gt 0 ]; do
    case "$1" in
        --proxy) PROXY_TYPES="$2"; shift 2 ;;
        *)       LOG_NODE="$1"; shift ;;
    esac
done

case "${CMD}" in
    start)
        check_jar
        echo "Starting HutuLock cluster (3 nodes)  proxy=${PROXY_TYPES:-disabled} ..."
        for node in "${NODE_ORDER[@]}"; do
            start_node "${node}" "${PROXY_TYPES}"
            sleep 0.3
        done
        echo ""
        echo "Waiting for leader election (~1.5s)..."
        sleep 1.5
        echo ""
        echo "Logs  : ${LOG_DIR}/"
        echo "Status: $0 status"
        echo "CLI   : ./bin/cli.sh 127.0.0.1:8881 127.0.0.1:8882 127.0.0.1:8883"
        ;;

    stop)
        echo "Stopping HutuLock cluster..."
        for node in "${NODE_ORDER[@]}"; do
            stop_node "${node}"
        done
        echo "Cluster stopped."
        ;;

    restart)
        "$0" stop
        sleep 1
        "$0" start ${PROXY_TYPES:+--proxy "${PROXY_TYPES}"}
        ;;

    status)
        echo "HutuLock Cluster Status:"
        for node in "${NODE_ORDER[@]}"; do
            status_node "${node}"
        done
        ;;

    logs)
        LOG_FILE="${LOG_DIR}/${LOG_NODE}.log"
        if [ ! -f "${LOG_FILE}" ]; then
            echo "[ERROR] Log file not found: ${LOG_FILE}"
            exit 1
        fi
        tail -f "${LOG_FILE}"
        ;;

    *)
        echo "Usage: $0 {start|stop|restart|status|logs} [options]"
        echo ""
        echo "  start   [--proxy logging,metrics]   Start 3-node local cluster"
        echo "  stop                                 Stop all nodes"
        echo "  restart [--proxy all]                Restart cluster"
        echo "  status                               Show node status"
        echo "  logs    [nodeId]                     Tail node log (default: node1)"
        exit 1
        ;;
esac
