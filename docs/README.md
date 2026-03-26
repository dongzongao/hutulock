# HutuLock

> 基于 Raft 共识算法 + ZooKeeper 设计模式的高可用分布式锁

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-11%2B-orange.svg)](https://openjdk.org/)

---

## 目录

- [项目概述](#项目概述)
- [模块结构](#模块结构)
- [核心设计](#核心设计)
- [快速开始](#快速开始)
- [配置参考](#配置参考)
- [技术细节](technical-details.md)
- [安全指南](security.md)
- [监控指南](metrics.md)
- [架构决策](architecture.md)

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
| 可扩展 | 全接口化设计（SPI），所有组件可替换 |

---

## 模块结构

```
hutulock/
├── hutulock-model/     领域模型（零外部依赖）
├── hutulock-spi/       服务接口契约（SPI）
├── hutulock-config/    配置加载（YAML / 代码）
├── hutulock-server/    服务端实现
└── hutulock-client/    客户端 SDK
```

**依赖关系：**

```
hutulock-model
    ↑
hutulock-spi  ←── hutulock-config
    ↑                   ↑
hutulock-server     hutulock-client
```

---

## 快速开始

### 启动单节点服务端

```bash
java -jar hutulock-server.jar node1 8881 9881
```

### 启动 3 节点集群

```bash
# 节点 1
java -jar hutulock-server.jar node1 8881 9881 node2:127.0.0.1:9882 node3:127.0.0.1:9883

# 节点 2
java -jar hutulock-server.jar node2 8882 9882 node1:127.0.0.1:9881 node3:127.0.0.1:9883

# 节点 3
java -jar hutulock-server.jar node3 8883 9883 node1:127.0.0.1:9881 node2:127.0.0.1:9882
```

### 客户端使用

**简单用法：**

```java
HutuLockClient client = HutuLockClient.builder()
    .addNode("127.0.0.1", 8881)
    .addNode("127.0.0.1", 8882)
    .addNode("127.0.0.1", 8883)
    .build();

client.connect();

boolean held = client.lock("order-lock");
try {
    // 临界区
} finally {
    client.unlock("order-lock");
}
```

**带看门狗的完整用法：**

```java
LockContext ctx = LockContext.builder("order-lock", client.getSessionId())
    .ttl(30, TimeUnit.SECONDS)
    .watchdogInterval(9, TimeUnit.SECONDS)
    .onExpired(lockName -> {
        // 锁过期时主动放弃临界区
        abortCriticalSection();
    })
    .build();

client.lock(ctx);
try {
    // 临界区，看门狗自动续期
} finally {
    client.unlock(ctx);
}
```

---

## 配置参考

在 classpath 放置 `hutulock.yml`：

```yaml
hutulock:
  server:
    raft:
      electionTimeoutMin: 150   # 选举超时最小值（ms）
      electionTimeoutMax: 300   # 选举超时最大值（ms）
      heartbeatInterval: 50     # 心跳间隔（ms）
      proposeTimeout: 10000     # Propose 超时（ms）
    watchdog:
      ttl: 30000                # 看门狗 TTL（ms）
      scanInterval: 1000        # 扫描间隔（ms）
    metrics:
      enabled: true
      port: 9090
    security:
      enabled: false            # 生产环境设为 true
      tls:
        enabled: false
        certFile: ""
        keyFile: ""
  client:
    connectTimeout: 3000
    lockTimeout: 30
    watchdog:
      ttl: 30000
      interval: 9000
```
