# HutuLock

> 基于 Raft 共识算法 + ZooKeeper 设计模式的高可用分布式锁

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-11%2B-orange.svg)](https://openjdk.org/)

---

## 目录

- [项目概述](#项目概述)
- [模块结构](#模块结构)
- [快速开始](#快速开始)
- [CLI 工具](#cli-工具)
- [配置参考](#配置参考)
- [技术细节](technical-details.md)
- [安全指南](security.md)
- [监控指南](metrics.md)
- [架构决策](architecture.md)
- [API 参考](api-reference.md)
- [测试说明](testing.md)

---

## 项目概述

HutuLock 是一个生产级分布式锁服务，核心特性：

| 特性 | 说明 |
|------|------|
| 高可用 | Raft 共识算法，3/5 节点集群，少数节点故障不影响服务 |
| 公平锁 | ZooKeeper 顺序临时节点模式，FIFO 排队，避免羊群效应 |
| 看门狗 | 客户端定时心跳续期，服务端 TTL 兜底，防止锁永久占用 |
| 安全 | Token/HMAC 认证、ACL 授权、TLS 加密、限流、审计日志 |
| 可观测 | Prometheus Metrics、结构化日志、事件总线 |
| CLI | 交互式命令行工具，支持连接集群、管理锁、查看状态 |
| IoC 容器 | 轻量级内置容器管理所有内存组件，支持生命周期和依赖注入 |
| 代理增强 | JDK 动态代理模块，无侵入地为 SPI 接口添加日志、指标、重试 |
| 可扩展 | 全接口化设计（SPI），所有组件可替换 |

---

## 模块结构

```
hutulock/
├── hutulock-model/     领域模型（零外部依赖）
├── hutulock-spi/       服务接口契约（SPI）
├── hutulock-config/    配置加载（YAML / 代码）
├── hutulock-proxy/     JDK 动态代理（日志 / 指标 / 重试）
├── hutulock-server/    服务端实现（含 IoC 容器）
├── hutulock-client/    客户端 SDK
└── hutulock-cli/       交互式命令行工具
```

**依赖关系：**

```
hutulock-model
    ↑
hutulock-spi  ←── hutulock-config
    ↑    ↑              ↑
    │  hutulock-proxy   │
    │         ↑         │
    └── hutulock-server ┘
                        hutulock-client
                              ↑
                          hutulock-cli
```

---

## 快速开始

### 构建

```bash
mvn clean package -DskipTests
```

### 使用脚本启动（推荐）

项目提供三个脚本，位于 `bin/` 目录：

| 脚本 | 用途 |
|------|------|
| `bin/server.sh` | 启动单个服务节点（前台运行） |
| `bin/cluster.sh` | 本地 3 节点集群一键管理（后台运行，开发/测试用） |
| `bin/cli.sh` | 启动交互式命令行工具 |

---

### bin/server.sh — 单节点启动

```
用法：./bin/server.sh <nodeId> <clientPort> <raftPort> [peer ...] [选项]

选项：
  --proxy <types>   启用代理增强，逗号分隔（logging / metrics / all）
  --config <path>   指定外部 hutulock.yml 路径
  --jvm <opts>      追加 JVM 参数（引号包裹）
```

**单节点（开发模式）：**

```bash
./bin/server.sh node1 8881 9881
```

**开启日志 + 指标代理：**

```bash
./bin/server.sh node1 8881 9881 --proxy logging,metrics
```

**3 节点集群（分别在三台机器上执行）：**

```bash
./bin/server.sh node1 8881 9881 node2:192.168.1.2:9882 node3:192.168.1.3:9883
./bin/server.sh node2 8882 9882 node1:192.168.1.1:9881 node3:192.168.1.3:9883
./bin/server.sh node3 8883 9883 node1:192.168.1.1:9881 node2:192.168.1.2:9882
```

**带代理 + 自定义配置文件：**

```bash
./bin/server.sh node1 8881 9881 \
  --proxy all \
  --config /etc/hutulock/hutulock.yml \
  --jvm "-Xmx1g"
```

启动后输出：

```
============================================
 HutuLock Server
 Node ID    : node1
 Client Port: 8881
 Raft Port  : 9881
 Peers      : none
 Proxy      : logging,metrics
 JAR        : .../hutulock-server-1.0.0.jar
 Log Dir    : .../logs
============================================
```

---

### bin/cluster.sh — 本地集群管理

```
用法：./bin/cluster.sh <命令> [选项]

命令：
  start   [--proxy types]   后台启动 3 节点集群
  stop                      停止所有节点
  restart [--proxy types]   重启集群
  status                    查看各节点运行状态
  logs    [nodeId]          实时查看节点日志（默认 node1）
```

**启动集群：**

```bash
./bin/cluster.sh start
```

**启动集群并开启全量代理：**

```bash
./bin/cluster.sh start --proxy all
```

**查看状态：**

```bash
./bin/cluster.sh status
# HutuLock Cluster Status:
#   node1: RUNNING  PID=12345  client=:8881  metrics=:9090  health=UP
#   node2: RUNNING  PID=12346  client=:8882  metrics=:9091  health=UP
#   node3: RUNNING  PID=12347  client=:8883  metrics=:9092  health=UP
```

**实时查看 node2 日志：**

```bash
./bin/cluster.sh logs node2
```

**停止集群：**

```bash
./bin/cluster.sh stop
```

集群默认端口分配：

| 节点 | 客户端端口 | Raft 端口 | Metrics 端口 |
|------|-----------|----------|-------------|
| node1 | 8881 | 9881 | 9090 |
| node2 | 8882 | 9882 | 9091 |
| node3 | 8883 | 9883 | 9092 |

---

### 代理增强（--proxy）

`--proxy` 通过系统属性 `-Dhutulock.proxy=<types>` 控制代理层，默认不启用：

| 值 | 效果 |
|----|------|
| `logging` | 记录每次方法调用的入参、耗时、异常（DEBUG 级别） |
| `metrics` | 统计调用次数、失败次数、平均耗时 |
| `all` | 等价于 `logging,metrics` |

生效范围：`ZNodeStorage`（日志+指标）、`SessionTracker`（日志）、`EventBus`（日志）。

---

### 客户端 SDK 使用

**简单用法：**

```java
HutuLockClient client = HutuLockClient.builder()
    .addNode("127.0.0.1", 8881)
    .addNode("127.0.0.1", 8882)
    .build();
client.connect();

boolean held = client.lock("order-lock");
try {
    // 临界区
} finally {
    client.unlock("order-lock");
}
client.close();
```

**带看门狗的完整用法：**

```java
LockContext ctx = LockContext.builder("order-lock", client.getSessionId())
    .ttl(30, TimeUnit.SECONDS)
    .watchdogInterval(9, TimeUnit.SECONDS)   // 必须 < ttl/3
    .onExpired(lockName -> abortCriticalSection())
    .build();

client.lock(ctx);
try {
    // 临界区，看门狗每 9s 自动续期
} finally {
    client.unlock(ctx);
}
```

---

## CLI 工具

### bin/cli.sh — 交互式命令行

```
用法：./bin/cli.sh [host:port ...] [选项]

选项：
  --jvm <opts>   追加 JVM 参数（引号包裹）
```

**交互模式（启动后手动 connect）：**

```bash
./bin/cli.sh
```

**启动时自动连接集群：**

```bash
./bin/cli.sh 127.0.0.1:8881 127.0.0.1:8882 127.0.0.1:8883
```

**自定义 JVM 参数：**

```bash
./bin/cli.sh --jvm "-Xmx256m" 127.0.0.1:8881
```

### 支持命令

| 命令 | 格式 | 说明 |
|------|------|------|
| `connect` | `connect <host:port> [...]` | 连接集群节点 |
| `lock` | `lock <lockName> [timeoutSec]` | 获取锁（默认 30s 超时） |
| `unlock` | `unlock <lockName>` | 释放锁 |
| `renew` | `renew <lockName>` | 手动续期 |
| `status` | `status` | 查看连接状态和持有的锁 |
| `disconnect` | `disconnect` | 断开连接 |
| `help` | `help [command]` | 显示帮助 |
| `exit` | `exit` | 退出 |

### 交互示例

```
hutulock(disconnected)> connect 127.0.0.1:8881
✓ Connected to: 127.0.0.1:8881
  Session ID: a3f8c2d1e4b5f6a7

hutulock(a3f8c2d1)> lock order-lock
  Acquiring lock [order-lock] (timeout=30s)...
✓ Lock acquired: order-lock [/locks/order-lock/seq-0000000001] in 12ms

hutulock(a3f8c2d1)[1 lock(s)]> status
Connected to: 127.0.0.1:8881
Session ID:   a3f8c2d1e4b5f6a7
Held locks:
  order-lock [seq=/locks/order-lock/seq-0000000001, state=HELD, held=yes]

hutulock(a3f8c2d1)[1 lock(s)]> unlock order-lock
✓ Lock released: order-lock

hutulock(a3f8c2d1)> exit
Bye!
```

---

## 配置参考

在 classpath 放置 `hutulock.yml`（不存在则使用全部默认值）：

```yaml
hutulock:
  server:
    raft:
      electionTimeoutMin: 150   # 选举超时最小值（ms）
      electionTimeoutMax: 300   # 选举超时最大值（ms）
      heartbeatInterval: 50     # 心跳间隔（ms），必须 < electionTimeoutMin/3
      proposeTimeout: 10000     # Propose 超时（ms）
      proposeRetryCount: 3      # Propose 失败重试次数
      proposeRetryDelay: 500    # 重试间隔（ms）
    watchdog:
      ttl: 30000                # 看门狗 TTL（ms），超时无心跳则强制释放锁
      scanInterval: 1000        # 扫描间隔（ms）
    network:
      soBacklog: 128
      maxFrameLength: 4096
    metrics:
      enabled: true
      port: 9090                # Prometheus scrape 端口
    security:
      enabled: false            # 生产环境必须设为 true
      tls:
        enabled: false
        certFile: ""            # PEM 格式证书路径
        keyFile: ""             # PEM 格式私钥路径
        selfSigned: false       # 仅开发/测试使用
      rateLimit:
        qps: 100                # 每客户端每秒最大请求数
        burst: 200              # 突发容量

  client:
    connectTimeout: 3000        # 连接超时（ms）
    lockTimeout: 30             # 获取锁默认超时（秒）
    watchdog:
      ttl: 30000                # 看门狗 TTL（ms），应与服务端一致
      interval: 9000            # 心跳间隔（ms），必须 < ttl/3
```
