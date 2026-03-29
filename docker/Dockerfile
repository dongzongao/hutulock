# =============================================================================
# HutuLock Server Docker 镜像
#
# 构建：
#   docker build -t hutulock/server:1.0.0 -f docker/Dockerfile .
#
# 运行（单节点）：
#   docker run -d \
#     -p 8881:8881 -p 9881:9881 -p 9090:9090 \
#     -e NODE_ID=node1 \
#     -e CLIENT_PORT=8881 \
#     -e RAFT_PORT=9881 \
#     hutulock/server:1.0.0
# =============================================================================

# ---- 构建阶段 ----
FROM eclipse-temurin:11-jdk-alpine AS builder

WORKDIR /build

# 先复制 pom 文件，利用 Docker 层缓存
COPY pom.xml .
COPY hutulock-model/pom.xml hutulock-model/
COPY hutulock-spi/pom.xml hutulock-spi/
COPY hutulock-config/pom.xml hutulock-config/
COPY hutulock-server/pom.xml hutulock-server/
COPY hutulock-client/pom.xml hutulock-client/
COPY hutulock-cli/pom.xml hutulock-cli/

# 下载依赖（利用缓存）
RUN apk add --no-cache maven && \
    mvn dependency:go-offline -q

# 复制源码并构建
COPY . .
RUN mvn clean package -DskipTests -q

# ---- 运行阶段 ----
FROM eclipse-temurin:11-jre-alpine

LABEL maintainer="HutuLock Authors"
LABEL version="1.0.0"
LABEL description="HutuLock Distributed Lock Server"

# 创建非 root 用户
RUN addgroup -S hutulock && adduser -S hutulock -G hutulock

WORKDIR /app

# 复制 JAR
COPY --from=builder /build/hutulock-server/target/hutulock-server-*.jar app.jar

# 创建目录
RUN mkdir -p /app/logs /app/config && \
    chown -R hutulock:hutulock /app

# 复制默认配置
COPY --from=builder /build/hutulock-config/src/main/resources/hutulock.yml /app/config/

USER hutulock

# 端口说明：
#   8881 — 客户端连接端口（可通过环境变量覆盖）
#   9881 — Raft 节点间通信端口
#   9090 — Prometheus Metrics 端口
EXPOSE 8881 9881 9090

# 健康检查
HEALTHCHECK --interval=10s --timeout=5s --start-period=15s --retries=3 \
    CMD wget -qO- http://localhost:9090/health || exit 1

# 环境变量（可在 docker run 或 docker-compose 中覆盖）
ENV NODE_ID=node1 \
    CLIENT_PORT=8881 \
    RAFT_PORT=9881 \
    PEERS="" \
    JVM_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC" \
    CONFIG_FILE="/app/config/hutulock.yml"

# 启动脚本
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

ENTRYPOINT ["/app/entrypoint.sh"]
