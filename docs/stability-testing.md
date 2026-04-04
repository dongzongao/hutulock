# 稳定性测试指南

## 概述

稳定性测试用于验证 HutuLock 在长时间运行下的可靠性、资源使用和错误恢复能力。

## 测试内容

### 1. 长时间运行测试
- 持续运行 1-24 小时
- 验证无内存泄漏
- 验证无连接泄漏
- 验证性能不衰减

### 2. 资源使用监控
- 内存使用趋势
- CPU 使用率
- 网络连接数
- 文件句柄数

### 3. 错误恢复测试
- 网络中断恢复
- 节点故障恢复
- 会话过期处理
- 锁超时处理

### 4. 并发压力测试
- 多线程并发访问
- 锁竞争场景
- 高频操作场景

## 快速开始

### 基础测试（1小时）

```bash
# 1. 启动集群
./bin/cluster.sh

# 2. 运行稳定性测试
./bin/stability-test.sh -d 60
```

### 完整测试（24小时）

```bash
# 3节点集群，100线程，24小时
./bin/stability-test.sh \
    -s 127.0.0.1:8881,127.0.0.1:8882,127.0.0.1:8883 \
    -t 100 \
    -d 1440
```

## 测试参数

| 参数 | 说明 | 默认值 | 推荐值 |
|------|------|--------|--------|
| `-s, --servers` | 服务器地址 | 127.0.0.1:8881 | 3节点集群 |
| `-t, --threads` | 并发线程数 | 50 | 50-200 |
| `-d, --duration` | 持续时间(分钟) | 60 | 60-1440 |

## 测试场景

### 场景1: 基本锁操作

```java
// 获取锁 -> 业务处理 -> 释放锁
client.lock(lockName);
try {
    // 模拟业务处理 10-50ms
    Thread.sleep(random(10, 50));
} finally {
    client.unlock(lockName);
}
```

### 场景2: 带超时的锁

```java
// 带 TTL 和 watchdog 的锁
LockContext ctx = LockContext.builder(lockName, sessionId)
    .ttl(30, TimeUnit.SECONDS)
    .watchdogInterval(10, TimeUnit.SECONDS)
    .build();

if (client.lock(ctx, 5, TimeUnit.SECONDS)) {
    try {
        // 业务处理
    } finally {
        client.unlock(ctx);
    }
}
```

### 场景3: 乐观锁

```java
// 版本控制的乐观锁
client.optimisticUpdate(dataPath, 3, current -> {
    return updatedData;
});
```

## 监控指标

### 实时监控（每10秒）

```
--- 运行状态 ---
成功: 125,430
失败: 12
成功率: 99.99%
重连次数: 0
内存使用: 256MB / 2048MB

错误类型:
  TimeoutException: 8
  ConnectionException: 4
```

### 最终报告

```
=== 测试完成 ===
总操作数: 1,254,420
成功: 1,254,408
失败: 12
成功率: 99.999%
平均 QPS: 348.45
重连次数: 0

内存使用:
  初始: 128MB
  使用: 256MB
  最大: 2048MB

错误统计:
  TimeoutException: 8
  ConnectionException: 4

稳定性评估:
  ✅ 优秀 (成功率 >= 99.9%)
  ✅ 连接稳定 (无重连)
```

## 评估标准

### 成功率

| 成功率 | 评级 | 说明 |
|--------|------|------|
| >= 99.9% | ✅ 优秀 | 生产可用 |
| >= 99.0% | ✅ 良好 | 基本可用 |
| >= 95.0% | ⚠️ 一般 | 需要优化 |
| < 95.0% | ❌ 较差 | 不建议使用 |

### 连接稳定性

| 重连次数 | 评级 | 说明 |
|---------|------|------|
| 0 | ✅ 优秀 | 连接稳定 |
| 1-10 | ⚠️ 一般 | 偶尔中断 |
| > 10 | ❌ 较差 | 连接不稳定 |

### 内存使用

| 内存增长 | 评级 | 说明 |
|---------|------|------|
| < 10% | ✅ 优秀 | 无泄漏 |
| 10-50% | ⚠️ 注意 | 需要观察 |
| > 50% | ❌ 警告 | 可能泄漏 |

## 故障注入测试

### 1. 网络中断测试

