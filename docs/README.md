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
| 可扩展 | 全接口化设计（SPI），所有组件可替换 |

---

## 模块结构

```
hutulock/
├── hutulock-model/     领域模型（零外部依赖）
├── hutulock-spi/       服务接口契约（SPI）
├── hutulock-config/    配置加载（YAML / 代码）
├── hutulock-server/    服务端实现
├── hutulock-client/    客户端 SDK
└── hutulock-cli/       交互式命令行工具
```

**依赖关系：**

```
hutulock-model
    ↑
hutulock-spi  ←── hutulock-config
    ↑                   ↑
hutulock-server     hutulock-client
                        ↑
                    hutulock-cli
```

---

## 快速开始

### 构建

```bash
mvn clean package -DskipTests
```

### 启动单节点服务端

```bash
java -jar hutulock-server/target/hutulock-server-1.0.0.jar node1 8881 9881
```

### 启动 3 节点集群

```bash
java -jar hutulock-server.jar node1 8881 9881 node2:127.0.0.1:9882 node3:127.0.0.1:9883
java -jar hutulock-server.jar node2 8882 9882 node1:127.0.0.1:9881 node3:127.0.0.1:9883
java -jar hutulock-server.jar node3 8883 9883 node1:127.0.0.1:9881 node2:127.0.0.1:9882
```

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

### 启动

```bash
# 交互模式
java -jar hutulock-cli/target/hutulock-cli-1.0.0.jar

# 启动时自动连接
java -jar hutulock-cli.jar 127.0.0.1:8881 127.0.0.1:8882
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
