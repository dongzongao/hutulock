# HutuLock 测试说明

## 测试结构

```
hutulock-model/src/test/
  com.hutulock.model.znode/
    ZNodePathTest.java          ZNode 路径校验、父路径、名称提取
  com.hutulock.model.protocol/
    MessageTest.java            协议解析、序列化、边界条件
  com.hutulock.model.session/
    SessionTest.java            会话状态机、心跳、过期检测

hutulock-server/src/test/
  com.hutulock.server.impl/
    DefaultZNodeTreeTest.java   ZNode 树创建/删除/查询/Watcher 触发
    LockIntegrationTest.java    锁获取/释放/竞争/会话过期完整流程
  com.hutulock.server.event/
    DefaultEventBusTest.java    事件订阅/发布/异常隔离/统计
  com.hutulock.server.security/
    TokenAuthenticatorTest.java Token 认证、时序攻击防护
    AclAuthorizerTest.java      ACL 规则匹配、通配符、默认策略
    TokenBucketRateLimiterTest  令牌桶限流、并发安全、令牌补充
  com.hutulock.server.mem/
    ObjectPoolTest.java         对象池 borrow/release、统计、并发安全（14 个用例）
    ZNodePathCacheTest.java     路径缓存命中/evict/formatSeq/容量降级（12 个用例）
    PooledLockTokenTest.java    PooledLockToken init/reset/token/heldMillis（7 个用例）
  com.hutulock.server.raft/
    RaftLogWalTest.java         WAL 写入/重启恢复/truncate/特殊字符/容错（20 个用例）
    ClusterMembershipTest.java  ClusterConfig quorum/选举、MembershipChange 编解码（18 个用例）
    RaftSyncTest.java           Raft 数据同步（单节点/三节点/顺序一致性/落后追赶）
  com.hutulock.server.stress/
    LockStressTest.java         压力测试（见下方）
```

---

## 运行测试

```bash
# 运行所有测试
mvn test -f hutulock/pom.xml

# 只运行 model 层测试
mvn test -pl hutulock-model -f hutulock/pom.xml

# 只运行 server 层测试
mvn test -pl hutulock-server -f hutulock/pom.xml

# 跳过压力测试（快速验证）
mvn test -pl hutulock-server -f hutulock/pom.xml \
  -Dtest='!LockStressTest'
```

---

## 单元测试覆盖

### ZNodePathTest（10 个用例）

| 测试 | 验证点 |
|------|--------|
| `validPaths` | 合法路径正常创建 |
| `invalidPath_noLeadingSlash` | 无 `/` 前缀抛异常 |
| `invalidPath_trailingSlash` | 尾部 `/` 抛异常 |
| `invalidPath_emptySegment` | 空路径段抛异常 |
| `parent` | 父路径逐级提取 |
| `rootHasNoParent` | 根路径无父路径 |
| `name` | 最后一段名称提取 |
| `childOf` | 子路径拼接 |
| `equality` | 路径相等性 |
| `normalizesDoubleSlash` | 双斜杠被拒绝（预期行为） |

### MessageTest（10 个用例）

| 测试 | 验证点 |
|------|--------|
| `parseConnect` | 无参数命令解析 |
| `parseLock` | 带参数命令解析 |
| `parseOk` | 服务端响应解析 |
| `parseUnknownCommand` | 未知命令抛异常 |
| `parseEmptyLine` | 空行/null 抛异常 |
| `missingArgThrows` | 参数不足抛异常 |
| `serialize` | 序列化为文本行 |
| `serializeNoArgs` | 无参数序列化 |
| `caseInsensitiveParse` | 大小写不敏感 |
| `roundTrip` | 序列化后反序列化一致 |

### SessionTest（6 个用例）

| 测试 | 验证点 |
|------|--------|
| `initialState` | 初始状态为 CONNECTING，sessionId 16 位 |
| `heartbeatUpdatesTimestamp` | 心跳更新时间戳 |
| `expiredWhenNoHeartbeat` | 50ms TTL 后过期 |
| `notExpiredAfterHeartbeat` | 续期后不过期 |
| `isAlive` | CONNECTED/RECONNECTING 为活跃 |
| `heartbeatIgnoredWhenExpired` | 过期后心跳无效 |

---

## 集成测试

### LockIntegrationTest（3 个用例）

**测试环境：** 内存 ZNode 树 + Mock Channel，无网络

| 测试 | 场景 |
|------|------|
| `singleClientAcquireAndRelease` | 单客户端获取锁 → 收到 OK → 释放 → 收到 RELEASED |
| `twoClientsCompeteForLock` | client-1 获锁 → client-2 等待 → client-1 释放 → client-2 recheck → 获锁 |
| `sessionExpireReleasesLock` | client-1 获锁 → 会话过期 → 清理临时节点 → client-2 recheck → 获锁


---

## IoC 容器测试

### ApplicationContext 测试要点

