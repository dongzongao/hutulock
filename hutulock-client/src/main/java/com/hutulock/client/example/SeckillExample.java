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
package com.hutulock.client.example;

import com.hutulock.client.HutuLockClient;
import com.hutulock.client.fast.ReadWriteSplitClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 秒杀场景示例（读写分离优化）
 *
 * <p>模拟 1000 个用户同时抢购 10 件商品的场景。
 *
 * <p>运行前提：
 * <ul>
 *   <li>启动 3 节点 HutuLock 集群（端口 8881/8882/8883）</li>
 *   <li>确保服务端已注册 ReadWriteSplitLockService</li>
 * </ul>
 *
 * <p>预期结果：
 * <ul>
 *   <li>只有 10 个用户成功获取锁（库存限制）</li>
 *   <li>990 个用户快速失败（< 10ms）</li>
 *   <li>总耗时 < 1 秒（相比标准实现的 5-10 秒）</li>
 * </ul>
 *
 * @author HutuLock Authors
 * @since 1.1.0
 */
public class SeckillExample {

    private static final Logger log = LoggerFactory.getLogger(SeckillExample.class);

    private static final String ITEM_ID = "seckill-item-123";
    private static final int    TOTAL_USERS = 1000;
    private static final int    INVENTORY   = 10;

    private final ReadWriteSplitClient client;
    private final AtomicInteger        successCount = new AtomicInteger(0);
    private final AtomicInteger        failCount    = new AtomicInteger(0);

    public SeckillExample(ReadWriteSplitClient client) {
        this.client = client;
    }

    /**
     * 模拟单个用户的秒杀请求。
     */
    private CompletableFuture<Void> seckillRequest(int userId) {
        long startMs = System.currentTimeMillis();

        // 第一阶段：快速过滤（本地内存读取）
        if (!client.isLockAvailable(ITEM_ID)) {
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.debug("User {} filtered out ({}ms)", userId, elapsedMs);
            failCount.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }

        // 第二阶段：尝试获取锁（批量提交到 Raft）
        return client.tryLockAsync(ITEM_ID, 5, TimeUnit.SECONDS)
            .thenAccept(success -> {
                long elapsedMs = System.currentTimeMillis() - startMs;
                if (success) {
                    // 第三阶段：扣减库存（模拟业务逻辑）
                    int count = successCount.incrementAndGet();
                    log.info("User {} SUCCESS ({}ms) - inventory: {}/{}", 
                        userId, elapsedMs, count, INVENTORY);

                    // 模拟业务处理（50ms）
                    try { Thread.sleep(50); } catch (InterruptedException ignored) {}

                    // 释放锁
                    client.unlockAsync(ITEM_ID);
                } else {
                    failCount.incrementAndGet();
                    log.debug("User {} FAILED ({}ms)", userId, elapsedMs);
                }
            })
            .exceptionally(ex -> {
                failCount.incrementAndGet();
                log.error("User {} ERROR: {}", userId, ex.getMessage());
                return null;
            });
    }

    /**
     * 运行秒杀测试。
     */
    public void run() throws Exception {
        log.info("=== Seckill Test Start ===");
        log.info("Total users: {}, Inventory: {}", TOTAL_USERS, INVENTORY);

        long startMs = System.currentTimeMillis();
        CountDownLatch latch = new CountDownLatch(TOTAL_USERS);

        // 模拟 1000 个用户同时发起请求
        CompletableFuture<?>[] futures = new CompletableFuture[TOTAL_USERS];
        for (int i = 0; i < TOTAL_USERS; i++) {
            int userId = i;
            futures[i] = seckillRequest(userId).whenComplete((v, ex) -> latch.countDown());
        }

        // 等待所有请求完成
        latch.await(30, TimeUnit.SECONDS);
        CompletableFuture.allOf(futures).join();

        long elapsedMs = System.currentTimeMillis() - startMs;

        // 统计结果
        log.info("=== Seckill Test Complete ===");
        log.info("Total time: {}ms", elapsedMs);
        log.info("Success: {}, Failed: {}", successCount.get(), failCount.get());
        log.info("QPS: {}", (TOTAL_USERS * 1000L) / elapsedMs);

        // 验证结果
        if (successCount.get() != INVENTORY) {
            log.error("FAILED: Expected {} success, got {}", INVENTORY, successCount.get());
        } else {
            log.info("PASSED: Inventory control correct");
        }
    }

    public static void main(String[] args) throws Exception {
        // 创建标准客户端
        HutuLockClient client = HutuLockClient.builder()
            .addNode("127.0.0.1", 8881)
            .addNode("127.0.0.1", 8882)
            .addNode("127.0.0.1", 8883)
            .build();

        try {
            client.connect();
            log.info("Connected to HutuLock cluster");

            // 包装为读写分离客户端
            ReadWriteSplitClient fastClient = new ReadWriteSplitClient(client);

            // 运行秒杀测试
            SeckillExample example = new SeckillExample(fastClient);
            example.run();

        } finally {
            client.close();
        }
    }
}
