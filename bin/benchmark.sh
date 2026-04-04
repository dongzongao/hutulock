#!/bin/bash
#
# HutuLock 压力测试脚本
#
# 使用方式：
#   ./bin/benchmark.sh [test_type] [threads] [duration]
#
# 参数：
#   test_type: all, read, write, mixed (默认: all)
#   threads:   并发线程数 (默认: 100)
#   duration:  测试时长（秒）(默认: 60)
#
# 示例：
#   ./bin/benchmark.sh all 100 60      # 运行所有测试，100 线程，60 秒
#   ./bin/benchmark.sh read 200 30     # 只运行读测试，200 线程，30 秒
#   ./bin/benchmark.sh mixed 50 120    # 只运行混合测试，50 线程，120 秒

set -e

# 默认参数
TEST_TYPE=${1:-all}
THREADS=${2:-100}
DURATION=${3:-60}
HOST=${HOST:-127.0.0.1}
PORT=${PORT:-8881}

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}=== HutuLock Benchmark ===${NC}"
echo "Test Type: $TEST_TYPE"
echo "Threads:   $THREADS"
echo "Duration:  ${DURATION}s"
echo "Target:    $HOST:$PORT"
echo ""

# 检查集群是否运行
echo -e "${YELLOW}Checking cluster status...${NC}"
if ! nc -z $HOST $PORT 2>/dev/null; then
    echo -e "${RED}ERROR: Cannot connect to $HOST:$PORT${NC}"
    echo "Please start the cluster first:"
    echo "  ./bin/start-cluster.sh"
    exit 1
fi
echo -e "${GREEN}✓ Cluster is running${NC}"
echo ""

# 编译项目
echo -e "${YELLOW}Building project...${NC}"
mvn clean package -DskipTests -q
echo -e "${GREEN}✓ Build complete${NC}"
echo ""

# 运行压力测试
echo -e "${YELLOW}Starting benchmark...${NC}"
java -cp "hutulock-client/target/hutulock-client-1.1.0.jar:hutulock-model/target/hutulock-model-1.1.0.jar:hutulock-spi/target/hutulock-spi-1.1.0.jar:hutulock-config/target/hutulock-config-1.1.0.jar:$(find ~/.m2/repository -name '*.jar' | grep -E '(netty|slf4j|logback)' | tr '\n' ':')" \
    -Dhost=$HOST \
    -Dport=$PORT \
    -Dthreads=$THREADS \
    -Dduration=$DURATION \
    -Dtest=$TEST_TYPE \
    com.hutulock.client.benchmark.LockBenchmark

echo ""
echo -e "${GREEN}=== Benchmark Complete ===${NC}"
