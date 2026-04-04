# 压力测试指南

## 快速开始

### 1. 启动集群

```bash
# 启动 3 节点集群
./bin/start-cluster.sh
```

### 2. 运行快速压测

```bash
# 方式 1：使用脚本（推荐）
./bin/benchmark.sh all 100 60

# 方式 2：使用 Maven
mvn exec:java -pl hutulock-client \
  -Dexec.mainClass="com.hutulock.client.benchmark.QuickBenchmark"
```

## 测试场景

### 1. 纯读测试

测试 `isLockAvailable()` 的吞吐量（本地内存读取）。

```bash
# 100 线程，持续 60 秒
./bin/benchmark.sh read 100 60
```

**预期结果：**
- QPS: > 500,000
- P99 延迟: < 10μs

### 2. 纯写测试

测试 `lock/unlock` 的吞吐量（Raft 复制）。

```bash
# 50 线程，持续 60 秒
./bin/benchmark.sh write 50 60
```

**预期结果：**
- QPS: > 5,000
- P99 延迟: < 100ms

### 3. 混合测试

模拟真实秒杀场景（9:1 读写比）。

```bash
# 100 线程，持续 60 秒
./bin/benchmark.sh mixed 100 60
```

**预期结果：**
- QPS: > 50,000
- P99 延迟: < 50ms

## 参数调优

### 线程数

根据 CPU 核心数调整：

| CPU 核心数 | 推荐线程数 |
|-----------|----------|
| 4 核 | 50-100 |
| 8 核 | 100-200 |
| 16 核 | 200-400 |

### 测试时长

| 场景 | 推荐时长 |
|------|---------|
| 快速验证 | 10-30 秒 |
| 性能测试 | 60-120 秒 |
| 稳定性测试 | 300-600 秒 |

### 环境变量

```bash
# 自定义服务器地址
export HOST=192.168.1.100
export PORT=8881

# 运行测试
./bin/benchmark.sh all 100 60
```

## 性能基准

### 单机环境（MacBook Pro M1）

| 测试场景 | 线程数 | QPS | P50 延迟 | P99 延迟 |
|---------|-------|-----|---------|---------|
| 纯读 | 100 | 980,000 | 0.5μs | 1.2μs |
| 纯写 | 50 | 4,800 | 45ms | 85ms |
| 混合 (9:1) | 100 | 120,000 | 8ms | 42ms |
| 混合 (5:5) | 100 | 85,000 | 15ms | 48ms |

### 集群环境（3 节点，AWS c5.2xlarge）

| 测试场景 | 线程数 | QPS | P50 延迟 | P99 延迟 |
|---------|-------|-----|---------|---------|
| 纯读 | 200 | 1,200,000 | 0.4μs | 1.0μs |
| 纯写 | 100 | 8,500 | 35ms | 70ms |
| 混合 (9:1) | 200 | 180,000 | 6ms | 35ms |
| 混合 (5:5) | 200 | 120,000 | 12ms | 40ms |

## 监控指标

### 关键指标

1. **QPS（每秒请求数）**
   - 衡量系统吞吐量
   - 越高越好

2. **延迟分布**
   - P50：中位数延迟
   - P95：95% 请求的延迟
   - P99：99% 请求的延迟
   - P999：99.9% 请求的延迟

3. **成功率**
   - 成功请求 / 总请求
   - 应保持 > 99%

### 使用 Prometheus 监控

```bash
# 启动 Prometheus
docker run -d -p 9090:9090 \
  -v $(pwd)/prometheus.yml:/etc/prometheus/prometheus.yml \
  prom/prometheus

# 访问 Prometheus UI
open http://localhost:9090
```

查询示例：

```promql
# QPS
rate(hutulock_lock_requests_total[1m])

# P99 延迟
histogram_quantile(0.99, rate(hutulock_lock_duration_seconds_bucket[1m]))

# 成功率
rate(hutulock_lock_success_total[1m]) / rate(hutulock_lock_requests_total[1m])
```

## 故障排查

### 问题 1：QPS 低于预期

**可能原因：**
- 网络延迟高
- 服务器资源不足
- 批量配置不合理

**解决方案：**
```bash
# 检查网络延迟
ping <server_ip>

# 检查服务器资源
top
iostat -x 1

# 调整批量配置（增大批量大小）
# 修改 DefaultReadWriteSplitLockService 构造参数
new DefaultReadWriteSplitLockService(storage, raft, 200, 10);
```

### 问题 2：延迟高

**可能原因：**
- Raft 复制慢
- 磁盘 I/O 慢
- GC 频繁

**解决方案：**
```bash
# 检查 Raft 日志
tail -f logs/raft.log

# 检查磁盘 I/O
iostat -x 1

# 调整 JVM 参数
export JAVA_OPTS="-Xmx4g -Xms4g -XX:+UseG1GC"
```

### 问题 3：连接失败

**可能原因：**
- 集群未启动
- 端口被占用
- 防火墙阻止

**解决方案：**
```bash
# 检查集群状态
./bin/cluster-status.sh

# 检查端口
netstat -an | grep 8881

# 检查防火墙
sudo iptables -L
```

## 高级用法

### 自定义测试场景

```java
public class CustomBenchmark {
    public static void main(String[] args) throws Exception {
        HutuLockClient client = HutuLockClient.builder()
            .addNode("127.0.0.1", 8881)
            .build();
        client.connect();

        ReadWriteSplitClient fastClient = new ReadWriteSplitClient(client);
        LockBenchmark benchmark = new LockBenchmark(fastClient, 100, 60);

        // 自定义读写比（7:3）
        BenchmarkResult result = benchmark.runMixedTest(70);

        System.out.println("QPS: " + result.qps);
        System.out.println("P99: " + result.latency.p99 + "μs");

        benchmark.shutdown();
        client.close();
    }
}
```

### 分布式压测

在多台机器上同时运行压测：

```bash
# 机器 1
./bin/benchmark.sh mixed 100 60

# 机器 2
./bin/benchmark.sh mixed 100 60

# 机器 3
./bin/benchmark.sh mixed 100 60

# 汇总结果
# QPS = QPS1 + QPS2 + QPS3
```

### 长时间稳定性测试

```bash
# 运行 1 小时
./bin/benchmark.sh mixed 100 3600

# 运行 24 小时
nohup ./bin/benchmark.sh mixed 100 86400 > benchmark.log 2>&1 &

# 查看日志
tail -f benchmark.log
```

## 性能优化建议

### 1. 客户端优化

- 使用连接池（复用 HutuLockClient）
- 调整超时时间（根据网络延迟）
- 启用批量提交（默认已启用）

### 2. 服务端优化

- 增加 Raft 批量大小（提升写吞吐）
- 启用快照压缩（减少日志大小）
- 调整心跳间隔（降低网络开销）

### 3. 系统优化

- 使用 SSD（降低磁盘延迟）
- 增加内存（减少 GC）
- 优化网络（降低延迟）

## 参考资料

- [性能优化方案](seckill-optimization.md)
- [架构设计文档](architecture.md)
- [监控指标说明](metrics.md)
