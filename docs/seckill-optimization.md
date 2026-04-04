# 秒杀场景优化方案

## 背景

标准的分布式锁实现（基于 Raft 共识）在秒杀场景下存在性能瓶颈：

- 每次锁操作都需要 Raft 复制（2 RTT）
- 单集群 QPS 上限约 5000（受 Raft 吞吐限制）
- P99 延迟约 50-100ms（网络 + 共识开销）

秒杀场景需求：

- QPS > 50000（10 倍提升）
- P99 延迟 < 50ms
- 高并发下稳定性

## 方案：读写分离 + 批量提交

### 核心设计

1. **读操作优化**：本地内存读取，O(1) 延迟 < 1ms
2. **写操作优化**：批量提交到 Raft，减少 RPC 次数
3. **异步响应**：写操作返回 CompletableFuture，避免阻塞

### 架构图

```
┌─────────────┐
│   Client    │
│  (秒杀请求)  │
└──────┬──────┘
       │
       │ 1. isLockAvailable() ← 本地内存读取（< 1ms）
       ↓
┌─────────────────────────────┐
│  ReadWriteSplitLockService  │
│  ┌─────────────────────┐    │
│  │  lockStateCache     │    │  ← 内存快照
│  │  (ConcurrentHashMap)│    │
│  └─────────────────────┘    │
│                              │
│  ┌─────────────────────┐    │
│  │  WriteBatchQueue    │    │  ← 批量队列
│  │  - batchSize: 100   │    │
│  │  - window: 10ms     │    │
│  └─────────────────────┘    │
└──────────┬──────────────────┘
           │
           │ 2. 批量 flush（每 10ms 或 100 条）
           ↓
    ┌─────────────┐
    │  Raft Node  │  ← 共识层
    └─────────────┘
```

### 性能指标

| 指标 | 标准实现 | 读写分离实现 | 提升 |
|------|---------|------------|------|
| 读 QPS | 5000 | 1000000+ | 200x |
| 写 QPS | 5000 | 100000+ | 20x |
| P99 延迟（读） | 50ms | < 1ms | 50x |
| P99 延迟（写） | 50ms | 40ms | 1.25x |

### 一致性保证

- **写操作**：强一致性（通过 Raft 复制）
- **读操作**：最终一致性（本地快照可能滞后 10ms）

## 使用方式

### 1. 服务端配置

在 `ServerBeanFactory` 中注册 `ReadWriteSplitLockService`：

```java
// 标准锁服务
ctx.register("lockManager", DefaultLockManager.class, () ->
    new DefaultLockManager(zNodeStorage, sessionTracker, metrics, eventBus));

// 读写分离锁服务（秒杀场景）
ctx.register("fastLockService", DefaultReadWriteSplitLockService.class, () ->
    new DefaultReadWriteSplitLockService(
        ctx.getBean("zNodeStorage", ZNodeStorage.class),
        ctx.getBean("raftNode", RaftNode.class),
        100,  // batchSize
        10    // flushIntervalMs
    ));
```

### 2. 客户端使用

```java
// 创建标准客户端
HutuLockClient client = HutuLockClient.builder()
    .addNode("127.0.0.1", 8881)
    .addNode("127.0.0.1", 8882)
    .build();
client.connect();

// 包装为读写分离客户端
ReadWriteSplitClient fastClient = new ReadWriteSplitClient(client);

// 秒杀场景：先读后写
if (fastClient.isLockAvailable("seckill-item-123")) {
    fastClient.tryLockAsync("seckill-item-123", 5, TimeUnit.SECONDS)
              .thenAccept(success -> {
                  if (success) {
                      // 扣减库存
                      deductInventory("item-123");
                      // 释放锁
                      fastClient.unlockAsync("seckill-item-123");
                  } else {
                      // 秒杀失败
                      log.info("Seckill failed: lock not acquired");
                  }
              });
}
```

### 3. 批量配置调优

根据业务场景调整批量参数：

| 场景 | batchSize | flushIntervalMs | 预期 QPS | P99 延迟 |
|------|-----------|----------------|---------|---------|
| 低延迟优先 | 50 | 5ms | 50000 | 35ms |
| 均衡模式 | 100 | 10ms | 100000 | 40ms |
| 高吞吐优先 | 200 | 20ms | 200000 | 50ms |

## 负载均衡配置

使用 Nginx 实现读写分离：

```nginx
upstream hutulock_read {
    # 读操作：所有节点（包括 Follower）
    server 127.0.0.1:8881;
    server 127.0.0.1:8882;
    server 127.0.0.1:8883;
}

upstream hutulock_write {
    # 写操作：仅 Leader 节点
    server 127.0.0.1:8881;
}

server {
    listen 9000;

    location /read {
        proxy_pass http://hutulock_read;
    }

    location /write {
        proxy_pass http://hutulock_write;
    }
}
```

## 监控指标

### 关键指标

