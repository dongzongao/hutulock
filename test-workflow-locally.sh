#!/bin/bash
set -e

echo "=========================================="
echo "本地测试 GitHub Actions 工作流逻辑"
echo "=========================================="

# 1. 编译项目
echo ""
echo "步骤 1/5: 编译项目..."
mvn -B clean install -DskipTests -q
echo "✅ 编译完成"

# 2. 启动 3 节点集群
echo ""
echo "步骤 2/5: 启动 3 节点集群..."
nohup java -jar hutulock-server/target/hutulock-server-*.jar node1 8881 9881 > node1.log 2>&1 &
echo $! > node1.pid
echo "  - Node1 启动 (PID: $(cat node1.pid))"

nohup java -jar hutulock-server/target/hutulock-server-*.jar node2 8882 9882 > node2.log 2>&1 &
echo $! > node2.pid
echo "  - Node2 启动 (PID: $(cat node2.pid))"

nohup java -jar hutulock-server/target/hutulock-server-*.jar node3 8883 9883 > node3.log 2>&1 &
echo $! > node3.pid
echo "  - Node3 启动 (PID: $(cat node3.pid))"

echo "  等待集群就绪..."
sleep 30

# 检查进程
if ps -p $(cat node1.pid) > /dev/null && \
   ps -p $(cat node2.pid) > /dev/null && \
   ps -p $(cat node3.pid) > /dev/null; then
    echo "✅ 集群启动成功"
else
    echo "❌ 集群启动失败，查看日志："
    echo "  tail -20 node1.log"
    echo "  tail -20 node2.log"
    echo "  tail -20 node3.log"
    exit 1
fi

# 3. 创建并运行测试
echo ""
echo "步骤 3/5: 运行性能测试..."

cat > BenchmarkRunner.java << 'EOF'
import com.hutulock.client.HutuLockClient;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.io.*;
import java.time.*;
import java.util.*;

public class BenchmarkRunner {
    public static void main(String[] args) throws Exception {
        System.out.println("连接到集群...");
        HutuLockClient client = HutuLockClient.builder()
            .addNode("127.0.0.1", 8881)
            .addNode("127.0.0.1", 8882)
            .addNode("127.0.0.1", 8883)
            .build();
        client.connect();
        System.out.println("✅ 已连接");
        
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("timestamp", Instant.now().toString());
        results.put("commit", "local-test");
        
        // 简化测试：每个只运行 10 秒
        System.out.println("\n运行读测试 (10秒)...");
        Map<String, Object> readTest = runTest(client, 50, 10, "read");
        results.put("read_test", readTest);
        System.out.println("  QPS: " + readTest.get("qps"));
        
        System.out.println("\n运行写测试 (10秒)...");
        Map<String, Object> writeTest = runTest(client, 25, 10, "write");
        results.put("write_test", writeTest);
        System.out.println("  QPS: " + writeTest.get("qps"));
        
        System.out.println("\n运行混合测试 (10秒)...");
        Map<String, Object> mixedTest = runTest(client, 50, 10, "mixed");
        results.put("mixed_test", mixedTest);
        System.out.println("  QPS: " + mixedTest.get("qps"));
        
        client.close();
        
        writeJson(results, "benchmark-results.json");
        System.out.println("\n✅ 测试完成，结果已保存到 benchmark-results.json");
    }
    
    static Map<String, Object> runTest(HutuLockClient client, int threads, int seconds, String type) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failCount = new AtomicLong(0);
        List<Long> latencies = new CopyOnWriteArrayList<>();
        
        long startTime = System.currentTimeMillis();
        long endTime = startTime + seconds * 1000L;
        
