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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 快速压力测试工具（简化版）
 *
 * <p>用于快速验证性能，无需复杂配置。
 *
 * @author HutuLock Authors
 * @since 1.1.0
 */
public class QuickBenchmark {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Quick Benchmark ===");

        // 创建客户端
        HutuLockClient client = HutuLockClient.builder()
            .addNode("127.0.0.1", 8881)
            .build();

        try {
            client.connect();
            System.out.println("✓ Connected to cluster");

            ReadWriteSplitClient fastClient = new ReadWriteSplitClient(client);

            // 测试 1：纯读性能（10 秒）
            System.out.println("\n--- Test 1: Read Performance ---");
            testRead(fastClient, 100, 10);

            Thread.sleep(2000);

            // 测试 2：纯写性能（10 秒）
            System.out.println("\n--- Test 2: Write Performance ---");
            testWrite(fastClient, 50, 10);

            Thread.sleep(2000);

            // 测试 3：混合性能（10 秒，9:1 读写比）
            System.out.println("\n--- Test 3: Mixed Performance (90% read) ---");
            testMixed(fastClient, 100, 10, 90);

        } finally {
            client.close();
        }
    }

    private static void testRead(ReadWriteSplitClient client, int threads, int seconds) throws Exception {
        AtomicInteger count = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threads);
        long startMs = System.currentTimeMillis();

        for (int i = 0; i < threads; i++) {
            int threadId = i;
            new Thread(() -> {
                try {
                    long endMs = startMs + seconds * 1000L;
                    while (System.currentTimeMillis() < endMs) {
                        client.isLockAvailable("bench-" + (threadId % 10));
                        count.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        long elapsedMs = System.currentTimeMillis() - startMs;
        double qps = (count.get() * 1000.0) / elapsedMs;

        System.out.println("Threads: " + threads);
        System.out.println("Duration: " + seconds + "s");
        System.out.println("Total Requests: " + count.get());
        System.out.println("QPS: " + String.format("%.0f", qps));
    }

    private static void testWrite(ReadWriteSplitClient client, int threads, int seconds) throws Exception {
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger fail = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threads);
        long startMs = System.currentTimeMillis();

        for (int i = 0; i < threads; i++) {
            int threadId = i;
            new Thread(() -> {
                try {
                    long endMs = startMs + seconds * 1000L;
                    while (System.currentTimeMillis() < endMs) {
                        String lockName = "bench-" + threadId;
                        try {
                            boolean locked = client.tryLockAsync(lockName, 5, TimeUnit.SECONDS)
                                .get(5, TimeUnit.SECONDS);
                            if (locked) {
                                Thread.sleep(1);
                                client.unlockAsync(lockName).get(5, TimeUnit.SECONDS);
                                success.incrementAndGet();
                            } else {
                                fail.incrementAndGet();
                            }
                        } catch (Exception e) {
                            fail.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    // ignore
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        long elapsedMs = System.currentTimeMillis() - startMs;
        int total = success.get() + fail.get();
        double qps = (total * 1000.0) / elapsedMs;

        System.out.println("Threads: " + threads);
        System.out.println("Duration: " + seconds + "s");
        System.out.println("Success: " + success.get() + ", Fail: " + fail.get());
        System.out.println("QPS: " + String.format("%.0f", qps));
    }

    private static void testMixed(ReadWriteSplitClient client, int threads, int seconds, int readRatio) throws Exception {
        AtomicInteger count = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threads);
        long startMs = System.currentTimeMillis();

        for (int i = 0; i < threads; i++) {
            int threadId = i;
            new Thread(() -> {
                try {
                    long endMs = startMs + seconds * 1000L;
                    while (System.currentTimeMillis() < endMs) {
                        if (Math.random() * 100 < readRatio) {
                            // 读操作
                            client.isLockAvailable("bench-" + (threadId % 10));
                        } else {
                            // 写操作
                            String lockName = "bench-" + threadId;
                            try {
                                boolean locked = client.tryLockAsync(lockName, 5, TimeUnit.SECONDS)
                                    .get(5, TimeUnit.SECONDS);
                                if (locked) {
                                    Thread.sleep(1);
                                    client.unlockAsync(lockName).get(5, TimeUnit.SECONDS);
                                }
                            } catch (Exception e) {
                                // ignore
                            }
                        }
                        count.incrementAndGet();
                    }
                } catch (Exception e) {
                    // ignore
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        long elapsedMs = System.currentTimeMillis() - startMs;
        double qps = (count.get() * 1000.0) / elapsedMs;

        System.out.println("Threads: " + threads);
        System.out.println("Duration: " + seconds + "s");
        System.out.println("Read Ratio: " + readRatio + "%");
        System.out.println("Total Requests: " + count.get());
        System.out.println("QPS: " + String.format("%.0f", qps));
    }
}
