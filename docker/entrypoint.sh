#!/bin/sh
# HutuLock Server 容器启动脚本
# 环境变量：
#   NODE_ID      — 节点 ID，如 node1
#   CLIENT_PORT  — 客户端端口，默认 8881
#   RAFT_PORT    — Raft 端口，默认 9881
#   PEERS        — 对等节点列表，空格分隔，如 "node2:hutulock-1.hutulock-raft:9882 node3:hutulock-2.hutulock-raft:9883"
#   JVM_OPTS     — 额外 JVM 参数
#   CONFIG_FILE  — 配置文件路径

set -e

NODE_ID="${NODE_ID:-node1}"
CLIENT_PORT="${CLIENT_PORT:-8881}"
RAFT_PORT="${RAFT_PORT:-9881}"
JVM_OPTS="${JVM_OPTS:--Xms256m -Xmx512m -XX:+UseG1GC}"
CONFIG_FILE="${CONFIG_FILE:-/app/config/hutulock.yml}"

# 将 PEERS 字符串转为参数列表
PEER_ARGS=""
if [ -n "${PEERS}" ]; then
    PEER_ARGS="${PEERS}"
fi

echo "============================================"
echo " HutuLock Server"
echo " Node ID    : ${NODE_ID}"
echo " Client Port: ${CLIENT_PORT}"
echo " Raft Port  : ${RAFT_PORT}"
echo " Peers      : ${PEERS:-none}"
echo "============================================"

exec java ${JVM_OPTS} \
    -Dfile.encoding=UTF-8 \
    -cp "/app/config:app.jar" \
    -jar app.jar \
    "${NODE_ID}" "${CLIENT_PORT}" "${RAFT_PORT}" ${PEER_ARGS}
