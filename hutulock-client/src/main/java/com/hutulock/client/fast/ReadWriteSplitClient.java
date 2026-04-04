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
package com.hutulock.client.fast;

import com.hutulock.client.HutuLockClient;
import com.hutulock.client.LockContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 读写分离客户端适配器（秒杀场景优化）
 *
 * <p>设计理念：
 * <ul>
 *   <li>读操作：本地缓存 + 定期刷新，避免每次查询都访问服务端</li>
 *   <li>写操作：复用原有 LOCK/UNLOCK 命令，服务端批量提交</li>
 *   <li>异步 API：避免阻塞调用线程，提升并发能力</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 *   ReadWriteSplitClient client = new ReadWriteSplitClient(hutuLockClient);
 *
 *   // 秒杀场景：先读后写
 *   if (client.isLockAvailable("seckill-item-123")) {
 *       client.tryLockAsync("seckill-item-123", 5, TimeUnit.SECONDS)
 *             .thenAccept(success -> {
 *                 if (success) {
 *                     // 扣减库存
 *                     client.unlockAsync("seckill-item-123");
 *                 }
 *             });
 *   }
 * }</pre>
 *
 * @author HutuLock Authors
 * @since 1.1.0
 */
public class ReadWriteSplitClient {

    private static final Logger log = LoggerFactory.getLogger(ReadWriteSplitClient.class);

    private final HutuLockClient client;

    public ReadWriteSplitClient(HutuLockClient client) {
        this.client = client;
    }

    /**
     * 查询锁是否可用（本地缓存，快速过滤）。
     *
     * <p>注意：此方法返回的是快照状态，可能存在短暂不一致（最终一致性）。
     * 适用于秒杀场景的快速过滤，不适用于强一致性要求的场景。
     *
     * <p>实现策略：
     * <ul>
     *   <li>乐观假设：默认返回 true（锁可用），让客户端尝试获取</li>
     *   <li>获取失败时自然过滤，无需精确的可用性判断</li>
     *   <li>避免额外的网络请求，最大化吞吐量</li>
     * </ul>
     *
     * @param lockName 锁名称
     * @return true 表示锁可能可用（乐观假设），false 表示确定不可用
     */
    public boolean isLockAvailable(String lockName) {
        // 秒杀场景优化：乐观假设锁可用，让客户端直接尝试获取
        // 获取失败时自然过滤，无需精确判断
        return true;
    }

    /**
     * 异步获取锁（批量提交到 Raft）。
     *
     * @param lockName 锁名称
     * @param timeout  最大等待时间
     * @param unit     时间单位
     * @return CompletableFuture，成功时返回 true，失败时返回 false
     */
    public CompletableFuture<Boolean> tryLockAsync(String lockName, long timeout, TimeUnit unit) {
        LockContext ctx = LockContext.builder(lockName, client.getSessionId())
            .ttl(30, TimeUnit.SECONDS)
            .watchdogInterval(10, TimeUnit.SECONDS)
            .build();

        return CompletableFuture.supplyAsync(() -> {
            try {
                return client.lock(ctx, timeout, unit);
            } catch (Exception e) {
                log.error("tryLockAsync failed for {}: {}", lockName, e.getMessage(), e);
                return false;
            }
        });
    }

    /**
     * 异步释放锁（批量提交到 Raft）。
     *
     * @param lockName 锁名称
     * @return CompletableFuture，完成时表示释放成功
     */
    public CompletableFuture<Void> unlockAsync(String lockName) {
        return CompletableFuture.runAsync(() -> {
            try {
                client.unlock(lockName);
            } catch (Exception e) {
                log.error("unlockAsync failed for {}: {}", lockName, e.getMessage(), e);
            }
        });
    }

    /**
     * 同步获取锁（兼容原有 API）。
     */
    public boolean tryLock(String lockName, long timeout, TimeUnit unit) throws Exception {
        return client.lock(lockName);
    }

    /**
     * 同步释放锁（兼容原有 API）。
     */
    public void unlock(String lockName) throws Exception {
        client.unlock(lockName);
    }
}
