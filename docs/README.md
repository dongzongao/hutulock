<div align="center">

# 🔒 HutuLock

**基于 Raft 共识算法的高可用分布式锁服务**

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-11%2B-orange.svg)](https://openjdk.org/)
[![Python](https://img.shields.io/badge/Python-3.8%2B-3776AB.svg)](https://python.org/)
[![Go](https://img.shields.io/badge/Go-1.21%2B-00ADD8.svg)](https://go.dev/)
[![PHP](https://img.shields.io/badge/PHP-8.0%2B-777BB4.svg)](https://php.net/)
[![Netty](https://img.shields.io/badge/Netty-4.1.129-green.svg)](https://netty.io/)

*公平锁 · 看门狗续期 · Java / Python / Go / PHP SDK · 生产就绪*

</div>

---

## ✨ 为什么选择 HutuLock？

> 大多数分布式锁方案依赖 Redis 或 ZooKeeper 等外部中间件。HutuLock 将 Raft 共识引擎内嵌到锁服务本身，**无需任何外部依赖**，开箱即用。

```
客户端                    HutuLock 集群（3 节点）
  │                       ┌─────────┐
  │── LOCK order-lock ──▶ │ Leader  │──┐ AppendEntries
  │◀─ OK /locks/.../seq-1 │ node1   │  ├──▶ Follower node2
  │                       └─────────┘  └──▶ Follower node3
  │   持锁期间每 10s 自动续期（看门狗）
  │── RENEW order-lock ──▶
  │── UNLOCK ────────────▶
```

**核心优势：**

- **零外部依赖** — Raft 引擎内置，不依赖 Redis / ZooKeeper / etcd
- **公平锁** — ZooKeeper 顺序临时节点模式，FIFO 排队，彻底避免羊群效应
- **看门狗** — 客户端定时心跳续期，服务端 TTL 兜底，锁不会因网络抖动意外释放
- **多语言** — Java / Python / Go / PHP SDK，同一套文本协议，互相兼容
- **生产就绪** — TLS、Token/HMAC 认证、ACL 授权、限流、Prometheus Metrics、审计日志

---

## 🏗️ 架构

```
┌─────────────────────────────────────────────────────────┐
│                    HutuLock Server                       │
│                                                         │
│  ┌──────────┐   ┌──────────┐   ┌──────────────────┐    │
│  │  Netty   │   │   Raft   │   │  ZNode Tree      │    │
│  │ Pipeline │──▶│  Engine  │──▶│  (内存存储)       │    │
│  │ TLS/Auth │   │ 3/5节点  │   │  顺序临时节点     │    │
│  └──────────┘   └──────────┘   └──────────────────┘    │
│       │                                │                │
│  ┌────▼────┐                    ┌──────▼──────┐         │
│  │Security │                    │   Session   │         │
│  │ Context │                    │   Manager   │         │
│  │Token/ACL│                    │  看门狗TTL  │         │
│  └─────────┘                    └─────────────┘         │
│                                                         │
│  ┌──────────────────────────────────────────────┐       │
│  │  IoC Container  │  EventBus  │  Prometheus   │       │
│  └──────────────────────────────────────────────┘       │
└─────────────────────────────────────────────────────────┘
         ▲                ▲                ▲
    Java SDK         Python SDK         Go SDK
```

**模块说明：**

```
hutulock/
├── hutulock-model/     领域模型（零外部依赖）
├── hutulock-spi/       服务接口契约（SPI）
├── hutulock-config/    配置加载（YAML / 代码）
├── hutulock-proxy/     JDK 动态代理（日志 / 指标 / 重试）
├── hutulock-server/    服务端实现（Raft + ZNode + Session）
├── hutulock-client/    Java 客户端 SDK
├── hutulock-cli/       交互式命令行工具
├── hutulock-admin/     Web 管理控制台（Vue 3 + REST API）
└── sdk/
    ├── python/         Python SDK（零依赖）
    ├── go/             Go SDK（零依赖）
    └── php/            PHP SDK（零依赖）
```

---

## 🚀 快速开始

### 1. 构建

```bash
git clone https://github.com/hutulock/hutulock.git
cd hutulock
mvn clean package -DskipTests
```

### 2. 启动单节点（开发模式）

```bash
./bin/server.sh node1 8881 9881
```

### 3. 启动本地 3 节点集群

```bash
./bin/cluster.sh start
./bin/cluster.sh status
```

```
HutuLock Cluster Status:
  node1: RUNNING  PID=12345  client=:8881  metrics=:9090  health=UP
  node2: RUNNING  PID=12346  client=:8882  metrics=:9091  health=UP
  node3: RUNNING  PID=12347  client=:8883  metrics=:9092  health=UP
```

### 4. 用 CLI 验证

```bash
./bin/cli.sh 127.0.0.1:8881

hutulock(disconnected)> connect 127.0.0.1:8881
✓ Connected  Session: a3f8c2d1e4b5f6a7

hutulock(a3f8c2d1)> lock order-lock
✓ Lock acquired: order-lock [/locks/order-lock/seq-0000000001] in 8ms

hutulock(a3f8c2d1)[1 lock(s)]> unlock order-lock
✓ Lock released: order-lock
```

---

## 📦 客户端 SDK

### Java

```xml
<dependency>
    <groupId>com.hutulock</groupId>
    <artifactId>hutulock-client</artifactId>
    <version>1.0.0</version>
</dependency>
```

> 从 GitHub Packages 安装，需在 `~/.m2/settings.xml` 配置认证，详见 [发布说明](#-发布与安装)。

```java
HutuLockClient client = HutuLockClient.builder()
    .addNode("127.0.0.1", 8881)
    .addNode("127.0.0.1", 8882)
    .build();
client.connect();

boolean held = client.lock("order-lock");
try {
    // 临界区，看门狗自动续期
} finally {
    client.unlock("order-lock");
}
```

### Python

```bash
pip install hutulock
```

```python
from hutulock import HutuLockClient

with HutuLockClient(nodes=[("127.0.0.1", 8881)]) as client:
    with client.lock("order-lock") as token:
        process_order()  # 临界区
```

### Go

```bash
go get github.com/hutulock/hutulock-go
```

```go
client, _ := hutulock.New(hutulock.Config{
    Nodes: []string{"127.0.0.1:8881"},
})
defer client.Close()

token, _ := client.Lock(ctx, "order-lock")
defer client.Unlock(ctx, token)
// 临界区
```

### PHP

```bash
composer require hutulock/hutulock-php
```

```php
use HutuLock\HutuLockClient;

$client = new HutuLockClient(
    nodes: [['127.0.0.1', 8881], ['127.0.0.1', 8882]],
    lockTimeout: 30.0,
);
$client->connect();

$token = $client->lock('order-lock');
try {
    processOrder(); // 临界区
    $client->tick(); // 触发看门狗续期（PHP 单线程）
} finally {
    $client->unlock($token);
}
$client->close();
```

---

## 🔐 安全特性

| 特性 | 说明 |
|------|------|
| Token 认证 | 静态 Token 校验，适合内网服务 |
| HMAC 认证 | 基于共享密钥的消息签名，防重放 |
| ACL 授权 | 细粒度资源权限控制 |
| TLS 加密 | 支持自签名证书（开发）和 PEM 证书（生产） |
| 限流 | 令牌桶算法，按客户端限制 QPS |
| 审计日志 | 所有锁操作写入独立审计日志文件 |

生产环境最小安全配置：

```yaml
hutulock:
  server:
    security:
      enabled: true
      tls:
        enabled: true
        certFile: /etc/hutulock/server.crt
        keyFile:  /etc/hutulock/server.key
      rateLimit:
        qps: 100
        burst: 200
```

---

## 📊 可观测性

Prometheus 指标（默认 `:9090/metrics`）：

```
# 锁操作
hutulock_lock_acquired_total
hutulock_lock_released_total
hutulock_lock_waiting_total
hutulock_lock_acquire_duration_ms

# Raft
hutulock_raft_propose_success_total
hutulock_raft_propose_timeout_total
hutulock_raft_election_started_total

# 会话
hutulock_session_created_total
hutulock_session_expired_total
```

---

## ⚙️ 配置参考

```yaml
hutulock:
  server:
    raft:
      electionTimeoutMin: 150   # ms，选举超时下限
      electionTimeoutMax: 300   # ms，选举超时上限
      heartbeatInterval: 50     # ms，必须 < electionTimeoutMin/3
      proposeTimeout: 10000     # ms，写入超时
    watchdog:
      ttl: 30000                # ms，会话 TTL
      scanInterval: 1000        # ms，过期扫描间隔
    network:
      maxFrameLength: 4096
    metrics:
      enabled: true
      port: 9090
  client:
    connectTimeout: 3000
    lockTimeout: 30             # 秒
    watchdog:
      interval: 9000            # ms，必须 < ttl/3
```

---

## 🗺️ 路线图

- [x] Raft 共识引擎（Leader 选举 + 日志复制）
- [x] ZooKeeper 顺序临时节点公平锁
- [x] 看门狗自动续期
- [x] TLS / Token / HMAC / ACL 安全体系
- [x] Prometheus Metrics
- [x] Java / Python / Go / PHP 多语言 SDK
- [x] 交互式 CLI
- [x] Zab 风格 Recovery Phase（Leader 上任同步屏障）
- [x] Raft Fast Backup（O(term数) 日志对齐）
- [x] Raft 批量 Pipeline + inFlight 流控
- [x] RaftLog ReadWriteLock（读写分离）
- [x] DelayQueue propose 超时管理
- [x] 持久 Watcher（参考 ZooKeeper 3.6 addWatch）
- [x] ZNode czxid/mzxid（事务 ID，线性一致读基础）
- [x] Raft 日志持久化（WAL + fsync + 崩溃恢复）
- [x] ZNode 快照（Snapshot + 增量日志重放）
- [x] 动态集群成员变更（Joint Consensus，Raft §6）
- [x] Web 管理控制台（Vue 3 + Element Plus + JWT 鉴权）
- [x] GitHub Packages 发布（Maven + GitHub Actions CI/CD）
- [ ] Kubernetes Operator

---

## 🌐 Web 管理控制台

服务启动后访问 `http://localhost:9091`（默认账户 `admin` / `admin123`）：

| 页面 | 功能 |
|------|------|
| 集群状态 | 节点角色、Leader、Peer 的 nextIndex/matchIndex/inFlight、配置阶段 |
| 活跃会话 | Session ID、Client、状态、剩余 TTL，每 5s 自动刷新 |
| 锁状态 | 持锁者和等待队列，每 3s 自动刷新 |
| 成员管理 | 动态添加/移除节点（Joint Consensus 两阶段变更） |

```yaml
hutulock:
  server:
    admin:
      enabled: true
      port: 9091   # 访问 http://localhost:9091
```

自定义账户（通过系统属性覆盖默认值）：

```bash
java -Dhutulock.admin.username=myuser \
     -Dhutulock.admin.password=mypass \
     -jar hutulock-server.jar node1 8881 9881
```

---

## 📤 发布与安装

HutuLock 发布到 [GitHub Packages](https://github.com/hutulock/hutulock/packages)，通过 GitHub Actions 在打 tag 时自动触发。

**安装依赖前，在 `~/.m2/settings.xml` 中配置认证：**

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_GITHUB_TOKEN</password>  <!-- 需要 read:packages 权限 -->
    </server>
  </servers>
  <profiles>
    <profile>
      <id>github</id>
      <repositories>
        <repository>
          <id>github</id>
          <url>https://maven.pkg.github.com/hutulock/hutulock</url>
        </repository>
      </repositories>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>github</activeProfile>
  </activeProfiles>
</settings>
```

**在项目 `pom.xml` 中添加依赖：**

```xml
<dependency>
    <groupId>com.hutulock</groupId>
    <artifactId>hutulock-client</artifactId>
    <version>1.0.0</version>
</dependency>
```

**发布新版本（打 tag 自动触发 CI）：**

```bash
git tag v1.0.0
git push origin v1.0.0
```

---

| 文档 | 说明 |
|------|------|
| [架构设计](architecture.md) | Raft 实现细节、ZNode 树、IoC 容器 |
| [API 参考](api-reference.md) | 完整协议文档 |
| [安全指南](security.md) | 认证、授权、TLS 配置 |
| [监控指南](metrics.md) | Prometheus 指标说明 |
| [测试说明](testing.md) | 单元测试、集成测试、压测 |
| [技术细节](technical-details.md) | 性能优化、内存管理 |

---

## 🤝 贡献

欢迎 PR 和 Issue。提交前请确保：

```bash
mvn test          # 所有测试通过
mvn checkstyle:check  # 代码风格检查
```

---

## 📜 License

[Apache License 2.0](../LICENSE)
