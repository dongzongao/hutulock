# HutuLock 技术细节

## 目录

1. [分布式锁模型](#1-分布式锁模型)
   - [锁升级完整链路图](#锁升级完整链路图)
2. [Raft 共识算法](#2-raft-共识算法)
3. [顺序节点公平锁](#3-顺序节点公平锁)
4. [Session 会话机制](#4-session-会话机制)
5. [看门狗机制](#5-看门狗机制)
6. [Watcher 事件系统](#6-watcher-事件系统)
7. [事件总线](#7-事件总线)
8. [网络协议](#8-网络协议)
9. [IoC 容器](#9-ioc-容器)
10. [代理模块](#10-代理模块)
11. [内存管理](#11-内存管理)
12. [兜底机制](#12-兜底机制)
13. [JVM 调优](#13-jvm-调优)
14. [模块依赖图](#14-模块依赖图)

---

## 1. 分布式锁模型

### ZNode 树结构

```
/locks/                              PERSISTENT  根节点
/locks/{lockName}/                   PERSISTENT  锁根节点
/locks/{lockName}/seq-0000000001     EPHEMERAL_SEQ  持锁者（序号最小）
/locks/{lockName}/seq-0000000002     EPHEMERAL_SEQ  等待者（监听前一个节点）
/locks/{lockName}/seq-0000000003     EPHEMERAL_SEQ  等待者（监听前一个节点）
```

### 获取锁流程

```
客户端                          服务端（Leader）
  │                                  │
  │── LOCK order-lock sessionId ────→│
  │                                  │ 1. 创建 /locks/order-lock/seq-N（EPHEMERAL_SEQ）
  │                                  │ 2. 获取所有子节点，按序号排序
  │                                  │ 3. 判断 seq-N 是否最小
  │                                  │
  │←── OK order-lock /locks/.../seq-N│  (是最小，获锁成功)
  │  或                               │
  │←── WAIT order-lock /locks/.../seq-N│ (不是最小，注册 Watcher 监听 seq-(N-1))
  │                                  │
  │  [等待 WATCH_EVENT NODE_DELETED] │
  │←── WATCH_EVENT NODE_DELETED ...  │  (前一个节点被删除)
  │                                  │
  │── RECHECK order-lock seq-N sid ─→│  (重新检查是否是最小序号)
  │←── OK order-lock /locks/.../seq-N│  (获锁成功)
```

### 释放锁流程

```
客户端                          服务端（Leader）
  │                                  │
  │── UNLOCK /locks/.../seq-N sid ──→│
  │                                  │ 1. 验证 sessionId 是节点所有者
  │                                  │ 2. 删除 seq-N 节点
  │                                  │ 3. 触发 NODE_DELETED 事件（通知下一个等待者）
  │                                  │ 4. 触发父节点 CHILDREN_CHANGED 事件
  │←── RELEASED order-lock ──────────│
```

### 锁升级完整链路图

从客户端发起 LOCK 到最终持锁，经过以下完整链路：

```
客户端                    Netty Pipeline              服务端核心                  Raft 集群
   │                           │                          │                          │
   │── LOCK lockName sid ──────→│                          │                          │
   │                           │ SecurityChannelHandler   │                          │
   │                           │  ① 认证（Token/HMAC）    │                          │
   │                           │  ② 限流（TokenBucket）   │                          │
   │                           │  ③ 授权（ACL）           │                          │
   │                           │──────────────────────────→│                          │
   │                           │                    LockServerHandler                │
   │                           │                    ④ 解析 Message                  │
   │                           │                    ⑤ 校验 Session                  │
   │                           │                          │                          │
   │                           │                    DefaultLockManager               │
   │                           │                    ⑥ propose(LOCK cmd)             │
   │                           │                          │── APPEND_REQ ───────────→│
   │                           │                          │                   Follower 复制
   │                           │                          │←── APPEND_RESP（多数派）──│
   │                           │                          │                          │
   │                           │                    RaftStateMachine                 │
   │                           │                    ⑦ apply(LOCK cmd)               │
   │                           │                          │                          │
   │                           │                    DefaultZNodeTree                 │
   │                           │                    ⑧ create EPHEMERAL_SEQ          │
   │                           │                       /locks/{name}/seq-N           │
   │                           │                          │                          │
   │                           │                    ⑨ getChildren + sort            │
   │                           │                          │                          │
   │                           │              ┌───────────┴───────────┐             │
   │                           │         seq-N 最小？                  │             │
   │                           │              │ YES                   │ NO          │
   │                           │              ↓                       ↓             │
   │                           │         ⑩ 获锁成功              ⑩ 注册 Watcher    │
   │                           │         发布 LockEvent           监听 seq-(N-1)    │
   │                           │         ACQUIRED                                   │
   │                           │              │                       │             │
   │←── OK lockName seq-N ─────│              │         ┌─────────────┘             │
   │                           │              │    等待 NODE_DELETED                 │
   │                           │              │    WATCH_EVENT 推送                  │
   │                           │              │         │                            │
   │                           │              │    ⑪ RECHECK                        │
   │                           │              │    重新检查序号                       │
   │                           │              │         │                            │
   │                           │              └─────────┘                           │
   │                           │                        │                           │
   │  客户端持锁期间                                      │                           │
   │  ⑫ 看门狗每 9s 发 RENEW ──→│                        │                           │
   │←── RENEWED ───────────────│                        │                           │
   │                           │                        │                           │
   │── UNLOCK seq-N sid ───────→│                        │                           │
   │                           │                    ⑬ propose(UNLOCK cmd)           │
   │                           │                          │── APPEND_REQ ───────────→│
   │                           │                          │←── APPEND_RESP（多数派）──│
   │                           │                    ⑭ apply：delete seq-N           │
   │                           │                    ⑮ fire NODE_DELETED             │
   │                           │                       → 通知 seq-(N+1) 等待者       │
   │←── RELEASED lockName ─────│                        │                           │
```

**各步骤说明：**

| 步骤 | 位置 | 说明 |
|------|------|------|
| ① 认证 | `SecurityChannelHandler` | Token 或 HMAC-SHA256 签名验证 |
| ② 限流 | `RateLimitFilter` | 令牌桶，防止单客户端打爆服务端 |
| ③ 授权 | `AclAuthorizer` | 检查客户端是否有该锁路径的操作权限 |
| ④ 解析 | `LockServerHandler` | 文本行协议解析为 `Message` 对象 |
| ⑤ 校验 | `LockServerHandler` | 验证 sessionId 存在且未过期 |
| ⑥ propose | `DefaultLockManager` | 将 LOCK 命令提交给 Raft 状态机 |
| ⑦ apply | `RaftStateMachine` | Raft 多数派确认后执行状态机 |
| ⑧ 创建节点 | `DefaultZNodeTree` | 创建 `EPHEMERAL_SEQ` 顺序临时节点 |
| ⑨ 排序判断 | `DefaultLockManager` | 获取所有子节点，按序号排序，判断是否最小 |
| ⑩ 获锁/等待 | `DefaultLockManager` | 最小则获锁；否则注册 Watcher 监听前驱节点 |
| ⑪ RECHECK | `LockServerHandler` | Watcher 触发后重新检查，可能再次等待 |
| ⑫ 看门狗 | `LockContext`（客户端） | 每 `watchdogInterval` 发 RENEW，防止 TTL 过期 |
| ⑬⑭ 释放 | `DefaultLockManager` | UNLOCK 同样走 Raft propose → apply |
| ⑮ 通知 | `WatcherRegistry` | 删除节点后触发 `NODE_DELETED`，唤醒下一个等待者 |

### 公平性保证（避免羊群效应）

每个等待者只监听**前一个**节点，而不是锁根节点：

```
seq-1 (持锁)  ←监听─  seq-2 (等待)  ←监听─  seq-3 (等待)
```

当 seq-1 释放时，只有 seq-2 收到通知，seq-3 不受影响。

---

## 2. Raft 共识算法

### 节点角色状态机

```
FOLLOWER ──(选举超时)──→ CANDIDATE ──(多数票)──→ LEADER
    ↑                        │                      │
    └────────────────────────┘◄─────────────────────┘
              (收到更高 term 的消息)
```

### 选举机制

- 选举超时：随机 `[electionTimeoutMin, electionTimeoutMax]`（默认 150~300ms）
- 随机化避免分票（Split Vote）
- 候选人向所有节点发送 `VOTE_REQ`，收到多数派（`n/2 + 1`）投票后成为 Leader
- 连续选举超过 10 次后强制冷却一个超时周期，防止网络分区下的选举风暴

### Zab 风格 Recovery Phase（Leader 上任同步）

```
becomeLeader()
  → leaderReady = false（暂不开放 propose）
  → 广播第一轮心跳（空 AppendEntries）
  → Follower ack 回来 → onSyncAck() 计数
  → 达到多数派 → completeSyncPhase() → leaderReady = true
  → 触发 pendingSyncQueue 里排队的 propose
```

Leader 上任期间收到的 propose 排入 `pendingSyncQueue`，同步完成后批量触发，避免第一批写入触发多轮 nextIndex 回退。

### 日志复制与 Fast Backup

```
Leader                    Follower
  │                           │
  │── APPEND_REQ ────────────→│  (携带日志条目)
  │←── APPEND_RESP ───────────│  (success + conflictTerm + conflictIndex)
  │                           │
  │  多数派确认后 commit        │
  │── 状态机 apply ────────────│
```

**Fast Backup（§5.3 优化）：** Follower 拒绝时携带冲突 term 及其第一条索引，Leader 直接跳到该位置，日志对齐从 O(日志长度) 降为 O(term数)。

**APPEND_RESP 格式（含 Fast Backup 字段）：**
```
APPEND_RESP {term} {success} {matchIndex} {nodeId} {conflictTerm} {conflictIndex}
```

### 批量 Pipeline 与 inFlight 流控

- `flushPipeline()`：心跳和 propose 触发时统一调用，合并发送，减少 RPC 次数
- `RaftPeer.inFlight`：上一条 AppendEntries 未收到 ack 时跳过该 peer，防止对慢速 Follower 无限堆积
- ack 回来后若还有积压日志立即补发（pipeline 效果）

### RaftLog 并发模型

使用 `ReadWriteLock` 替代全局 `synchronized`：
- 读操作（`get/getFrom/termAt/lastIndex/lastTerm`）：共享锁，多 Follower 并发读不互斥
- 写操作（`append/truncateFrom`）：独占锁

### WAL 持久化

`RaftLog` 支持两种模式：

| 模式 | 构造方式 | 用途 |
|------|---------|------|
| 内存模式 | `new RaftLog()` | 测试 |
| WAL 模式 | `new RaftLog(dataDir)` | 生产 |

**WAL 格式（`raft-log.wal`，每行一条）：**
```
{index}\t{term}\t{command}
```
command 中的 TAB → `\t`，换行 → `\n`，反斜杠 → `\\`。

**写入策略：**
- `append`：追加写 + `channel.force(false)` fsync data
- `truncateFrom`：重写整个文件 + fsync + fsync 目录（确保 rename 持久化）

**崩溃恢复：**
- 启动时校验 index 连续性，遇到跳跃截断并 warn
- 加载快照 → 重放快照点之后的 WAL 增量日志

**元数据持久化（`RaftMetaStore`）：**
- `currentTerm/votedFor` 写临时文件 → `SYNC` → rename → fsync 目录（原子替换）

### 动态成员变更（Joint Consensus）

两阶段流程（Raft §6）：

```
Phase 1: propose C_old,new（JOINT）
  → 复制到多数派（需 C_old 和 C_new 各自多数派同意）
  → apply：更新 clusterConfig 为 JOINT

Phase 2: Leader 自动 propose C_new（NORMAL）
  → 复制到多数派
  → apply：更新 clusterConfig 为 NORMAL，清理旧 peer 连接
  → 若 Leader 不在 C_new 中，主动降级
```

**JOINT 阶段 quorum 计算：**
```java
// 需要 C_old 和 C_new 各自的多数派都满足
boolean hasQuorum = countQuorum(selfId, oldMembers, matchCounts, threshold)
                 && countQuorum(selfId, newMembers, matchCounts, threshold);
```

### propose 超时管理

使用 `DelayQueue<ProposeDeadline>` 替代全量 Map 扫描：
- 每个 propose 注册一个 `ProposeDeadline` 条目
- `cleanupTimedOutProposes` 只 poll 真正到期的条目，O(k log n) vs O(n)

### Leader 切换时的 Propose 处理

- Leader 降级时，所有 `pendingCommits` 立即 `completeExceptionally("LEADER_CHANGED")`
- 重置所有 peer 的 `inFlight` 标志和 `conflictTerm/conflictIndex`
- 清理 `pendingSyncQueue` 中排队的 propose
- `LockServerHandler` 捕获后等待 `proposeRetryDelayMs` 重试，最多 `proposeRetryCount` 次

---

## 3. 顺序节点公平锁

### ZNodeType 说明

| 类型 | 生命周期 | 序号 | 用途 |
|------|---------|------|------|
| PERSISTENT | 永久 | 无 | 锁根节点 |
| EPHEMERAL | 会话绑定 | 无 | 临时占位 |
| PERSISTENT_SEQ | 永久 | 有 | 持久排队 |
| EPHEMERAL_SEQ | 会话绑定 | 有 | 分布式锁核心 |

### 顺序节点序号分配

- 每个父路径维护独立的 `AtomicInteger` 计数器
- 序号格式：10 位补零，如 `seq-0000000001`
- 序号单调递增，不会重置（即使节点被删除）

### ZNode Stat（元数据）

| 字段 | 类型 | 说明 |
|------|------|------|
| `version` | int | 数据版本，每次 setData 递增 |
| `createTime` | long | 创建时间戳（ms） |
| `modifyTime` | long | 最后修改时间戳（ms） |
| `czxid` | long | 创建时的 Raft logIndex（参考 ZooKeeper Stat.czxid） |
| `mzxid` | long | 最后修改时的 Raft logIndex（参考 ZooKeeper Stat.mzxid） |

`czxid/mzxid` 为线性一致读奠定基础：客户端可携带 `czxid` 发起读请求，服务端确保 `lastApplied >= czxid` 后再响应，避免读到旧 Leader 的过期数据。

---

## 4. Session 会话机制

### 会话状态机

```
CONNECTING ──→ CONNECTED ──→ RECONNECTING ──→ EXPIRED
                   │                               ↑
                   └──────────────────────────────→ CLOSED
```

### 会话与 TCP 连接解耦

- TCP 断开 ≠ 会话过期
- TCP 断开时会话进入 `RECONNECTING` 状态
- 客户端在 `sessionTimeout` 内重连可恢复会话（临时节点不被删除）
- 超时未重连才触发会话过期，清理临时节点

### 会话过期扫描（PriorityQueue + 重新入队）

- 后台线程每 `watchdogScanIntervalMs`（默认 1s）扫描一次
- 使用 `PriorityQueue` 按 `expireTime` 排序，只检查队头，O(k log s) 而非 O(s)
- **关键：** `heartbeat()` 调用 `remove + offer` 重新入队，确保续期后队列位置更新（参考 ZooKeeper ExpiryQueue 设计）
- 过期时：清理临时节点 → 触发 `NODE_DELETED` → 通知等待者 → 推送 `SESSION_EXPIRED` 给客户端

---

## 5. 看门狗机制

### 双层看门狗

```
客户端看门狗（主动续期）          服务端看门狗（被动兜底）
       │                                  │
  每 9s 发 RENEW ──────────────→  重置 lastHeartbeat
                                          │
                              每 1s 扫描：now - lastHeartbeat > 30s
                                          │
                              强制释放锁，通知下一个等待者
```

**约束：** `watchdogInterval < ttl/3`（确保至少有 3 次续期机会）

---

## 6. Watcher 事件系统

### 两种 Watcher 模式

| 模式 | 注册方法 | 触发后行为 | 适用场景 |
|------|---------|-----------|---------|
| One-shot | `register()` | 自动注销 | 锁等待通知（默认） |
| 持久 | `registerPersistent()` | 不注销，持续触发 | 监听锁队列整体变化 |

持久 Watcher 参考 ZooKeeper 3.6 `addWatch PERSISTENT` 模式，连接断开时通过 `removeChannel` 统一清理。

### WatchEvent 类型

| 类型 | 触发时机 |
|------|---------|
| `NODE_CREATED` | ZNode 被创建 |
| `NODE_DELETED` | ZNode 被删除（锁释放的核心信号） |
| `NODE_DATA_CHANGED` | ZNode 数据变更 |
| `CHILDREN_CHANGED` | 子节点列表变化（增删子节点时触发父节点，参考 ZooKeeper NodeChildrenChanged） |
| `SESSION_EXPIRED` | 会话过期 |

### Watcher 触发流程

```
ZNodeTree.delete(path)
    → WatcherRegistry.fire(path, NODE_DELETED)
        → One-shot watcher：remove 后推送
        → 持久 watcher：推送但不 remove
    → WatcherRegistry.fire(parent, CHILDREN_CHANGED)
        → 通知监听父节点子列表变化的 watcher
```

---

## 7. 事件总线

### DefaultEventBus 实现

- 基于 `LinkedBlockingQueue`（容量 10000）
- 固定大小线程池（默认 2 个分发线程）
- 发布操作 O(1)，立即返回
- 订阅者异常隔离：单个订阅者抛出异常不影响其他订阅者

### 事件类型层次

```
HutuEvent（抽象基类）
├── LockEvent     ACQUIRED / ACQUIRED_QUEUED / WAITING / RELEASED / EXPIRED
├── SessionEvent  CREATED / DISCONNECTED / RECONNECTED / EXPIRED / CLOSED
├── RaftEvent     ELECTION_STARTED / BECAME_LEADER / STEPPED_DOWN / LOG_COMMITTED
└── ZNodeEvent    CREATED / DELETED / DATA_CHANGED
```

---

## 8. 网络协议

### 文本行协议

- 编码：UTF-8
- 分帧：`LineBasedFrameDecoder`（最大帧长 4096 字节）
- 格式：`{TYPE} {arg0} {arg1} ...\n`

### 客户端 → 服务端命令

| 命令 | 格式 | 说明 |
|------|------|------|
| CONNECT | `CONNECT [sessionId] [TOKEN:xxx]` | 建立/恢复会话 |
| LOCK | `LOCK {lockName} {sessionId}` | 获取锁 |
| UNLOCK | `UNLOCK {seqNodePath} {sessionId}` | 释放锁 |
| RECHECK | `RECHECK {lockName} {seqNodePath} {sessionId}` | Watcher 触发后重新检查 |
| RENEW | `RENEW {lockName} {sessionId}` | 心跳续期 |

### 服务端 → 客户端响应

| 响应 | 格式 | 说明 |
|------|------|------|
| CONNECTED | `CONNECTED {sessionId}` | 会话建立成功 |
| OK | `OK {lockName} {seqNodePath}` | 获锁成功 |
| WAIT | `WAIT {lockName} {seqNodePath}` | 进入等待，已注册 Watcher |
| RELEASED | `RELEASED {lockName}` | 锁已释放 |
| RENEWED | `RENEWED {lockName}` | 续期成功 |
| REDIRECT | `REDIRECT {nodeId}` | 非 Leader，重定向 |
| WATCH_EVENT | `WATCH_EVENT {type} {path}` | Watcher 事件推送 |
| ERROR | `ERROR {message}` | 错误响应 |

### Raft 节点间协议

| 消息 | 格式 |
|------|------|
| VOTE_REQ | `VOTE_REQ {term} {candidateId} {lastLogIndex} {lastLogTerm}` |
| VOTE_RESP | `VOTE_RESP {term} {granted}` |
| APPEND_REQ | `APPEND_REQ {term} {leaderId} {prevLogIndex} {prevLogTerm} {leaderCommit} {entries}` |
| APPEND_RESP | `APPEND_RESP {term} {success} {matchIndex} {nodeId} {conflictTerm} {conflictIndex}` |

> `conflictTerm/conflictIndex` 为 Fast Backup 字段，`success=false` 时有效，`-1` 表示无冲突信息（兼容旧版本）。

---

## 9. IoC 容器

### 核心设计

容器位于 `com.hutulock.server.ioc`，共 4 个类：

| 类 | 职责 |
|----|------|
| `BeanDefinition<T>` | Bean 元数据（名称、类型、`Supplier` 工厂） |
| `ApplicationContext` | 容器核心：延迟实例化、单例缓存、生命周期调度 |
| `Lifecycle` | 生命周期接口（`start()` / `shutdown()`） |
| `ServerBeanFactory` | 服务端专属配置，集中声明所有 Bean 及依赖关系 |

### 代理 Bean 与 Lifecycle 分离模式

JDK 动态代理只暴露目标接口，会屏蔽实现类上额外实现的 `Lifecycle`。
解决方案：真实实现和代理版本分别注册为不同名称的 Bean：

```java
// 真实实现（容器可感知 Lifecycle.shutdown()）
ctx.register(BeanDefinition.of("sessionManager", DefaultSessionManager.class,
    () -> new DefaultSessionManager(...)));

// 代理版本（对外暴露的接口 Bean，带日志增强）
ctx.register(BeanDefinition.of("sessionTracker", SessionTracker.class,
    () -> ProxyBuilder.wrap(SessionTracker.class, ctx.getBean(DefaultSessionManager.class))
            .withLogging().build()));
```

---

## 10. 代理模块

### ProxyBuilder 链式 API

```java
// 日志 + 指标双层代理
ZNodeStorage proxied = ProxyBuilder.wrap(ZNodeStorage.class, realImpl)
    .withLogging()
    .withMetrics()
    .build();
// 调用链（外层先执行）：LoggingProxy → MetricsProxy → realImpl

// 带重试（LockService 专用）
LockService retried = ProxyBuilder.wrap(LockService.class, lockService)
    .withLogging()
    .withRetry(3, 500L, Set.of("release", "shutdown"), RuntimeException.class)
    .build();
```

---

## 11. 内存管理

### MemoryManager

统一管理两个内存优化组件，注册到 IoC 容器作为单例：

| 组件 | 作用 |
|------|------|
| `ZNodePathCache` | ZNodePath 对象缓存（String intern 模式），消除重复 new |
| `ObjectPool<PooledLockToken>` | LockToken 对象池（两级：ThreadLocal + 全局），减少 GC 压力 |

### ZNodePathCache

- 最大缓存 8192 条，超出后降级为直接 new
- 预计算 10 万个顺序节点序号字符串（`SEQ_STRINGS`），避免 `String.format`
- 顺序节点删除时主动 evict，防止内存泄漏

### ObjectPool（两级池）

```
borrow()
  → ThreadLocal ArrayDeque（无锁，O(1)）
  → 本地池空 → 从全局 ArrayBlockingQueue 批量转移 16 个
  → 全局池也空 → factory.get() 直接 new

release()
  → ThreadLocal ArrayDeque（无锁，O(1)）
  → 本地池满（>32）→ 批量归还 16 个到全局池
```

---

## 12. 兜底机制

### 多层兜底设计

```
层级 1：客户端看门狗（主动续期）
    每 9s 发 RENEW，防止服务端 TTL 过期

层级 2：服务端看门狗（被动检测）
    每 1s 扫描，30s 无心跳强制释放

层级 3：Session 过期清理
    TCP 断开后 30s 内未重连，清理所有临时节点

层级 4：Raft propose 超时清理（DelayQueue）
    10s 未 commit 的 propose 自动 fail，防止内存泄漏

层级 5：Leader 切换时 fail pending proposes
    降级时立即通知所有等待中的 propose，触发客户端重试

层级 6：Raft 消息格式兜底
    APPEND_REQ/RESP、VOTE_REQ/RESP 均有字段数量校验和 NumberFormatException 捕获

层级 7：连续选举上限
    超过 10 次连续选举后强制冷却，防止网络分区下的选举风暴
```

### Raft 各模块兜底汇总

| 模块 | 兜底点 |
|------|--------|
| `RaftPeer.send()` | 发送失败记录 warn，不再静默丢弃 |
| `RaftPeerHandler` | `channelInactive` 记录断连，`exceptionCaught` 关闭 channel 触发自动重连 |
| `RaftNode.handlePeerMessage()` | 空消息守卫 + 整体 try/catch，异常不传播到 Netty pipeline |
| `RaftNode.startRaftServer()` | 端口绑定失败打印明确错误日志 |
| `RaftElection.startElection()` | 连续选举计数上限，超限强制冷却 |
| `RaftElection.handleVoteReq/Resp()` | 字段数量校验，格式不合法直接 warn 并 return |
| `RaftReplication.propose()` | 非 Leader 时按配置重试；Leader 未就绪时排队 |
| `RaftReplication.handleAppendReq/Resp()` | 字段数量校验 + NumberFormatException 捕获 |

---

## 13. JVM 调优

hutulock-server 是低延迟锁服务，核心诉求是**最小化 GC 停顿**（直接影响 Raft 心跳和锁操作的 P99 延迟）。调优参数集中在 `bin/server.sh` 和 `docker/entrypoint.sh`。

### 调优目标

| 指标 | 目标值 | 说明 |
|------|--------|------|
| GC 停顿 P99 | < 20ms | Raft 心跳默认 50ms，停顿超过 1/3 会触发误选举 |
| 堆外内存 | ≤ 256MB | Netty DirectBuffer 不走堆，需单独限制 |
| 堆扩容 | 禁止 | `Xms=Xmx` 避免运行时扩容触发 Full GC |

### GC 收集器选择

```
JDK 版本检测
    ├── JDK 15+  →  ZGC（亚毫秒停顿，推荐）
    └── JDK 11   →  G1GC（MaxGCPauseMillis=20）
```

**ZGC 参数（JDK 15+）：**

| 参数 | 值 | 说明 |
|------|----|------|
| `-XX:+UseZGC` | — | 启用 ZGC |
| `-XX:ZCollectionInterval` | `5` | 最大 5s 触发一次 GC，防止内存长期不回收 |
| `-XX:ZUncommitDelay` | `60` | 60s 后将空闲堆内存归还给 OS（容器友好） |

**G1GC 参数（JDK 11 降级）：**

| 参数 | 值 | 说明 |
|------|----|------|
| `-XX:+UseG1GC` | — | 启用 G1GC |
| `-XX:MaxGCPauseMillis` | `20` | 目标停顿 ≤ 20ms |
| `-XX:G1HeapRegionSize` | `4m` | 4MB region，减少大对象直接晋升 Old 区 |
| `-XX:G1NewSizePercent` | `20` | 新生代最小 20%，避免过小导致频繁 Minor GC |
| `-XX:G1MaxNewSizePercent` | `40` | 新生代最大 40% |
| `-XX:InitiatingHeapOccupancyPercent` | `35` | 35% 时触发并发标记（默认 45%，提前触发减少 Mixed GC 压力） |
| `-XX:G1MixedGCCountTarget` | `8` | 混合 GC 分 8 次完成，每次停顿更短 |

### 堆内存

```bash
-Xms512m -Xmx512m   # Xms = Xmx，禁止运行时堆扩容
```

`Xms=Xmx` 是低延迟服务的标准做法：堆扩容会触发 Full GC，在 Raft 选举窗口内可能导致 Leader 误判。

### Netty 堆外内存

Netty 默认使用 `DirectBuffer`（堆外），不受 `-Xmx` 限制，需单独控制：

| 参数 | 值 | 说明 |
|------|----|------|
| `-XX:MaxDirectMemorySize` | `256m` | 限制堆外内存上限，防止 OOM |
| `-Dio.netty.leakDetection.level` | `disabled` | 关闭泄漏检测采样（生产环境，有 ~1% 性能开销） |
| `-Dio.netty.allocator.type` | `pooled` | 显式指定池化分配器，减少 DirectBuffer 分配次数 |

### JIT 编译优化

| 参数 | 说明 |
|------|------|
| `-XX:+TieredCompilation` | 分层编译（JDK 8+ 默认开启，显式声明） |
| `-XX:CompileThreshold=1000` | 降低 JIT 触发阈值（默认 10000），加快热身，减少解释执行时间 |
| `-XX:+OptimizeStringConcat` | 优化字符串拼接，对协议序列化（`Message.serialize()`）有效 |
| `-XX:+UseStringDeduplication` | 字符串去重，ZNode 路径高度重复（如 `/locks/order-lock/seq-0000000001`），可节省 10~20% 堆空间 |

### 线程栈

```bash
-Xss256k   # 默认 512k，Netty worker 线程多，减小栈大小降低内存占用
```

Netty 默认 `workerThreads = 2 * CPU`，每个线程节省 256k，16 核机器可节省约 4MB。

### 其他关键参数

| 参数 | 说明 |
|------|------|
| `-Djava.security.egd=file:/dev/./urandom` | 加速随机数生成，影响 HMAC 认证（`/dev/random` 在熵不足时会阻塞） |
| `-XX:+DisableExplicitGC` | 禁止代码中调用 `System.gc()`，防止 Netty 内部触发意外 Full GC |
| `-XX:+ExitOnOutOfMemoryError` | OOM 时直接退出，让 K8s/Supervisor 重启，避免进程僵死 |
| `-XX:+HeapDumpOnOutOfMemoryError` | OOM 时自动 dump，便于事后分析 |

### GC 日志

```bash
-Xlog:gc*:file=${LOG_DIR}/gc-${NODE_ID}.log:time,uptime,level,tags:filecount=5,filesize=20m
```

- 滚动写入，最多 5 个文件，每个 20MB
- 包含 GC 类型、停顿时间、堆使用情况
- 可用 `GCViewer` 或 `GCEasy` 分析

### 快速参考：完整参数组合

**生产环境（JDK 17+，4 核 8GB 节点）：**

```bash
-Xms2g -Xmx2g
-XX:+UseZGC
-XX:ZCollectionInterval=5
-XX:ZUncommitDelay=60
-XX:MaxDirectMemorySize=512m
-Xss256k
-XX:CompileThreshold=1000
-XX:+UseStringDeduplication
-XX:+DisableExplicitGC
-XX:+ExitOnOutOfMemoryError
-XX:+HeapDumpOnOutOfMemoryError
-Djava.security.egd=file:/dev/./urandom
-Dio.netty.leakDetection.level=disabled
-Xlog:gc*:file=/app/logs/gc.log:time,uptime,level,tags:filecount=5,filesize=20m
```

**开发/测试环境（JDK 11，1 核 1GB）：**

```bash
-Xms256m -Xmx256m
-XX:+UseG1GC
-XX:MaxGCPauseMillis=50
-XX:MaxDirectMemorySize=128m
-XX:+ExitOnOutOfMemoryError
```

---

## 14. 模块依赖图

```
┌─────────────────────────────────────────────────────────┐
│                    hutulock-server                       │
│                                                         │
│  ┌─────────────────────────────────────────────────┐   │
│  │  ioc/  ApplicationContext  ServerBeanFactory     │   │
│  │        BeanDefinition      Lifecycle             │   │
│  └─────────────────────────────────────────────────┘   │
│                                                         │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────┐  │
│  │ RaftNode │  │LockMgr   │  │SessionMgr│  │ZNode   │  │
│  │ Election │  │(Default) │  │(Default) │  │Tree    │  │
│  │ Replicat.│  │          │  │          │  │        │  │
│  │ WAL/Snap │  │          │  │          │  │        │  │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └───┬────┘  │
│       │              │              │             │      │
│  ┌────▼─────────────▼──────────────▼─────────────▼────┐ │
│  │              hutulock-spi                           │ │
│  │  LockService  SessionTracker  ZNodeStorage          │ │
│  │  MetricsCollector  EventBus  WatcherRegistry        │ │
│  │  Authenticator  Authorizer  AuditLogger             │ │
│  └─────────────────────┬───────────────────────────────┘ │
│                         │                                │
│  ┌──────────────────────▼──────────────────────────────┐ │
│  │                 hutulock-model                       │ │
│  │  ZNode(czxid/mzxid)  ZNodePath  Session             │ │
│  │  WatchEvent(CHILDREN_CHANGED)  Message               │ │
│  └──────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────┘
         ▲                ▲                ▲
    Java SDK         Python SDK         Go SDK

┌─────────────────────────────────────────────────────────┐
│                   hutulock-admin                         │
│                                                         │
│  AdminApiServer（JDK HttpServer + REST API）             │
│  AdminTokenStore（SecureRandom Token + 8h TTL）          │
│  ui/（Vue 3 + Element Plus，构建产物打包进 jar）           │
│                                                         │
│  依赖：hutulock-server（RaftNode、DefaultSessionManager、 │
│        DefaultZNodeTree）                                │
└─────────────────────────────────────────────────────────┘
