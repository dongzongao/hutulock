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
| 语义 | One-shot / 持久 Watcher | 发布-订阅 |
| 订阅者 | 网络客户端 | 内部 Java 组件 |
| 触发 | One-shot 触发后注销；持久 Watcher 持续触发 | 持续触发 |
| 用途 | 锁等待通知、持续监听 | 审计、监控、解耦 |

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

## ADR-009：IoC 容器管理内存组件

**背景：** `HutuLockServer` 构造函数中手动 `new` 所有组件，依赖关系散落在一处，难以替换和测试。

**决策：** 在 `hutulock-server` 内实现轻量级 IoC 容器（`com.hutulock.server.ioc`），不引入 Spring 等外部框架。

**核心类：**

| 类 | 职责 |
|----|------|
| `BeanDefinition<T>` | Bean 元数据（名称、类型、工厂方法） |
| `ApplicationContext` | 容器核心：延迟实例化、按类型/名称查找、生命周期调度 |
| `Lifecycle` | 生命周期接口（`start()` / `shutdown()`） |
| `ServerBeanFactory` | 服务端专属配置：集中声明所有 Bean 及其依赖关系 |

**Bean 注册顺序（从底层到上层）：**

```
serverProperties → memoryManager → eventBus → metrics → metricsHttpServer
  → watcherRegistry → zNodeStorage → sessionManager → sessionTracker
  → lockManager → raftNode → securityContext → lockServerHandler
```

**生命周期：**
- `ctx.start()` 按注册顺序调用所有 `Lifecycle.start()`
- `ctx.close()` 按注册逆序调用所有 `Lifecycle.shutdown()`

---

## ADR-010：代理模块实现横切关注点

**背景：** 日志、指标采集、重试等横切关注点散落在各实现类中，难以统一管理。

**决策：** 新增 `hutulock-proxy` 模块，基于 JDK 动态代理实现，通过 `ProxyBuilder` 链式组合。

**各 SPI 接口支持的代理类型：**

| SPI 接口 | Logging | Metrics | Retry |
|----------|:-------:|:-------:|:-----:|
| `ZNodeStorage` | ✓ | ✓ | |
| `LockService` | ✓ | ✓ | ✓ |
| `SessionTracker` | ✓ | | |
| `EventBus` | ✓ | | |
| `WatcherRegistry` | ✓ | | |

---

## ADR-011：Raft 引入 Zab 风格 Recovery Phase

**背景：** Leader 上任后立即开放 propose，Follower 日志可能尚未对齐，导致第一批写入触发多轮 nextIndex 回退，增加延迟。

**决策：** 参考 ZooKeeper Zab 协议的 Recovery Phase，Leader 上任后先广播一轮同步心跳，等多数派确认日志对齐后才开放 propose。

**实现：**
- `RaftState.leaderReady`：同步屏障标志，false 时 propose 排队等待
- `RaftState.syncAckCount`：同步阶段已收到 ack 的节点数
- `RaftState.pendingSyncQueue`：同步期间排队的 propose，就绪后批量触发
- `RaftElection.completeSyncPhase()`：多数派 ack 后置 `leaderReady=true` 并 flush 队列

**效果：** Leader 切换后第一批写入无需多轮回退，延迟更稳定。

---

## ADR-012：Raft 六项性能优化

**背景：** 原始实现存在多处性能瓶颈。

**决策：** 参考 etcd/raft 和 Raft 论文 §5.3 优化建议，实施以下六项改进：

| 优化 | 改动 | 效果 |
|------|------|------|
| Fast Backup | `APPEND_RESP` 携带 `conflictTerm/conflictIndex` | 日志对齐从 O(日志长度) 降为 O(term数) |
| 批量 Pipeline | `flushPipeline()` 合并心跳与 propose 发送 | 减少 RPC 次数，提升吞吐 |
| inFlight 流控 | `RaftPeer.inFlight` 标志 | 防止对慢速 Follower 无限堆积 |
| RaftLog ReadWriteLock | 读操作共享锁，写操作独占锁 | 多 Follower 并发读不互斥 |
| DelayQueue 超时清理 | `ProposeDeadline` 替代全量 Map 扫描 | O(k log n) vs O(n) |
| 连续选举上限 | `MAX_CONSECUTIVE_ELECTIONS = 10` | 防止网络分区下的选举风暴 |

---

## ADR-013：参考 ZooKeeper 的四项设计改进

**背景：** 对照 ZooKeeper 源码发现若干可借鉴的设计点。

**决策：** 引入以下改进：

**1. Session heartbeat 重新入队（修复 bug）**
- 原问题：`PriorityQueue` 中 session 的 `expireTime` 更新后队列位置不变，导致误判过期
- 修复：`heartbeat()` 做 `remove + offer` 重新入队，O(log n)
- 同时修复 `expireSession` 中 `channelToSession` 未清理的内存泄漏

**2. `WatchEvent.Type` 增加 `CHILDREN_CHANGED`**
- 对应 ZooKeeper `NodeChildrenChanged`
- `create/delete` 操作触发父节点 `CHILDREN_CHANGED`，语义比原来的 `NODE_DATA_CHANGED` 更准确

