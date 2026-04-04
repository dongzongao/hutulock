# 秒杀场景快速开始

## 5 分钟快速上手

### 1. 启动集群

```bash
# 启动 3 节点集群
./bin/start-cluster.sh
```

### 2. 客户端代码

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
                  }
              });
}
```

### 3. 运行示例

```bash
# 运行秒杀示例（1000 用户抢购 10 件商品）
mvn exec:java -pl hutulock-client \
  -Dexec.mainClass="com.hutulock.client.example.SeckillExample"
```

## 性能对比

| 场景 | 标准实现 | 读写分离实现 | 提升 |
|------|---------|------------|------|
| 1000 用户抢购 10 件商品 | 5-10 秒 | < 1 秒 | 10x |
| QPS | 5000 | 100000+ | 20x |
| P99 延迟（读） | 50ms | < 1ms | 50x |

## 核心优势

1. **极致性能**：读 QPS 100 万+，写 QPS 10 万+
2. **低延迟**：读操作 < 1ms，写操作 < 50ms
3. **零侵入**：无需修改现有代码，只需包装客户端
4. **自动批量**：写操作自动批量提交，减少 RPC 次数

## 适用场景

✅ 秒杀抢购  
✅ 限流控制  
✅ 热点数据保护  
❌ 金融交易（需要强一致性）  
❌ 库存扣减（需要精确计数）

## 详细文档

- [完整优化方案](seckill-optimization.md)
- [API 参考](api-reference.md)
- [性能测试报告](../RELEASE_NOTES_v1.1.0.md)
