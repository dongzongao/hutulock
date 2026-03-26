# HutuLock 架构决策记录（ADR）

## ADR-001：选择 Raft 作为共识层

**背景：** 需要一个高可用的分布式协调服务。

**决策：** 自实现 Raft 算法，而不是依赖外部协调服务。

**理由：**
- 减少外部依赖，降低运维复杂度
- 可以针对分布式锁场景做定制优化
- Raft 比 Paxos 更易理解和实现

**权衡：**
- 自实现 Raft 需要更多测试和验证
- 当前实现为内存日志，生产环境需要持久化（WAL）

---

## ADR-002：采用顺序临时节点实现公平锁

**背景：** 需要实现公平的分布式锁，避免饥饿。

**决策：** 使用 EPHEMERAL_SEQ 节点 + 监听前一个节点的模式。

**理由：**
- 公平性：FIFO 排队，先到先得
- 避免羊群效应：每个等待者只监听前一个节点
- 会话绑定：临时节点在会话过期时自动删除，天然实现锁释放

**对比简单锁（直接创建/删除节点）：**

| 特性 | 顺序节点模式 | 简单锁 |
|------|------------|--------|
| 公平性 | FIFO | 随机竞争 |
| 羊群效应 | 无 | 有 |
| 实现复杂度 | 中 | 低 |

---

## ADR-003：模块按领域语义命名，去除 common 概念

**背景：** 早期设计有 `common`、`common-core`、`common-api` 三个模块，概念混乱。

**决策：** 按领域语义拆分为 `model`（数据）和 `spi`（契约）。

**理由：**
- `common` 没有语义，不表达任何业务含义
- `model` 清晰表达"领域模型/值对象"
- `spi`（Service Provider Interface）清晰表达"服务接口契约"

**新结构：**
```
hutulock-model  ← 纯数据，零依赖
hutulock-spi    ← 接口契约，依赖 model
```

---

## ADR-004：接口优先设计（SPI 模式）

**背景：** 需要支持不同的认证方式、存储后端、监控后端。

**决策：** 所有核心组件先定义接口，实现类以 `Default` 前缀命名。

**接口清单：**

| 接口 | 默认实现 | 可替换场景 |
|------|---------|-----------|
| `LockService` | `DefaultLockManager` | 自定义锁语义 |
| `SessionTracker` | `DefaultSessionManager` | 持久化会话 |
| `ZNodeStorage` | `DefaultZNodeTree` | RocksDB 持久化 |
| `WatcherRegistry` | `DefaultWatcherRegistry` | 分布式 Watcher |
| `MetricsCollector` | `PrometheusMetricsCollector` | JMX、日志 |
| `EventBus` | `DefaultEventBus` | Kafka、RabbitMQ |
| `Authenticator` | `TokenAuthenticator` | OAuth、JWT |
| `Authorizer` | `AclAuthorizer` | RBAC、LDAP |

---

## ADR-005：看门狗采用双层设计

**背景：** 单层看门狗存在单点失效风险。

**决策：** 客户端主动续期 + 服务端被动检测双层兜底。

**客户端层：**
- 持锁期间每 `watchdogInterval` 发送 RENEW
- 连接断开时触发 `onExpired` 回调

**服务端层：**
- 每秒扫描，超过 TTL 无心跳则强制释放
- 不依赖客户端的正确行为

**约束：** `watchdogInterval < ttl/3`，确保至少有 3 次续期机会。

---

## ADR-006：事件总线与 Watcher 分离

**背景：** 需要两种不同语义的事件通知机制。

**决策：** 保留两套独立的事件系统。

| | WatcherRegistry | EventBus |
|--|----------------|---------|
| 语义 | One-shot Watcher | 发布-订阅 |
| 订阅者 | 网络客户端 | 内部 Java 组件 |
| 触发 | 一次后注销 | 持续触发 |
| 用途 | 锁等待通知 | 审计、监控、解耦 |

---

## ADR-007：安全组件通过 SecurityContext 聚合

**背景：** 认证、授权、审计、限流四个组件需要统一管理。

**决策：** 使用 `SecurityContext`（Builder 模式）聚合所有安全组件。

**优点：**
- 单一注入点，避免构造函数参数爆炸
- 支持 `SecurityContext.disabled()` 快速禁用（开发环境）
- 各组件独立可替换

---

## ADR-008：增加 CLI 工具

**背景：** 需要一个便捷的方式手动操作和调试分布式锁。

**决策：** 新增 `hutulock-cli` 模块，提供交互式 REPL。

**设计要点：**
- `CliContext` 封装连接状态和持有的锁，与 `HutuLockClient` 解耦
- `CliCommand` 枚举统一管理命令定义和帮助信息
- 日志级别设为 WARN，避免干扰交互界面
- 支持启动时自动连接（传入节点参数）

---

## 当前已知限制

| 限制 | 影响 | 计划 |
|------|------|------|
| Raft 日志为内存存储 | 节点重启后日志丢失 | 实现 WAL 持久化 |
| ZNode 树为内存存储 | 节点重启后状态丢失 | 实现快照（Snapshot） |
| 无 Raft 日志压缩 | 长期运行日志无限增长 | 实现 Log Compaction |
| REDIRECT 依赖 nodeId | 客户端需要维护 nodeId→地址映射 | 改为直接返回 host:port |
| 单线程 Raft 调度器 | 高并发下可能成为瓶颈 | 评估多线程优化 |