```java
// 验证延迟实例化
ApplicationContext ctx = new ApplicationContext();
AtomicInteger count = new AtomicInteger();
ctx.register(BeanDefinition.of("bean", Runnable.class, () -> {
    count.incrementAndGet();
    return () -> {};
}));
// 此时 count == 0，工厂尚未调用
ctx.getBean(Runnable.class);
ctx.getBean(Runnable.class); // 第二次调用不触发工厂
assert count.get() == 1;    // 单例，只实例化一次

// 验证生命周期顺序
List<String> order = new ArrayList<>();
ctx.register(BeanDefinition.of("a", Lifecycle.class, () -> new Lifecycle() {
    public void start() { order.add("start-a"); }
    public void shutdown() { order.add("stop-a"); }
}));
ctx.register(BeanDefinition.of("b", Object.class, () -> new Lifecycle() {
    public void start() { order.add("start-b"); }
    public void shutdown() { order.add("stop-b"); }
}));
ctx.start();
ctx.close();
// order == ["start-a", "start-b", "stop-b", "stop-a"]

// 验证 Bean 覆盖（测试替换）
ctx.register(BeanDefinition.of("eventBus", EventBus.class, () -> mockEventBus));
// 同名 Bean 覆盖，后续 getBean("eventBus") 返回 mock
```

---

## 代理模块测试

### ProxyBuilder 测试要点

```java
// 验证 LoggingHandler 记录调用
List<String> logs = new ArrayList<>();
ZNodeStorage proxied = ProxyBuilder.wrap(ZNodeStorage.class, realImpl)
    .withLogging()
    .build();
proxied.exists(ZNodePath.of("/test"));
// 验证 DEBUG 日志包含方法名和耗时

// 验证 MetricsHandler 统计
ProxyBuilder.MetricsHolder<ZNodeStorage> holder = new ProxyBuilder.MetricsHolder<>();
ZNodeStorage proxied = ProxyBuilder.wrap(ZNodeStorage.class, realImpl)
    .withMetrics(holder)
    .build();
proxied.exists(ZNodePath.of("/test"));
proxied.exists(ZNodePath.of("/test"));
Map<String, String> snap = holder.snapshot();
assert snap.get("ZNodeStorage.exists").contains("calls=2");

// 验证 RetryHandler 重试次数
AtomicInteger attempts = new AtomicInteger();
LockService failingImpl = (name, sid, t, u) -> {
    if (attempts.incrementAndGet() < 3) throw new RuntimeException("fail");
    return mockToken;
};
LockService retried = ProxyBuilder.wrap(LockService.class, failingImpl)
    .withRetry(3, 0L, Set.of(), RuntimeException.class)
    .build();
retried.tryAcquire("lock", "sid", 1, TimeUnit.SECONDS);
assert attempts.get() == 3; // 前两次失败，第三次成功

// 验证不可重试方法不重试
AtomicInteger releaseAttempts = new AtomicInteger();
// release 在 noRetryMethods 中，抛异常后不重试
```

### ProxyCatalog 测试要点

```java
// ZNodeStorage 支持 LOGGING 和 METRICS，不支持 RETRY
assert ProxyCatalog.supports(ZNodeStorage.class, ProxyType.LOGGING);
assert ProxyCatalog.supports(ZNodeStorage.class, ProxyType.METRICS);
assert !ProxyCatalog.supports(ZNodeStorage.class, ProxyType.RETRY);

// LockService 全量支持
assert ProxyCatalog.supportedTypes(LockService.class)
    .containsAll(EnumSet.allOf(ProxyType.class));

// 未注册接口返回空集合
assert ProxyCatalog.supportedTypes(Object.class).isEmpty();
```

---

## 压力测试

### LockStressTest

**测试场景：** 多线程并发竞争同一把锁，验证公平性和正确性。

```bash
# 运行压力测试（默认跳过，需显式指定）
mvn test -pl hutulock-server -f hutulock/pom.xml -Dtest=LockStressTest
```

| 参数 | 默认值 | 说明 |
|------|--------|------|
| 并发线程数 | 20 | 同时竞争锁的客户端数 |
| 每线程操作数 | 100 | 每个客户端获取/释放锁的次数 |
| 锁超时 | 5s | 单次获取锁的最大等待时间 |

**验证点：**
- 任意时刻最多 1 个线程持锁（互斥性）
- 所有线程最终都能获取到锁（无饥饿）
- 释放顺序符合 FIFO（公平性）
- 无死锁、无内存泄漏

---

## 内存模型测试

### ObjectPoolTest（14 个用例）

| 测试 | 验证点 |
|------|--------|
| `borrow_returnsNonNull` | borrow 永不返回 null |
| `release_callsReset` | release 触发 reset() |
| `borrowAfterRelease_reusesSameObject` | 同线程复用同一对象 |
| `borrowedItem_hasResetState` | 复用对象状态已清零 |
| `localPoolOverflow_doesNotLoseObjects` | 本地池溢出不丢对象 |
| `localPoolOverflow_globalPoolReceivesItems` | 溢出后批量归还全局池 |
| `globalPoolFull_releaseDoesNotThrow` | 全局池满时静默丢弃 |
| `stats_borrowCountIncrementsCorrectly` | borrowCount 精确计数 |
| `stats_localHitRateIsPositiveAfterReuse` | 本地池命中率 > 0 |
| `stats_newAllocRateIsAccurate` | 容量为 0 的池 newAllocRate > 0 |
| `stats_hitRatesSumToAtMostOne` | 三种命中率之和 = 1.0 |
| `concurrentBorrowRelease_noExceptionAndCountsCorrect` | 8 线程并发，borrowCount 精确 |
| `concurrentBorrow_allItemsAreNonNull` | 4 线程并发，borrow 不返回 null |

