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
package com.hutulock.client.benchmark;

import com.hutulock.client.HutuLockClient;
import com.hutulock.client.fast.ReadWriteSplitClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * 分布式锁压力测试工具
 *
 * <p>测试场景：
 * <ul>
 *   <li>纯读测试：测试 isLockAvailable() 的吞吐量</li>
 *   <li>纯写测试：测试 lock/unlock 的吞吐量</li>
 *   <li>读写混合测试：模拟真实秒杀场景（9:1 读写比）</li>
 * </ul>
 *
 * <p>性能指标：
 * <ul>
 *   <li>QPS（每秒请求数）</li>
 *   <li>延迟分布（P50/P95/P99/P999）</li>
 *   <li>成功率</li>
 * </ul>
 *
 * @author HutuLock Authors
 * @since 1.1.0
 */
public class LockBenchmark {

    private static final Logger log = LoggerFactory.getLogger(LockBenchmark.class);

    private final ReadWriteSplitClient client;
    private final int                  threads;
    private final int                  duration;
    private final ExecutorService      executor;

    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder successCount  = new LongAdder();
    private final LongAdder failCount     = new LongAdder();
    private final List<Long> latencies    = new CopyOnWriteArrayList<>();

    public LockBenchmark(ReadWriteSplitClient client, int threads, int duration) {
        this.client   = client;
        this.threads  = threads;
        this.duration = duration;
        this.executor = Executors.newFixedThreadPool(threads);
    }