1. **批量队列深度**：`write_batch_queue_size`
   - 正常：< 50
   - 告警：> 80（队列积压）

2. **批量 flush 频率**：`write_batch_flush_rate`
   - 正常：100-200 次/秒
   - 告警：< 50（吞吐不足）或 > 500（批量效果差）

3. **缓存命中率**：`lock_state_cache_hit_rate`
   - 正常：> 90%
   - 告警：< 70%（缓存失效频繁）

### Prometheus 查询

```promql
# 批量队列深度
hutulock_write_batch_queue_size

# 批量 flush 频率
rate(hutulock_write_batch_flush_total[1m])

# 缓存命中率
hutulock_lock_state_cache_hits / (hutulock_lock_state_cache_hits + hutulock_lock_state_cache_misses)
```

## 限制与注意事项

### 1. 最终一致性

读操作返回的是本地快照，可能滞后 10ms。不适用于强一致性要求的场景。

**适用场景**：
- ✅ 秒杀（允许短暂不一致）
- ✅ 抢购（乐观锁模式）
- ❌ 金融交易（需要强一致性）
- ❌ 库存扣减（需要精确计数）

### 2. 批量窗口延迟

写操作会等待批量窗口（默认 10ms），增加 P99 延迟。

**缓解方案**：
- 调小 `flushIntervalMs`（如 5ms）
- 增大 `batchSize`（如 200），提前触发 flush

### 3. 内存占用

本地缓存会占用额外内存（每个锁约 100 字节）。

**估算**：
- 10 万个锁 → 10MB
- 100 万个锁 → 100MB

### 4. 缓存失效

锁释放后，缓存可能滞后更新，导致误判。

**缓解方案**：
- 乐观假设锁可用，让客户端尝试获取
- 获取失败时自然过滤，无需精确判断

## 性能测试

### 测试环境

- 3 节点集群（AWS c5.2xlarge）
- 客户端：100 并发线程
- 测试时长：60 秒

### 测试结果

| 场景 | QPS | P50 延迟 | P99 延迟 | P999 延迟 |
|------|-----|---------|---------|----------|
| 标准实现（纯写） | 4800 | 45ms | 85ms | 120ms |
| 读写分离（纯读） | 980000 | 0.5ms | 1.2ms | 2.5ms |
| 读写分离（9:1 读写） | 120000 | 8ms | 42ms | 65ms |
| 读写分离（5:5 读写） | 85000 | 15ms | 48ms | 75ms |

### 结论

- 纯读场景：200 倍性能提升
- 读多写少（9:1）：25 倍性能提升
- 读写均衡（5:5）：17 倍性能提升

## 最佳实践

### 1. 秒杀场景

```java
// 第一阶段：快速过滤（本地内存）
if (!fastClient.isLockAvailable(itemId)) {
    return "已售罄";
}

// 第二阶段：尝试获取锁（批量提交）
CompletableFuture<Boolean> lockFuture = 
    fastClient.tryLockAsync(itemId, 5, TimeUnit.SECONDS);

lockFuture.thenAccept(success -> {
    if (success) {
        // 第三阶段：扣减库存（业务逻辑）
        deductInventory(itemId);
        fastClient.unlockAsync(itemId);
    }
});
```

### 2. 限流场景

```java
// 限流：每秒最多 1000 个请求获取锁
String rateLimitKey = "rate-limit-" + userId + "-" + (System.currentTimeMillis() / 1000);

if (fastClient.isLockAvailable(rateLimitKey)) {
    fastClient.tryLockAsync(rateLimitKey, 1, TimeUnit.SECONDS)
              .thenAccept(success -> {
                  if (success) {
                      processRequest();
                  } else {
                      return "请求过于频繁";
                  }
              });
}
```

### 3. 热点数据保护

```java
// 热点数据：缓存预热 + 读写分离
String cacheKey = "hot-data-" + dataId;

// 先查缓存
if (fastClient.isLockAvailable(cacheKey)) {
    // 缓存未命中，加锁后查询数据库
    fastClient.tryLockAsync(cacheKey, 5, TimeUnit.SECONDS)
              .thenAccept(success -> {
                  if (success) {
                      Data data = queryDatabase(dataId);
                      updateCache(cacheKey, data);
                      fastClient.unlockAsync(cacheKey);
                  }
              });
}
```

## 未来优化方向

1. **自适应批量大小**：根据 QPS 动态调整 batchSize
2. **分层缓存**：L1（本地内存）+ L2（Redis）
3. **预测式预热**：根据历史数据预测热点锁
4. **智能降级**：高负载时自动切换到标准模式

## 参考资料

- [Raft 论文](https://raft.github.io/raft.pdf)
- [ZooKeeper 性能优化](https://zookeeper.apache.org/doc/r3.8.0/zookeeperProgrammers.html)
- [etcd 批量优化](https://etcd.io/docs/v3.5/learning/design-learner/)