### ZNodePathCacheTest（12 个用例）

| 测试 | 验证点 |
|------|--------|
| `get_samePathReturnsSameInstance` | 相同路径返回同一实例 |
| `get_differentPathsReturnDifferentInstances` | 不同路径不同实例 |
| `get_returnsCorrectPath` | 路径值正确 |
| `get_incrementsSize` | 新路径增加 size |
| `get_duplicateDoesNotIncrementSize` | 重复路径不增加 size |
| `evict_removesFromCache` | evict 后 size 减少 |
| `evict_afterEvictGetCreatesNewInstance` | evict 后重新创建 |
| `evict_nonExistentKeyDoesNotThrow` | evict 不存在的 key 不抛异常 |
| `getSeqPath_formatsCorrectly` | 序号格式 10 位补零 |
| `getSeqPath_largeSeqBeyondPrecomputed` | 超出预计算范围正确格式化 |
| `formatSeq_paddingCorrect` | 静态方法边界值 |
| `get_beyondMaxSize_doesNotCache` | 超出容量上限降级不抛异常 |

---

## WAL 持久化测试

### RaftLogWalTest（20 个用例）

| 测试 | 验证点 |
|------|--------|
| `memoryMode_appendAndGet` | 内存模式基本操作 |
| `memoryMode_truncateFrom` | 内存模式截断 |
| `memoryMode_getFrom` | 范围查询 |
| `memoryMode_termAt_outOfBounds_returnsZero` | 越界返回 0 |
| `memoryMode_firstIndexOfTerm` | 按 term 查找首条索引 |
| `wal_appendAndReload` | WAL 写入后重启恢复 |
| `wal_appendAfterReload_continuousIndex` | 重启后继续追加 |
| `wal_truncateAndReload` | truncate 后重启恢复 |
| `wal_truncateAndAppendNewEntries` | truncate 后追加新 term 条目 |
| `wal_commandWithTab_escapedAndRestored` | TAB 字符转义/反转义 |
| `wal_commandWithNewline_escapedAndRestored` | 换行符转义/反转义 |
| `wal_commandWithBackslash_escapedAndRestored` | 反斜杠转义/反转义 |
| `wal_commandWithAllSpecialChars` | 混合特殊字符 |
| `wal_corruptedLine_skippedGracefully` | 损坏行跳过，已有数据完整 |
| `wal_indexDiscontinuity_truncatesAtGap` | index 不连续时截断 |
| `wal_emptyFile_startsFromScratch` | 空目录从 index=0 开始 |
| `wal_singleEntry_reloadCorrect` | 单条日志重启恢复 |
| `wal_truncateAll_reloadEmpty` | 全部截断后重启为空 |
| `lifecycle_shutdownIdempotent` | 多次 shutdown 不抛异常 |
| `lifecycle_startDoesNotThrow` | start 不抛异常 |

---

## 动态成员变更测试

### ClusterMembershipTest（18 个用例）

| 测试 | 验证点 |
|------|--------|
| `normal_quorum_singleNode_selfAlwaysQuorum` | 单节点自身满足多数派 |
| `normal_quorum_threeNodes_majorityRequired` | 3 节点需要 2/3 |
| `normal_quorum_threeNodes_onlyLeader_notQuorum` | 只有 Leader 不满足 |
| `normal_quorum_fiveNodes_threeRequired` | 5 节点需要 3/5 |
| `joint_quorum_requiresBothMajorities` | JOINT 需要两个多数派 |
| `joint_quorum_oldMajorityOnly_notEnough` | 只满足 old 不够 |
| `joint_quorum_newMajorityOnly_notEnough` | 只满足 new 不够 |
| `normal_electionQuorum_threeNodes` | 选举多数派 |
| `joint_electionQuorum_requiresBothMajorities` | JOINT 选举双多数派 |
| `normal_allVoters_returnsOldMembers` | NORMAL allVoters |
| `joint_allVoters_returnsUnion` | JOINT allVoters = old ∪ new |
| `encodeDecodeJoint_roundTrip` | JOINT 命令编解码 |
| `encodeDecodeNormal_roundTrip` | NORMAL 命令编解码 |
| `isMembershipChange_regularCommand_returnsFalse` | 普通命令不是成员变更 |
| `decode_invalidCommand_throwsException` | 非法命令抛异常 |
| `applyTo_joint_returnsJointConfig` | applyTo JOINT |
| `applyTo_normal_returnsNormalConfig` | applyTo NORMAL |
| `twoPhaseChange_addMember_configTransition` | 添加成员两阶段状态转换 |
| `twoPhaseChange_removeMember_configTransition` | 移除成员两阶段状态转换 |