```bash
# 测试期间手动断开网络
# 方法1: 停止节点
kill -STOP $(cat data/node1/server.pid)
sleep 30
kill -CONT $(cat data/node1/server.pid)

# 方法2: 防火墙规则（需要 root）
sudo iptables -A INPUT -p tcp --dport 8881 -j DROP
sleep 30
sudo iptables -D INPUT -p tcp --dport 8881 -j DROP
```

### 2. 节点故障测试

```bash
# 杀死一个节点
kill $(cat data/node2/server.pid)

# 等待30秒观察恢复

# 重启节点
./bin/server.sh node2 8882 9882 &
```

### 3. 内存压力测试

```bash
# 增加线程数和持续时间
./bin/stability-test.sh -t 200 -d 180
```

## 高级测试

### 1. 混沌工程测试

使用 Chaos Mesh 或类似工具：

```yaml
# chaos-network.yaml
apiVersion: chaos-mesh.org/v1alpha1
kind: NetworkChaos
metadata:
  name: network-delay
spec:
  action: delay
  mode: one
  selector:
    namespaces:
      - hutulock
  delay:
    latency: "100ms"
    jitter: "50ms"
  duration: "5m"
```

### 2. 性能衰减测试

```bash
# 运行24小时，每小时记录一次性能
for i in {1..24}; do
    echo "=== Hour $i ==="
    ./bin/stability-test.sh -d 60 | tee -a stability-24h.log
done
```

### 3. 内存泄漏检测

```bash
# 使用 JVM 参数
export MAVEN_OPTS="-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./heap-dump"

# 运行测试
./bin/stability-test.sh -d 360

# 分析堆转储
jmap -heap <pid>
jhat heap-dump.hprof
```

## 常见问题

### Q: 测试中出现大量 TimeoutException

A: 可能原因：
- 集群负载过高
- 网络延迟大
- 锁竞争激烈

解决方案：
- 减少线程数
- 增加超时时间
- 检查网络状况

### Q: 内存持续增长

A: 可能原因：
- 内存泄漏
- 缓存未清理
- 连接未关闭

解决方案：
- 使用 JProfiler 分析
- 检查资源释放
- 调整 GC 参数

### Q: 连接频繁中断

A: 可能原因：
- 网络不稳定
- 服务器重启
- 会话超时

解决方案：
- 检查网络质量
- 增加心跳频率
- 调整会话超时

## 最佳实践

### 1. 测试前准备

- ✅ 确保集群稳定运行
- ✅ 清理旧的测试数据
- ✅ 设置合理的 JVM 参数
- ✅ 准备监控工具

### 2. 测试期间

- ✅ 实时监控资源使用
- ✅ 记录异常日志
- ✅ 定期检查集群状态
- ✅ 保存测试数据

### 3. 测试后分析

- ✅ 分析成功率趋势
- ✅ 检查内存使用
- ✅ 统计错误类型
- ✅ 生成测试报告

## 自动化测试

### 定时任务

```bash
# crontab -e
# 每天凌晨2点运行1小时稳定性测试
0 2 * * * cd /path/to/hutulock && ./bin/stability-test.sh -d 60 >> stability-daily.log 2>&1
```

### CI/CD 集成

```yaml
# .github/workflows/stability-test.yml
name: Stability Test

on:
  schedule:
    - cron: '0 2 * * 0'  # 每周日凌晨2点

jobs:
  stability:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
      - name: Start cluster
        run: ./bin/cluster.sh
      - name: Run stability test
        run: ./bin/stability-test.sh -d 60
```

## 测试报告模板

```markdown
# 稳定性测试报告

## 测试环境
- 日期: 2026-04-04
- 集群: 3节点
- 线程数: 100
- 持续时间: 24小时

## 测试结果
- 总操作数: 30,125,420
- 成功率: 99.998%
- 平均 QPS: 348.45
- 重连次数: 2

## 资源使用
- 内存: 256MB -> 280MB (+9.4%)
- CPU: 平均 45%
- 网络: 稳定

## 问题记录
1. 第8小时出现1次网络中断，自动恢复
2. 第16小时出现1次会话超时，自动重连

## 结论
✅ 系统稳定性优秀，可用于生产环境
```

## 参考资料

- [性能测试指南](benchmark-guide.md)
- [监控指标说明](metrics.md)
- [故障排查](troubleshooting.md)