        for (int i = 0; i < threads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                while (System.currentTimeMillis() < endTime) {
                    String lockName = "bench-lock-" + (threadId % 10);
                    long start = System.nanoTime();
                    try {
                        if ("read".equals(type)) {
                            client.exists("/" + lockName);
                        } else if ("write".equals(type)) {
                            if (client.tryLock(lockName, 5, TimeUnit.SECONDS)) {
                                client.unlock(lockName);
                            }
                        } else {
                            if (Math.random() < 0.9) {
                                client.exists("/" + lockName);
                            } else {
                                if (client.tryLock(lockName, 5, TimeUnit.SECONDS)) {
                                    client.unlock(lockName);
                                }
                            }
                        }
                        long latency = (System.nanoTime() - start) / 1000;
                        latencies.add(latency);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                    }
                }
            });
        }
        
        executor.shutdown();
        executor.awaitTermination(seconds + 10, TimeUnit.SECONDS);
        
        long duration = System.currentTimeMillis() - startTime;
        long total = successCount.get();
        double qps = total * 1000.0 / duration;
        
        Collections.sort(latencies);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("threads", threads);
        result.put("duration_ms", duration);
        result.put("total_ops", total);
        result.put("failed_ops", failCount.get());
        result.put("qps", Math.round(qps));
        result.put("p50_latency_us", getPercentile(latencies, 0.50));
        result.put("p95_latency_us", getPercentile(latencies, 0.95));
        result.put("p99_latency_us", getPercentile(latencies, 0.99));
        result.put("p999_latency_us", getPercentile(latencies, 0.999));
        
        return result;
    }
    
    static long getPercentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
    
    static void writeJson(Map<String, Object> data, String filename) throws Exception {
        StringBuilder json = new StringBuilder("{\n");
        boolean first = true;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (!first) json.append(",\n");
            first = false;
            json.append("  \"").append(entry.getKey()).append("\": ");
            if (entry.getValue() instanceof Map) {
                json.append(mapToJson((Map<String, Object>) entry.getValue(), 2));
            } else if (entry.getValue() instanceof String) {
                json.append("\"").append(entry.getValue()).append("\"");
            } else {
                json.append(entry.getValue());
            }
        }
        json.append("\n}");
        
        try (FileWriter writer = new FileWriter(filename)) {
            writer.write(json.toString());
        }
    }
    
    static String mapToJson(Map<String, Object> map, int indent) {
        StringBuilder json = new StringBuilder("{\n");
        String indentStr = "  ".repeat(indent);
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) json.append(",\n");
            first = false;
            json.append(indentStr).append("  \"").append(entry.getKey()).append("\": ");
            if (entry.getValue() instanceof String) {
                json.append("\"").append(entry.getValue()).append("\"");
            } else {
                json.append(entry.getValue());
            }
        }
        json.append("\n").append(indentStr).append("}");
        return json.toString();
    }
}
EOF

# 编译并运行
javac -cp "hutulock-client/target/*:hutulock-model/target/*" BenchmarkRunner.java
java -cp ".:hutulock-client/target/*:hutulock-model/target/*:hutulock-server/target/lib/*" BenchmarkRunner

echo "✅ 性能测试完成"

# 4. 生成图表
echo ""
echo "步骤 4/5: 生成性能图表..."

python3 << 'PYEOF'
import json
import matplotlib.pyplot as plt
import matplotlib
matplotlib.use('Agg')
import os

with open('benchmark-results.json', 'r') as f:
    current = json.load(f)

history_file = 'docs/benchmark-history.json'
if os.path.exists(history_file):
    with open(history_file, 'r') as f:
        history = json.load(f)
else:
    history = []

history.append(current)
history = history[-50:]

with open(history_file, 'w') as f:
    json.dump(history, f, indent=2)

fig, axes = plt.subplots(2, 2, figsize=(14, 10))
fig.suptitle('HutuLock Performance Benchmark', fontsize=16, fontweight='bold')

timestamps = [h.get('timestamp', '')[:10] for h in history]
read_qps = [h.get('read_test', {}).get('qps', 0) for h in history]
write_qps = [h.get('write_test', {}).get('qps', 0) for h in history]
mixed_qps = [h.get('mixed_test', {}).get('qps', 0) for h in history]

read_p99 = [h.get('read_test', {}).get('p99_latency_us', 0) / 1000 for h in history]
write_p99 = [h.get('write_test', {}).get('p99_latency_us', 0) / 1000 for h in history]
mixed_p99 = [h.get('mixed_test', {}).get('p99_latency_us', 0) / 1000 for h in history]

