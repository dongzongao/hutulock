#!/bin/bash
# 稳定性测试脚本

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# 默认参数
SERVERS="127.0.0.1:8881"
THREADS=50
DURATION=60  # 分钟

# 解析参数
while [[ $# -gt 0 ]]; do
    case $1 in
        -s|--servers)
            SERVERS="$2"
            shift 2
            ;;
        -t|--threads)
            THREADS="$2"
            shift 2
            ;;
        -d|--duration)
            DURATION="$2"
            shift 2
            ;;
        -h|--help)
            echo "用法: $0 [选项]"
            echo ""
            echo "选项:"
            echo "  -s, --servers   服务器地址 (默认: 127.0.0.1:8881)"
            echo "  -t, --threads   线程数 (默认: 50)"
            echo "  -d, --duration  持续时间(分钟) (默认: 60)"
            echo "  -h, --help      显示帮助"
            echo ""
            echo "示例:"
            echo "  $0 -s 127.0.0.1:8881,127.0.0.1:8882,127.0.0.1:8883 -t 100 -d 120"
            exit 0
            ;;
        *)
            echo "未知选项: $1"
            echo "使用 -h 查看帮助"
            exit 1
            ;;
    esac
done

echo "=== HutuLock 稳定性测试 ==="
echo "服务器: $SERVERS"
echo "线程数: $THREADS"
echo "持续时间: $DURATION 分钟"
echo ""

# 检查集群状态
echo "检查集群状态..."
IFS=',' read -ra SERVER_ARRAY <<< "$SERVERS"
for server in "${SERVER_ARRAY[@]}"; do
    IFS=':' read -ra ADDR <<< "$server"
    HOST="${ADDR[0]}"
    PORT="${ADDR[1]}"
    
    if nc -z "$HOST" "$PORT" 2>/dev/null; then
        echo "  ✓ $server 可访问"
    else
        echo "  ✗ $server 不可访问"
        echo ""
        echo "请先启动集群："
        echo "  ./bin/cluster.sh"
        exit 1
    fi
done

echo ""
echo "开始稳定性测试..."
echo "预计完成时间: $(date -v+${DURATION}M '+%Y-%m-%d %H:%M:%S' 2>/dev/null || date -d "+${DURATION} minutes" '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo "约 $DURATION 分钟后")"
echo ""

# 运行测试
cd "$PROJECT_DIR"

mvn -q exec:java \
    -Dexec.mainClass="com.hutulock.client.stability.StabilityTest" \
    -Dexec.args="$SERVERS $THREADS $DURATION" \
    -pl hutulock-client

echo ""
echo "稳定性测试完成！"
