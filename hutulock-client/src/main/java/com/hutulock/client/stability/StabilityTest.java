/*
 * Copyright 2026 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hutulock.client.stability;

import com.hutulock.client.HutuLockClient;
import com.hutulock.client.LockContext;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 稳定性测试工具
 *
 * <p>测试内容：
 * <ul>
 *   <li>长时间运行（1小时+）</li>
 *   <li>内存泄漏检测</li>
 *   <li>连接稳定性</li>
 *   <li>错误恢复能力</li>
 *   <li>资源使用监控</li>
 * </ul>
 *
 * @author HutuLock Authors
 * @since 1.1.0
 */
public class StabilityTest {

    private static final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

    public static void main(String[] args) throws Exception {
        // 解析参数
        String servers = args.length > 0 ? args[0] : "127.0.0.1:8881";
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : 50;
        int durationMinutes = args.length > 2 ? Integer.parseInt(args[2]) : 60;

        System.out.println("=== HutuLock 稳定性测试 ===");
        System.out.println("服务器: " + servers);
        System.out.println("线程数: " + threads);
        System.out.println("持续时间: " + durationMinutes + " 分钟");
        System.out.println();

        // 创建客户端
        HutuLockClient.Builder builder = HutuLockClient.builder();
        for (String server : servers.split(",")) {
            String[] parts = server.split(":");
            builder.addNode(parts[0], Integer.parseInt(parts[1]));
        }
        HutuLockClient client = builder.build();
        client.connect();

        // 统计数据
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failCount = new AtomicLong(0);
        AtomicLong reconnectCount = new AtomicLong(0);
        ConcurrentHashMap<String, AtomicLong> errorTypes = new ConcurrentHashMap<>();

        // 启动监控线程
        ScheduledExecutorService monitor = Executors.newScheduledThreadPool(1);
        monitor.scheduleAtFixedRate(() -> {
            printStatus(successCount, failCount, reconnectCount, errorTypes);
        }, 10, 10, TimeUnit.SECONDS);

        // 启动测试线程
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        long endTime = System.currentTimeMillis() + durationMinutes * 60 * 1000L;

        for (int i = 0; i < threads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                runTestLoop(client, threadId, endTime, successCount, failCount, 
                           reconnectCount, errorTypes);
            });
        }

        // 等待测试完成
        executor.shutdown();
        executor.awaitTermination(durationMinutes + 10, TimeUnit.MINUTES);
        monitor.shutdown();

        // 打印最终报告
        System.out.println("\n=== 测试完成 ===");
        printFinalReport(successCount, failCount, reconnectCount, errorTypes, durationMinutes);

        client.close();
    }

    private static void runTestLoop(HutuLockClient client, int threadId, long endTime,
                                    AtomicLong successCount, AtomicLong failCount,
                                    AtomicLong reconnectCount,
                                    ConcurrentHashMap<String, AtomicLong> errorTypes) {
        while (System.currentTimeMillis() < endTime) {
            String lockName = "stability-test-" + (threadId % 10);
            
            try {
                // 测试场景1: 基本锁操作
                if (client.lock(lockName)) {
                    try {
                        // 模拟业务处理
                        Thread.sleep(ThreadLocalRandom.current().nextInt(10, 50));
                    } finally {
                        client.unlock(lockName);
                    }
                    successCount.incrementAndGet();
                } else {
                    failCount.incrementAndGet();
                }

                // 测试场景2: 带超时的锁
                LockContext ctx = LockContext.builder(lockName + "-timeout", client.getSessionId())
                    .ttl(30, TimeUnit.SECONDS)
                    .watchdogInterval(10, TimeUnit.SECONDS)
                    .build();

                if (client.lock(ctx, 5, TimeUnit.SECONDS)) {
                    try {
                        Thread.sleep(ThreadLocalRandom.current().nextInt(10, 100));
                    } finally {
                        client.unlock(ctx);
                    }
                    successCount.incrementAndGet();
                } else {
                    failCount.incrementAndGet();
                }

                // 测试场景3: 乐观锁
                try {
                    String dataPath = "/stability/data-" + threadId;
                    client.optimisticUpdate(dataPath, 3, current -> {
                        return ("updated-" + System.currentTimeMillis()).getBytes();
                    });
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    recordError(errorTypes, e.getClass().getSimpleName());
                }

                // 短暂休息
                Thread.sleep(ThreadLocalRandom.current().nextInt(50, 200));

            } catch (Exception e) {
                failCount.incrementAndGet();
                recordError(errorTypes, e.getClass().getSimpleName());
                
                // 检测是否需要重连
                if (e.getMessage() != null && e.getMessage().contains("connection")) {
                    reconnectCount.incrementAndGet();
                }
            }
        }
    }

    private static void recordError(ConcurrentHashMap<String, AtomicLong> errorTypes, String errorType) {
        errorTypes.computeIfAbsent(errorType, k -> new AtomicLong(0)).incrementAndGet();
    }

    private static void printStatus(AtomicLong successCount, AtomicLong failCount,
                                    AtomicLong reconnectCount,
                                    ConcurrentHashMap<String, AtomicLong> errorTypes) {
        long total = successCount.get() + failCount.get();
        double successRate = total > 0 ? (successCount.get() * 100.0 / total) : 0;

        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        long usedMB = heapUsage.getUsed() / 1024 / 1024;
        long maxMB = heapUsage.getMax() / 1024 / 1024;

        System.out.println("\n--- 运行状态 ---");
        System.out.println("成功: " + successCount.get());
        System.out.println("失败: " + failCount.get());
        System.out.println("成功率: " + String.format("%.2f%%", successRate));
        System.out.println("重连次数: " + reconnectCount.get());
        System.out.println("内存使用: " + usedMB + "MB / " + maxMB + "MB");

        if (!errorTypes.isEmpty()) {
            System.out.println("\n错误类型:");
            errorTypes.forEach((type, count) -> {
                System.out.println("  " + type + ": " + count.get());
            });
        }
    }

    private static void printFinalReport(AtomicLong successCount, AtomicLong failCount,
                                         AtomicLong reconnectCount,
                                         ConcurrentHashMap<String, AtomicLong> errorTypes,
                                         int durationMinutes) {
        long total = successCount.get() + failCount.get();
        double successRate = total > 0 ? (successCount.get() * 100.0 / total) : 0;
        double avgQPS = total / (durationMinutes * 60.0);

        System.out.println("总操作数: " + total);
        System.out.println("成功: " + successCount.get());
        System.out.println("失败: " + failCount.get());
        System.out.println("成功率: " + String.format("%.2f%%", successRate));
        System.out.println("平均 QPS: " + String.format("%.2f", avgQPS));
        System.out.println("重连次数: " + reconnectCount.get());

        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        System.out.println("\n内存使用:");
        System.out.println("  初始: " + (heapUsage.getInit() / 1024 / 1024) + "MB");
        System.out.println("  使用: " + (heapUsage.getUsed() / 1024 / 1024) + "MB");
        System.out.println("  最大: " + (heapUsage.getMax() / 1024 / 1024) + "MB");

        if (!errorTypes.isEmpty()) {
            System.out.println("\n错误统计:");
            errorTypes.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .forEach(entry -> {
                    System.out.println("  " + entry.getKey() + ": " + entry.getValue().get());
                });
        }

        // 稳定性评估
        System.out.println("\n稳定性评估:");
        if (successRate >= 99.9) {
            System.out.println("  ✅ 优秀 (成功率 >= 99.9%)");
        } else if (successRate >= 99.0) {
            System.out.println("  ✅ 良好 (成功率 >= 99.0%)");
        } else if (successRate >= 95.0) {
            System.out.println("  ⚠️  一般 (成功率 >= 95.0%)");
        } else {
            System.out.println("  ❌ 较差 (成功率 < 95.0%)");
        }

        if (reconnectCount.get() == 0) {
            System.out.println("  ✅ 连接稳定 (无重连)");
        } else if (reconnectCount.get() < 10) {
            System.out.println("  ⚠️  连接偶尔中断 (重连 " + reconnectCount.get() + " 次)");
        } else {
            System.out.println("  ❌ 连接不稳定 (重连 " + reconnectCount.get() + " 次)");
        }
    }
}