    /**
     * 纯读测试：测试 isLockAvailable() 的吞吐量。
     */
    public BenchmarkResult runReadOnlyTest() throws Exception {
        log.info("=== Read-Only Test Start ===");
        log.info("Threads: {}, Duration: {}s", threads, duration);

        reset();
        long startMs = System.currentTimeMillis();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long endMs = startMs + duration * 1000L;
                    while (System.currentTimeMillis() < endMs) {
                        long reqStart = System.nanoTime();
                        boolean available = client.isLockAvailable("bench-lock-" + (threadId % 10));
                        long latencyNs = System.nanoTime() - reqStart;

                        totalRequests.increment();
                        if (available) successCount.increment();
                        else failCount.increment();
                        latencies.add(latencyNs / 1000); // 转换为微秒
                    }
                } catch (Exception e) {
                    log.error("Thread {} error: {}", threadId, e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        long elapsedMs = System.currentTimeMillis() - startMs;

        return calculateResult("Read-Only", elapsedMs);
    }

    /**
     * 纯写测试：测试 lock/unlock 的吞吐量。
     */
    public BenchmarkResult runWriteOnlyTest() throws Exception {
        log.info("=== Write-Only Test Start ===");
        log.info("Threads: {}, Duration: {}s", threads, duration);

        reset();
        long startMs = System.currentTimeMillis();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long endMs = startMs + duration * 1000L;
                    while (System.currentTimeMillis() < endMs) {
                        String lockName = "bench-lock-" + threadId;
                        long reqStart = System.nanoTime();

                        CompletableFuture<Boolean> lockFuture = 
                            client.tryLockAsync(lockName, 5, TimeUnit.SECONDS);
                        boolean success = lockFuture.get(5, TimeUnit.SECONDS);

                        if (success) {
                            // 模拟业务处理（1ms）
                            Thread.sleep(1);
                            client.unlockAsync(lockName).get(5, TimeUnit.SECONDS);
                            successCount.increment();
                        } else {
                            failCount.increment();
                        }

                        long latencyNs = System.nanoTime() - reqStart;
                        totalRequests.increment();
                        latencies.add(latencyNs / 1000); // 转换为微秒
                    }
                } catch (Exception e) {
                    log.error("Thread {} error: {}", threadId, e.getMessage());
                    failCount.increment();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        long elapsedMs = System.currentTimeMillis() - startMs;

        return calculateResult("Write-Only", elapsedMs);
    }

    /**
     * 读写混合测试：模拟真实秒杀场景（9:1 读写比）。
     */
    public BenchmarkResult runMixedTest(int readRatio) throws Exception {
        log.info("=== Mixed Test Start (Read:Write = {}:{}) ===", readRatio, 100 - readRatio);
        log.info("Threads: {}, Duration: {}s", threads, duration);

        reset();
        long startMs = System.currentTimeMillis();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(threads);
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int i = 0; i < threads; i++) {
            int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long endMs = startMs + duration * 1000L;
                    while (System.currentTimeMillis() < endMs) {
                        String lockName = "bench-lock-" + (threadId % 10);
                        long reqStart = System.nanoTime();

                        if (random.nextInt(100) < readRatio) {
                            // 读操作
                            boolean available = client.isLockAvailable(lockName);
                            if (available) successCount.increment();
                            else failCount.increment();
                        } else {
                            // 写操作
                            CompletableFuture<Boolean> lockFuture = 
                                client.tryLockAsync(lockName, 5, TimeUnit.SECONDS);
                            boolean success = lockFuture.get(5, TimeUnit.SECONDS);

                            if (success) {
                                Thread.sleep(1);
                                client.unlockAsync(lockName).get(5, TimeUnit.SECONDS);
                                successCount.increment();
                            } else {
                                failCount.increment();
                            }
                        }

                        long latencyNs = System.nanoTime() - reqStart;
                        totalRequests.increment();
                        latencies.add(latencyNs / 1000);
                    }
                } catch (Exception e) {
                    log.error("Thread {} error: {}", threadId, e.getMessage());
                    failCount.increment();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        long elapsedMs = System.currentTimeMillis() - startMs;

        return calculateResult("Mixed(" + readRatio + ":" + (100 - readRatio) + ")", elapsedMs);
    }

    private void reset() {
        totalRequests.reset();
        successCount.reset();
        failCount.reset();
        latencies.clear();
    }

    private BenchmarkResult calculateResult(String testName, long elapsedMs) {
        long total = totalRequests.sum();
        long success = successCount.sum();
        long fail = failCount.sum();
        double qps = (total * 1000.0) / elapsedMs;

        // 计算延迟分布
        List<Long> sorted = new ArrayList<>(latencies);
        sorted.sort(Long::compareTo);

        LatencyStats latencyStats = new LatencyStats();
        if (!sorted.isEmpty()) {
            latencyStats.p50  = percentile(sorted, 50);
            latencyStats.p95  = percentile(sorted, 95);
            latencyStats.p99  = percentile(sorted, 99);
            latencyStats.p999 = percentile(sorted, 99.9);
            latencyStats.min  = sorted.get(0);
            latencyStats.max  = sorted.get(sorted.size() - 1);
        }

        BenchmarkResult result = new BenchmarkResult(
            testName, threads, duration, total, success, fail, qps, latencyStats);

        log.info("=== {} Test Complete ===", testName);
        log.info("Total Requests: {}", total);
        log.info("Success: {}, Fail: {}", success, fail);
        log.info("QPS: {}", String.format("%.2f", qps));
        log.info("Latency (μs) - P50: {}, P95: {}, P99: {}, P999: {}",
            latencyStats.p50, latencyStats.p95, latencyStats.p99, latencyStats.p999);

        return result;
    }

    private long percentile(List<Long> sorted, double p) {
        int index = (int) Math.ceil(sorted.size() * p / 100.0) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ==================== 结果类 ====================

    public static class BenchmarkResult {
        public final String       testName;
        public final int          threads;
        public final int          duration;
        public final long         totalRequests;
        public final long         successCount;
        public final long         failCount;
        public final double       qps;
        public final LatencyStats latency;

        public BenchmarkResult(String testName, int threads, int duration,
                                long totalRequests, long successCount, long failCount,
                                double qps, LatencyStats latency) {
            this.testName      = testName;
            this.threads       = threads;
            this.duration      = duration;
            this.totalRequests = totalRequests;
            this.successCount  = successCount;
            this.failCount     = failCount;
            this.qps           = qps;
            this.latency       = latency;
        }
    }

    public static class LatencyStats {
        public long p50;
        public long p95;
        public long p99;
        public long p999;
        public long min;
        public long max;
    }

    // ==================== 主程序 ====================

    public static void main(String[] args) throws Exception {
        // 解析参数
        String host     = System.getProperty("host", "127.0.0.1");
        int    port     = Integer.parseInt(System.getProperty("port", "8881"));
        int    threads  = Integer.parseInt(System.getProperty("threads", "100"));
        int    duration = Integer.parseInt(System.getProperty("duration", "60"));
        String testType = System.getProperty("test", "all"); // all, read, write, mixed

        log.info("=== HutuLock Benchmark ===");
        log.info("Host: {}, Port: {}", host, port);
        log.info("Threads: {}, Duration: {}s", threads, duration);
        log.info("Test Type: {}", testType);

        // 创建客户端
        HutuLockClient client = HutuLockClient.builder()
            .addNode(host, port)
            .build();

        try {
            client.connect();
            log.info("Connected to HutuLock cluster");

            ReadWriteSplitClient fastClient = new ReadWriteSplitClient(client);
            LockBenchmark benchmark = new LockBenchmark(fastClient, threads, duration);

            List<BenchmarkResult> results = new ArrayList<>();

            // 运行测试
            if ("all".equals(testType) || "read".equals(testType)) {
                results.add(benchmark.runReadOnlyTest());
                Thread.sleep(5000); // 冷却 5 秒
            }

            if ("all".equals(testType) || "write".equals(testType)) {
                results.add(benchmark.runWriteOnlyTest());
                Thread.sleep(5000);
            }

            if ("all".equals(testType) || "mixed".equals(testType)) {
                results.add(benchmark.runMixedTest(90)); // 9:1 读写比
                Thread.sleep(5000);
                results.add(benchmark.runMixedTest(50)); // 5:5 读写比
            }

            // 打印汇总报告
            printSummary(results);

            benchmark.shutdown();

        } finally {
            client.close();
        }
    }

    private static void printSummary(List<BenchmarkResult> results) {
        log.info("\n=== Benchmark Summary ===");
        log.info(String.format("%-20s %10s %10s %10s %10s %10s %10s",
            "Test", "QPS", "P50(μs)", "P95(μs)", "P99(μs)", "P999(μs)", "Success%"));
        log.info("-".repeat(90));

        for (BenchmarkResult r : results) {
            double successRate = (r.successCount * 100.0) / r.totalRequests;
            log.info(String.format("%-20s %10.0f %10d %10d %10d %10d %9.2f%%",
                r.testName, r.qps, r.latency.p50, r.latency.p95,
                r.latency.p99, r.latency.p999, successRate));
        }
    }
}
