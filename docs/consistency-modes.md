# 一致性模式

HutuLock 提供两种一致性模式，适用于不同的业务场景。

## 模式对比

| 特性 | 最终一致性模式 | 强一致性模式 |
|------|--------------|------------|
| **读操作** | 本地缓存（乐观假设） | Raft 共识读取 |
| **写操作** | Raft 共识 | Raft 共识 |
| **读延迟** | <1ms | ~50ms |
| **读 QPS** | 21M+ | 取决于集群 |
| **一致性保证** | 最终一致（可能滞后 10ms） | 强一致（实时） |
| **适用场景** | 秒杀、抢购、高并发 | 金融、支付、交易 |

## 最终一致性模式（默认）

### 特点

- **极致性能**：读操作 21M+ QPS，<1ms 延迟
- **乐观假设**：假设锁可用，让客户端直接尝试获取
- **自然过滤**：获取失败时自然过滤，无需精确判断
- **最终一致**：读操作可能滞后 10ms，但最终会一致

### 适用场景

✅ 秒杀/抢购
- 允许短暂的库存不一致
- 超卖可以通过后续校验处理
- 性能优先

✅ 限流/熔断
- 允许短暂的计数不一致
- 最终会收敛到正确值

✅ 缓存更新
- 允许短暂的缓存不一致
- 最终会刷新到最新值

### 使用示例

```java
// 创建客户端（默认最终一致性）
ReadWriteSplitClient client = new ReadWriteSplitClient(hutuLockClient);

// 秒杀场景
if (client.isLockAvailable("seckill-item-123")) {  // <1ms，本地判断
    client.tryLockAsync("seckill-item-123", 5, TimeUnit.SECONDS)
          .thenAccept(success -> {
              if (success) {
                  deductInventory("item-123");
                  client.unlockAsync("seckill-item-123");
              }
          });
}
```

### 性能数据

| 指标 | 数值 |
|------|------|
| 读 QPS | 21,760,065 |
| 混合 QPS (9:1) | 3,173,992 |
| P99 延迟（读） | <1ms |
| P99 延迟（写） | ~50ms |

## 强一致性模式

### 特点

- **强一致性**：所有操作都通过 Raft 共识
- **实时准确**：读操作返回最新状态
- **无数据丢失**：即使网络分区也能保证一致性
- **性能折衷**：读操作延迟 ~50ms

### 适用场景

✅ 金融交易
- 账户转账
- 支付处理
- 余额查询

✅ 订单处理
- 订单状态更新
- 库存扣减（严格模式）
- 支付确认

✅ 关键业务
- 配置更新
- 权限变更
- 审计日志

### 使用示例

```java
// 创建强一致性客户端
ReadWriteSplitClient client = new ReadWriteSplitClient(hutuLockClient, true);

// 金融转账场景
String transferLock = "transfer-" + fromAccount + "-" + toAccount;

if (client.isLockAvailable(transferLock)) {  // ~50ms，Raft 读取
    client.tryLockAsync(transferLock, 10, TimeUnit.SECONDS)
          .thenAccept(success -> {
              if (success) {
                  try {
                      // 执行转账（临界区）
                      checkBalance(fromAccount);
                      deductBalance(fromAccount, amount);
                      addBalance(toAccount, amount);
                      logTransaction(fromAccount, toAccount, amount);
                  } finally {
                      client.unlockAsync(transferLock);
                  }
              }
          });
}
```

### 性能数据

| 指标 | 数值 |
|------|------|
| 读 QPS | 取决于集群（通常 1K-10K） |
| 写 QPS | 100,000+ |
| P99 延迟（读） | ~50ms |
| P99 延迟（写） | ~50ms |

## 如何选择

### 选择最终一致性模式

如果你的场景满足以下条件：
- ✅ 允许短暂的数据不一致（10ms 内）
- ✅ 性能是首要考虑因素
- ✅ 可以通过后续校验处理不一致
- ✅ 读操作远多于写操作（9:1 或更高）

典型场景：秒杀、抢购、限流、缓存

### 选择强一致性模式

