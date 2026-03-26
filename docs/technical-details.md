# HutuLock 技术细节

## 目录

1. [分布式锁模型](#1-分布式锁模型)
2. [Raft 共识算法](#2-raft-共识算法)
3. [顺序节点公平锁](#3-顺序节点公平锁)
4. [Session 会话机制](#4-session-会话机制)
5. [看门狗机制](#5-看门狗机制)
6. [Watcher 事件系统](#6-watcher-事件系统)
7. [事件总线](#7-事件总线)
8. [网络协议](#8-网络协议)
9. [IoC 容器](#9-ioc-容器)
10. [代理模块](#10-代理模块)
11. [兜底机制](#11-兜底机制)
12. [模块依赖图](#12-模块依赖图)

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
  │                                  │ 3. 触发 NODE_DELETED 事件
  │                                  │    → 通知监听 seq-N 的下一个等待者
  │←── RELEASED order-lock ──────────│
```

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

### 日志复制

```
Leader                    Follower
  │                           │
  │── APPEND_REQ ────────────→│  (携带日志条目)
  │←── APPEND_RESP ───────────│  (success=true/false)
  │                           │
  │  多数派确认后 commit        │
  │── 状态机 apply ────────────│
```

**日志条目格式（内部序列化）：**
```
{index}:{term}:{command}
多条目用 | 分隔，| 在 command 中转义为 \|
```

### Leader 切换时的 Propose 处理

- Leader 降级时，所有 `pendingCommits` 立即 `completeExceptionally("LEADER_CHANGED")`
- `LockServerHandler` 捕获后等待 500ms 重试，最多 3 次
- 超时未 commit 的 propose 由定时任务清理（默认 10s）

### 心跳

- Leader 每 `heartbeatIntervalMs`（默认 50ms）发送一次心跳（空的 APPEND_REQ）
- Follower 收到心跳后重置选举计时器

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

### ZNodePath 规则

- 必须以 `/` 开头
- 不能以 `/` 结尾（根路径除外）
- 路径段不能为空（不允许 `//`）
- 示例：`/locks/order-lock/seq-0000000001`

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

### 会话 ID 生成

```java
UUID.randomUUID().toString().replace("-", "").substring(0, 16)
// 示例：a3f8c2d1e4b5f6a7
```

### 会话过期扫描

- 后台线程每 `watchdogScanIntervalMs`（默认 1s）扫描一次
- 过期条件：`now - lastHeartbeat > sessionTimeout`
- 过期时：清理临时节点 → 触发 `NODE_DELETED` → 通知等待者

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

### 客户端看门狗（LockContext）

- 获锁成功后自动启动
- 每 `watchdogIntervalMs` 发送 `RENEW lockName sessionId`
- 连接断开时触发 `onExpired` 回调
- 约束：`watchdogInterval < ttl/3`（确保至少有 3 次续期机会）

### 服务端看门狗（WatchdogContext）

- 每把锁维护独立的 `WatchdogContext`
- 记录：持有者 clientId、TTL、最后心跳时间（`AtomicLong`）
- 扫描线程检测超时，强制释放并通知等待队列

### 看门狗状态

```
HELD ──(超时)──→ EXPIRED
  │
  └──(正常释放)──→ RELEASED
```

---

## 6. Watcher 事件系统

### 两种事件通知的区别

| 特性 | WatcherRegistry（网络推送） | EventBus（内部总线） |
|------|--------------------------|---------------------|
| 订阅方 | 网络客户端（Channel） | 内部 Java 组件 |
| 触发次数 | One-shot（触发后自动注销） | 持久订阅 |
| 传输方式 | Netty Channel 推送 | 内存队列异步分发 |
| 用途 | 锁等待通知 | 组件解耦、审计、监控 |

### WatchEvent 类型

| 类型 | 触发时机 |
|------|---------|
| NODE_CREATED | ZNode 被创建 |
| NODE_DELETED | ZNode 被删除（锁释放的核心信号） |
| NODE_DATA_CHANGED | ZNode 数据变更 |
| SESSION_EXPIRED | 会话过期 |

### Watcher 触发流程

```
ZNodeTree.delete(path)
    → WatcherRegistry.fire(path, NODE_DELETED)
        → 找到所有监听该路径的 Channel（One-shot，remove）
        → 向每个活跃 Channel 推送 "WATCH_EVENT NODE_DELETED /path\n"
            → 客户端 LockClientHandler 解析
                → 触发注册的 Consumer<WatchEvent> 回调
                    → 发送 RECHECK 命令
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

### 订阅者匹配规则

- 精确匹配：订阅 `LockEvent.class` 只收到 `LockEvent`
- 父类匹配：订阅 `HutuEvent.class` 收到所有事件
- 遍历事件类的继承链实现

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
| APPEND_RESP | `APPEND_RESP {term} {success} {matchIndex}` |

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

### ApplicationContext 工作流程

```
register(BeanDefinition)  → 记录定义，不触发实例化
getBean(name/type)        → 首次调用时执行工厂方法，结果缓存为单例
start()                   → 按注册顺序实例化所有 Bean，调用 Lifecycle.start()
close()                   → 按注册逆序调用 Lifecycle.shutdown()
```

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

### 模块结构

```
hutulock-proxy/
  api/
    Proxiable.java      代理类型枚举（LOGGING / METRICS / RETRY）
    ProxyCatalog.java   各 SPI 接口支持的代理类型声明
  handler/
    LoggingHandler.java  方法调用日志（入参、耗时、异常）
    MetricsHandler.java  调用次数、失败次数、平均耗时统计
    RetryHandler.java    失败自动重试
  support/
    ProxyBuilder.java    链式构建 API
```

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
    .withRetry(3, 500L,
        Set.of("release", "shutdown"),   // 不重试的方法
        RuntimeException.class)
    .build();

// 获取指标快照
ProxyBuilder.MetricsHolder<ZNodeStorage> holder = new ProxyBuilder.MetricsHolder<>();
ZNodeStorage proxied = ProxyBuilder.wrap(ZNodeStorage.class, realImpl)
    .withMetrics(holder)
    .build();
Map<String, String> stats = holder.snapshot();
// {"ZNodeStorage.create" → "calls=42 errors=0 avgMs=3", ...}
```

### 各 SPI 接口支持的代理类型

| SPI 接口 | Logging | Metrics | Retry | 说明 |
|----------|:-------:|:-------:|:-----:|------|
| `ZNodeStorage` | ✓ | ✓ | | 读写操作频繁，日志+指标均有价值 |
| `LockService` | ✓ | ✓ | ✓ | 核心业务，全量支持 |
| `SessionTracker` | ✓ | | | 生命周期管理，日志追踪即可 |
| `EventBus` | ✓ | | | 事件流追踪 |
| `WatcherRegistry` | ✓ | | | 注册/触发追踪 |

### LoggingHandler 日志格式

```
DEBUG [create] args=[/locks/order-lock, EPHEMERAL_SEQ, ...] → /locks/order-lock/seq-0000000001 (3ms)
WARN  [create] args=[/locks/order-lock, ...] threw HutuLockException (1ms): parent not found
```

---

## 11. 兜底机制

### 多层兜底设计

```
层级 1：客户端看门狗（主动续期）
    每 9s 发 RENEW，防止服务端 TTL 过期

层级 2：服务端看门狗（被动检测）
    每 1s 扫描，30s 无心跳强制释放

层级 3：Session 过期清理
    TCP 断开后 30s 内未重连，清理所有临时节点

层级 4：Raft propose 超时清理
    10s 未 commit 的 propose 自动 fail，防止内存泄漏

层级 5：Leader 切换时 fail pending proposes
    降级时立即通知所有等待中的 propose，触发客户端重试
```

### 等待队列孤儿处理

当等待者的 Channel 断开时：
1. `WatchdogManager.handleExpired()` 弹出等待队列
2. 检查 `channel.isActive()`
3. 若失活，跳过该等待者，继续弹出下一个
4. 直到找到活跃的等待者或队列为空

### 客户端重连策略

1. 收到 `REDIRECT` → 随机打乱节点列表，轮询重连
2. 重连成功后尝试恢复旧 sessionId
3. 若 session 已过期，创建新 session

---

## 12. 模块依赖图

```
┌─────────────────────────────────────────────────────────┐
│                    hutulock-server                       │
│                                                          │
│  ┌─────────────────────────────────────────────────┐    │
│  │  ioc/  ApplicationContext  ServerBeanFactory     │    │
│  │        BeanDefinition      Lifecycle             │    │
│  └─────────────────────────────────────────────────┘    │
│                                                          │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────┐  │
│  │ RaftNode │  │LockMgr   │  │SessionMgr│  │ZNode   │  │
│  │          │  │(Default) │  │(Default) │  │Tree    │  │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └───┬────┘  │
│       │              │              │             │       │
│  ┌────▼─────────────▼──────────────▼─────────────▼────┐ │
│  │              hutulock-spi                           │ │
│  │  LockService  SessionTracker  ZNodeStorage          │ │
│  │  MetricsCollector  EventBus  WatcherRegistry        │ │
│  │  Authenticator  Authorizer  AuditLogger             │ │
│  └─────────────────────┬───────────────────────────────┘ │
│                         │                                 │
│  ┌──────────────────────▼───────────────────────────────┐ │
│  │                 hutulock-model                        │ │
│  │  ZNode  ZNodePath  Session  WatchEvent  Message       │ │
│  └───────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│                    hutulock-proxy                        │
│  ProxyBuilder  LoggingHandler  MetricsHandler            │
│  RetryHandler  ProxyCatalog                              │
│       ↓ 依赖                                             │
│  hutulock-spi                                            │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│                    hutulock-client                       │
│  HutuLockClient  LockContext  LockClientHandler          │
│       ↓ 依赖                                             │
│  hutulock-spi + hutulock-model + hutulock-config         │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│                    hutulock-cli                          │
│  HutuLockCli  CliContext  CliCommand                     │
│       ↓ 依赖                                             │
│  hutulock-client                                         │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│                    hutulock-config                       │
│  YamlConfigProvider  ServerProperties  ClientProperties  │
│       ↓ 依赖                                             │
│  hutulock-model                                          │
└─────────────────────────────────────────────────────────┘
```