ax1 = axes[0, 0]
ax1.plot(read_qps, 'o-', label='Read', linewidth=2, markersize=6)
ax1.plot(write_qps, 's-', label='Write', linewidth=2, markersize=6)
ax1.plot(mixed_qps, '^-', label='Mixed (9:1)', linewidth=2, markersize=6)
ax1.set_title('QPS Trends', fontweight='bold')
ax1.set_xlabel('Build Number')
ax1.set_ylabel('QPS')
ax1.legend()
ax1.grid(True, alpha=0.3)
ax1.ticklabel_format(style='plain', axis='y')

ax2 = axes[0, 1]
ax2.plot(read_p99, 'o-', label='Read', linewidth=2, markersize=6)
ax2.plot(write_p99, 's-', label='Write', linewidth=2, markersize=6)
ax2.plot(mixed_p99, '^-', label='Mixed (9:1)', linewidth=2, markersize=6)
ax2.set_title('P99 Latency Trends', fontweight='bold')
ax2.set_xlabel('Build Number')
ax2.set_ylabel('Latency (ms)')
ax2.legend()
ax2.grid(True, alpha=0.3)

ax3 = axes[1, 0]
categories = ['Read', 'Write', 'Mixed\n(9:1)']
latest_qps = [read_qps[-1], write_qps[-1], mixed_qps[-1]]
bars = ax3.bar(categories, latest_qps, color=['#2ecc71', '#e74c3c', '#3498db'])
ax3.set_title('Latest QPS Comparison', fontweight='bold')
ax3.set_ylabel('QPS')
ax3.ticklabel_format(style='plain', axis='y')
for bar in bars:
    height = bar.get_height()
    ax3.text(bar.get_x() + bar.get_width()/2., height,
             f'{int(height):,}', ha='center', va='bottom', fontweight='bold')

ax4 = axes[1, 1]
latest = history[-1]
read_latencies = [
    latest['read_test']['p50_latency_us'] / 1000,
    latest['read_test']['p95_latency_us'] / 1000,
    latest['read_test']['p99_latency_us'] / 1000,
    latest['read_test']['p999_latency_us'] / 1000
]
write_latencies = [
    latest['write_test']['p50_latency_us'] / 1000,
    latest['write_test']['p95_latency_us'] / 1000,
    latest['write_test']['p99_latency_us'] / 1000,
    latest['write_test']['p999_latency_us'] / 1000
]
x = range(4)
width = 0.35
ax4.bar([i - width/2 for i in x], read_latencies, width, label='Read', color='#2ecc71')
ax4.bar([i + width/2 for i in x], write_latencies, width, label='Write', color='#e74c3c')
ax4.set_title('Latest Latency Distribution', fontweight='bold')
ax4.set_ylabel('Latency (ms)')
ax4.set_xticks(x)
ax4.set_xticklabels(['P50', 'P95', 'P99', 'P999'])
ax4.legend()
ax4.grid(True, alpha=0.3, axis='y')

plt.tight_layout()
plt.savefig('docs/benchmark-chart.png', dpi=150, bbox_inches='tight')
print("✅ 图表已生成: docs/benchmark-chart.png")

print(f"\n最新结果:")
print(f"  读 QPS: {int(read_qps[-1]):,}")
print(f"  写 QPS: {int(write_qps[-1]):,}")
print(f"  混合 QPS: {int(mixed_qps[-1]):,}")
PYEOF

echo "✅ 图表生成完成"

# 5. 停止集群
echo ""
echo "步骤 5/5: 停止集群..."
kill $(cat node1.pid) 2>/dev/null || true
kill $(cat node2.pid) 2>/dev/null || true
kill $(cat node3.pid) 2>/dev/null || true
sleep 2
echo "✅ 集群已停止"

# 清理
rm -f node*.pid BenchmarkRunner.java BenchmarkRunner.class

echo ""
echo "=========================================="
echo "✅ 本地测试完成！"
echo "=========================================="
echo ""
echo "查看结果："
echo "  - JSON: cat benchmark-results.json | jq ."
echo "  - 图表: open docs/benchmark-chart.png"
echo "  - 历史: cat docs/benchmark-history.json | jq ."
echo ""
echo "如果一切正常，GitHub Actions 也应该能成功运行！"
echo ""