如果你的场景满足以下条件：
- ✅ 不允许任何数据不一致
- ✅ 准确性优先于性能
- ✅ 涉及金钱、账户、订单等关键数据
- ✅ 需要审计和合规

典型场景：金融交易、支付、订单、权限

## 混合使用

可以在同一个应用中混合使用两种模式：

```java
// 秒杀场景：最终一致性
ReadWriteSplitClient seckillClient = new ReadWriteSplitClient(hutuLockClient, false);

// 支付场景：强一致性
ReadWriteSplitClient paymentClient = new ReadWriteSplitClient(hutuLockClient, true);

// 秒杀商品
if (seckillClient.isLockAvailable("item-123")) {
    seckillClient.tryLockAsync("item-123", 5, TimeUnit.SECONDS)
                 .thenAccept(success -> {
                     if (success) {
                         // 创建订单（强一致性）
                         String orderId = createOrder();
                         
                         // 处理支付（强一致性）
                         if (paymentClient.isLockAvailable("payment-" + orderId)) {
                             paymentClient.tryLockAsync("payment-" + orderId, 10, TimeUnit.SECONDS)
                                          .thenAccept(paymentSuccess -> {
                                              if (paymentSuccess) {
                                                  processPayment(orderId);
                                                  paymentClient.unlockAsync("payment-" + orderId);
                                              }
                                          });
                         }
                         
                         seckillClient.unlockAsync("item-123");
                     }
                 });
}
```

## 实现原理

### 最终一致性模式

```
客户端                    服务端
   │                        │
   │  isLockAvailable()     │
   │  (本地判断，<1ms)       │
   │                        │
   │  tryLock()             │
   ├───────────────────────>│
   │                        │ Raft 共识
   │                        │ (批量提交)
   │<───────────────────────┤
   │  success/fail          │
```

### 强一致性模式

```
客户端                    服务端
   │                        │
   │  isLockAvailable()     │
   ├───────────────────────>│
   │                        │ Raft 读取
   │<───────────────────────┤
   │  available/unavailable │
   │                        │
   │  tryLock()             │
   ├───────────────────────>│
   │                        │ Raft 共识
   │<───────────────────────┤
   │  success/fail          │
```

## 最佳实践

### 1. 根据场景选择模式

不要盲目追求强一致性，根据业务需求选择合适的模式。

### 2. 监控一致性延迟

在最终一致性模式下，监控实际的一致性延迟：

```java
long start = System.currentTimeMillis();
boolean available = client.isLockAvailable(lockName);
boolean locked = client.tryLockAsync(lockName, 5, TimeUnit.SECONDS).get();
long delay = System.currentTimeMillis() - start;

if (available && !locked) {
    // 记录不一致情况
    log.warn("Consistency delay detected: {}ms", delay);
}
```

### 3. 设置合理的超时

- 最终一致性模式：5-10 秒（允许重试）
- 强一致性模式：10-30 秒（Raft 共识需要时间）

### 4. 处理失败情况

```java
client.tryLockAsync(lockName, timeout, unit)
      .thenAccept(success -> {
          if (success) {
              // 成功获取锁
          } else {
              // 失败处理：重试、降级、通知用户
          }
      })
      .exceptionally(ex -> {
          // 异常处理：记录日志、告警
          log.error("Lock operation failed", ex);
          return null;
      });
```

## 常见问题

### Q: 最终一致性模式会导致超卖吗？

A: 可能会有短暂的超卖（10ms 内），但可以通过以下方式处理：
- 预留库存缓冲
- 后续校验和补偿
- 异步对账

### Q: 强一致性模式的性能如何？

A: 读操作 ~50ms 延迟，写操作 100K+ QPS。对于金融场景来说，这个性能是可接受的。

### Q: 可以动态切换模式吗？

A: 不建议。一致性模式应该在创建客户端时确定，并在整个生命周期保持不变。

### Q: 如何验证一致性？

A: 可以通过以下方式验证：
- 单元测试：模拟并发场景
- 压力测试：高并发下的一致性检查
- 监控告警：实时监控不一致情况

## 参考资料

- [秒杀场景优化](seckill-optimization.md)
- [性能测试指南](benchmark-guide.md)
- [API 文档](api-reference.md)
