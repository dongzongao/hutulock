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
JAR="${PROJECT_DIR}/hutulock-server/target/hutulock-server-1.0.1-SNAPSHOT.jar"
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
# hutulock-server JVM 调优策略：
#   - 低延迟优先：ZGC（JDK 15+）或 G1GC（JDK 11）
#   - 堆外内存：Netty DirectBuffer 不走堆，堆可以适当小一些
#   - 减少 GC 停顿：对 Raft 心跳和锁操作的 P99 延迟影响最大
JVM_OPTS="${JVM_OPTS:-}"

# ---- 堆内存 ----
# Xms = Xmx 避免运行时堆扩容（扩容会触发 Full GC）
JVM_OPTS="${JVM_OPTS} -Xms512m -Xmx512m"

# ---- 垃圾收集器 ----
# 检测 JDK 版本，JDK 15+ 用 ZGC（亚毫秒停顿），否则用 G1GC
JAVA_MAJOR=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
if [ "${JAVA_MAJOR}" -ge 15 ] 2>/dev/null; then
    # ZGC：亚毫秒停顿，适合低延迟锁服务
    JVM_OPTS="${JVM_OPTS} -XX:+UseZGC"
    JVM_OPTS="${JVM_OPTS} -XX:ZCollectionInterval=5"       # 最大 5s 触发一次 GC
    JVM_OPTS="${JVM_OPTS} -XX:ZUncommitDelay=60"           # 60s 后归还空闲内存给 OS
else
    # G1GC：JDK 11 默认，调低停顿目标
    JVM_OPTS="${JVM_OPTS} -XX:+UseG1GC"
    JVM_OPTS="${JVM_OPTS} -XX:MaxGCPauseMillis=20"         # 目标停顿 ≤ 20ms
    JVM_OPTS="${JVM_OPTS} -XX:G1HeapRegionSize=4m"         # 4MB region，减少大对象晋升
    JVM_OPTS="${JVM_OPTS} -XX:G1NewSizePercent=20"         # 新生代最小 20%
    JVM_OPTS="${JVM_OPTS} -XX:G1MaxNewSizePercent=40"      # 新生代最大 40%
    JVM_OPTS="${JVM_OPTS} -XX:InitiatingHeapOccupancyPercent=35"  # 35% 时触发并发标记
    JVM_OPTS="${JVM_OPTS} -XX:G1MixedGCCountTarget=8"     # 混合 GC 分 8 次完成
fi

# ---- JIT 编译优化 ----
JVM_OPTS="${JVM_OPTS} -XX:+TieredCompilation"              # 分层编译（默认开启，显式声明）
JVM_OPTS="${JVM_OPTS} -XX:CompileThreshold=1000"           # 降低 JIT 触发阈值，更快热身
JVM_OPTS="${JVM_OPTS} -XX:+OptimizeStringConcat"           # 优化字符串拼接（协议序列化）
JVM_OPTS="${JVM_OPTS} -XX:+UseStringDeduplication"         # 字符串去重（ZNode 路径大量重复）

# ---- Netty 堆外内存 ----
# Netty 默认用 DirectBuffer，限制最大堆外内存防止 OOM
JVM_OPTS="${JVM_OPTS} -XX:MaxDirectMemorySize=256m"
# 关闭 Netty 的 ResourceLeakDetector（生产环境，避免采样开销）
JVM_OPTS="${JVM_OPTS} -Dio.netty.leakDetection.level=disabled"
# Netty 线程本地缓存，减少 DirectBuffer 分配
JVM_OPTS="${JVM_OPTS} -Dio.netty.allocator.type=pooled"

# ---- 线程栈 ----
# Netty worker 线程多，减小栈大小降低内存占用
JVM_OPTS="${JVM_OPTS} -Xss256k"

# ---- GC 日志（可观测性）----
LOG_GC="${LOG_DIR}/gc-${NODE_ID}.log"
JVM_OPTS="${JVM_OPTS} -Xlog:gc*:file=${LOG_GC}:time,uptime,level,tags:filecount=5,filesize=20m"

# ---- OOM 保护 ----
JVM_OPTS="${JVM_OPTS} -XX:+HeapDumpOnOutOfMemoryError"
JVM_OPTS="${JVM_OPTS} -XX:HeapDumpPath=${LOG_DIR}/heapdump-${NODE_ID}.hprof"
JVM_OPTS="${JVM_OPTS} -XX:+ExitOnOutOfMemoryError"         # OOM 直接退出，让 K8s 重启

# ---- 其他 ----
JVM_OPTS="${JVM_OPTS} -Dfile.encoding=UTF-8"
JVM_OPTS="${JVM_OPTS} -Djava.security.egd=file:/dev/./urandom"  # 加速随机数（影响 HMAC 认证）
JVM_OPTS="${JVM_OPTS} -XX:+DisableExplicitGC"              # 禁止代码中调用 System.gc()

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
