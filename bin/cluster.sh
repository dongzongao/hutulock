#!/usr/bin/env bash
# =============================================================================
# HutuLock 本地 3 节点集群一键启动脚本（开发/测试用）
#
# 用法：
#   ./bin/cluster.sh start    启动 3 节点集群
#   ./bin/cluster.sh stop     停止所有节点
#   ./bin/cluster.sh status   查看节点状态
#   ./bin/cluster.sh restart  重启集群
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
JAR="${PROJECT_DIR}/hutulock-server/target/hutulock-server-1.0.0.jar"
LOG_DIR="${PROJECT_DIR}/logs"
PID_DIR="${PROJECT_DIR}/run"

# 集群配置
declare -A NODES=(
    ["node1"]="8881:9881"
    ["node2"]="8882:9882"
    ["node3"]="8883:9883"
)
PEERS="node2:127.0.0.1:9882 node3:127.0.0.1:9883"
PEERS_NODE2="node1:127.0.0.1:9881 node3:127.0.0.1:9883"
PEERS_NODE3="node1:127.0.0.1:9881 node2:127.0.0.1:9882"

JVM_OPTS="-Xms128m -Xmx256m -XX:+UseG1GC -Dfile.encoding=UTF-8"

# ---- 工具函数 ----

check_jar() {
    if [ ! -f "${JAR}" ]; then
        echo "[ERROR] JAR not found: ${JAR}"
        echo "Run: mvn clean package -DskipTests"
        exit 1
    fi
}

start_node() {
    local node_id="$1"
    local client_port="$2"
    local raft_port="$3"
    local peers="$4"
    local pid_file="${PID_DIR}/${node_id}.pid"
    local log_file="${LOG_DIR}/${node_id}.log"

    if [ -f "${pid_file}" ] && kill -0 "$(cat ${pid_file})" 2>/dev/null; then
        echo "[SKIP] ${node_id} is already running (PID=$(cat ${pid_file}))"
        return
    fi

    mkdir -p "${LOG_DIR}" "${PID_DIR}"

    nohup java ${JVM_OPTS} -jar "${JAR}" \
        "${node_id}" "${client_port}" "${raft_port}" ${peers} \
        > "${log_file}" 2>&1 &

    echo $! > "${pid_file}"
    echo "[OK]   ${node_id} started (PID=$!, client=:${client_port}, raft=:${raft_port})"
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
    local client_port="$2"
    local pid_file="${PID_DIR}/${node_id}.pid"

    if [ -f "${pid_file}" ] && kill -0 "$(cat ${pid_file})" 2>/dev/null; then
        local pid
        pid=$(cat "${pid_file}")
        # 检查 metrics 端点
        local metrics_port=$((9090 + ${client_port} - 8881))
        local health="unknown"
        if command -v curl &>/dev/null; then
            health=$(curl -sf "http://127.0.0.1:${metrics_port}/health" 2>/dev/null | grep -o '"status":"[^"]*"' | cut -d'"' -f4 || echo "unreachable")
        fi
        echo "  ${node_id}: RUNNING (PID=${pid}, client=:${client_port}, health=${health})"
    else
        echo "  ${node_id}: STOPPED"
    fi
}

# ---- 主逻辑 ----

CMD="${1:-help}"

case "${CMD}" in
    start)
        check_jar
        echo "Starting HutuLock cluster (3 nodes)..."
        start_node "node1" "8881" "9881" "${PEERS}"
        sleep 0.5
        start_node "node2" "8882" "9882" "${PEERS_NODE2}"
        sleep 0.5
        start_node "node3" "8883" "9883" "${PEERS_NODE3}"
        echo ""
        echo "Cluster started. Waiting for leader election (~1s)..."
        sleep 1.5
        echo ""
        echo "Logs: ${LOG_DIR}/"
        echo "Connect: java -jar hutulock-cli.jar 127.0.0.1:8881 127.0.0.1:8882 127.0.0.1:8883"
        ;;

    stop)
        echo "Stopping HutuLock cluster..."
        stop_node "node1"
        stop_node "node2"
        stop_node "node3"
        echo "Cluster stopped."
        ;;

    restart)
        "$0" stop
        sleep 1
        "$0" start
        ;;

    status)
        echo "HutuLock Cluster Status:"
        status_node "node1" "8881"
        status_node "node2" "8882"
        status_node "node3" "8883"
        ;;

    *)
        echo "Usage: $0 {start|stop|restart|status}"
        echo ""
        echo "  start    Start 3-node local cluster"
        echo "  stop     Stop all nodes"
        echo "  restart  Restart cluster"
        echo "  status   Show node status"
        exit 1
        ;;
esac