**3. `ZNode` 增加 `czxid/mzxid`（事务 ID）**
- 对应 ZooKeeper `Stat.czxid/mzxid`，记录创建和最后修改时的 Raft logIndex
- 为线性一致读奠定基础：客户端携带 `czxid`，服务端确保 `lastApplied >= czxid` 后响应

**4. 持久 Watcher（`registerPersistent`）**
- 对应 ZooKeeper 3.6 `addWatch PERSISTENT` 模式
- 触发后不自动注销，适合需要持续监听锁队列变化的场景
- `WatcherRegistry` SPI 新增 `registerPersistent` 方法

---

## 当前已知限制

| 限制 | 影响 | 计划 |
|------|------|------|
| ZNode 树为内存存储 | 节点重启后状态丢失（快照可恢复） | 实现 RocksDB 持久化 |
| 无 Raft 日志压缩 | 长期运行日志无限增长 | 实现 Log Compaction |
| REDIRECT 依赖 nodeId | 客户端需要维护 nodeId→地址映射 | 改为直接返回 host:port |
| 单线程 Raft 调度器 | 高并发下可能成为瓶颈 | 评估多线程优化 |
| DefaultZNodeTree 全局锁 | 不同锁名的写操作互相阻塞 | 按 lockRoot 路径分段加锁 |

---

## ADR-014：Raft 日志 WAL 持久化

**背景：** 原始实现日志存储在内存，节点重启后日志丢失，无法满足生产环境要求。

**决策：** 在 `RaftLog` 中实现 WAL（Write-Ahead Log）持久化，同时实现 `Lifecycle` 接口。

**格式：** 每行一条，`{index}\t{term}\t{command}`，command 中的 TAB/换行/反斜杠转义。

**关键设计：**
- `append`：追加写 + `channel.force(false)` fsync（data only，不强制 metadata，性能更好）
- `truncateFrom`：重写整个文件 + fsync + fsync 目录（确保 rename 后目录项持久化）
- `loadFromWal`：启动时校验 index 连续性，遇到跳跃截断并 warn（防止损坏数据静默接受）
- `RaftMetaStore`：`currentTerm/votedFor` 原子替换（写临时文件 → fsync → rename → fsync 目录）
- 重启恢复：加载快照 → 重放快照点之后的 WAL 增量日志

**两种模式：**
- `new RaftLog()`：内存模式（测试用）
- `new RaftLog(dataDir)`：WAL 持久化模式

---

## ADR-015：动态集群成员变更（Joint Consensus）

**背景：** 静态集群配置无法在不停机的情况下扩缩容。

**决策：** 实现 Raft §6 的 Joint Consensus 两阶段成员变更。

**核心类：**

| 类 | 职责 |
|----|------|
| `ClusterConfig` | 不可变配置值对象，支持 NORMAL 和 JOINT 两种阶段 |
| `MembershipChange` | 变更命令编解码，作为 Raft 日志条目传播 |

**两阶段流程：**
```
1. Leader propose C_old,new（JOINT）→ 复制到多数派 → apply
2. JOINT commit 后 Leader 自动 propose C_new（NORMAL）→ 复制 → apply
3. NORMAL commit 后清理不在新配置中的 peer 连接
4. 若 Leader 自身不在新配置中，主动降级为 FOLLOWER
```

**安全性：** JOINT 阶段任何决策（选举/提交）都需要 C_old 和 C_new 各自的多数派同意，防止脑裂。

**API：**
```java
// 添加成员（返回 CompletableFuture，变更完成后 complete）
raftNode.addMember("n4", "127.0.0.1", 9884);

// 移除成员
raftNode.removeMember("n3");
```

**约束：** 同一时刻只允许一个成员变更在途（`membershipChangePending` 标志）。

---

## ADR-016：Web 管理控制台（hutulock-admin 模块）

**背景：** 需要一个可视化界面管理集群状态、会话、锁和成员变更。

**决策：** 新增独立的 `hutulock-admin` Maven 模块，前后端分离。

**技术栈：**
- 后端：JDK 内置 `HttpServer`（零额外依赖）+ Session Token 鉴权
- 前端：Vue 3 + Element Plus + Pinia + Vue Router + Axios

**鉴权流程：**
```
POST /api/admin/login → 返回 Bearer token（SecureRandom 32 字节，有效期 8h）
→ 存 localStorage → axios 拦截器自动附加 Authorization 头
→ 401 时自动跳转登录页
```

**默认账户：** `admin` / `admin123`，可通过 `-Dhutulock.admin.username/password` 覆盖。

**前端构建：**
```bash
cd hutulock-admin/ui
npm install && npm run build
# 产物输出到 hutulock-admin/src/main/resources/admin-ui/，打包进 jar
```

**API 端点：**

| 端点 | 说明 |
|------|------|
| `POST /api/admin/login` | 登录，返回 token |
| `POST /api/admin/logout` | 注销 |
| `GET  /api/admin/cluster` | 集群状态（需鉴权） |
| `GET  /api/admin/sessions` | 会话列表（需鉴权） |
| `GET  /api/admin/locks` | 锁状态（需鉴权） |
| `POST /api/admin/members/add` | 添加成员（需鉴权） |
| `POST /api/admin/members/remove` | 移除成员（需鉴权） |
| `GET  /` | 前端 SPA 入口 |
