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
package com.hutulock.server.fast;

import com.hutulock.spi.lock.LockService;
import com.hutulock.spi.lock.LockToken;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 读写分离锁服务接口（秒杀场景优化）
 *
 * <p>设计理念：
 * <ul>
 *   <li>读操作（查询锁状态）：本地内存读取，O(1) 延迟 < 1ms</li>
 *   <li>写操作（获取/释放锁）：批量提交到 Raft，减少 RPC 次数</li>
 *   <li>异步响应：写操作返回 CompletableFuture，避免阻塞调用线程</li>
 * </ul>
 *
 * <p>性能目标：
 * <ul>
 *   <li>读 QPS：> 100万（纯内存）</li>
 *   <li>写 QPS：> 10万（批量提交，每批 100 条）</li>
 *   <li>P99 延迟：< 50ms（批量窗口 10ms + Raft 复制 30ms）</li>
 * </ul>
 *
 * <p>使用场景：
 * <pre>{@code
 *   // 秒杀场景：先读后写
 *   if (service.isLockAvailable("seckill-item-123")) {
 *       service.tryAcquireAsync("seckill-item-123", sessionId, 5, TimeUnit.SECONDS)
 *              .thenAccept(token -> {
 *                  if (token != null) {
 *                      // 扣减库存
 *                      service.releaseAsync(token);
 *                  }
 *              });
 *   }
 * }</pre>
 *
 * @author HutuLock Authors
 * @since 1.1.0
 */
public interface ReadWriteSplitLockService extends LockService {

    /**
     * 查询锁是否可用（本地内存读取，无 Raft 开销）。
     *
     * <p>注意：此方法返回的是快照状态，可能存在短暂不一致（最终一致性）。
     * 适用于秒杀场景的快速过滤，不适用于强一致性要求的场景。
     *
     * @param lockName 锁名称
     * @return true 表示锁当前可用（未被持有），false 表示已被持有
     */
    boolean isLockAvailable(String lockName);

    /**
     * 异步获取锁（批量提交到 Raft）。
     *
     * <p>写操作会进入批量队列，等待批量窗口（默认 10ms）或队列满（默认 100 条）后
     * 统一提交到 Raft，减少 RPC 次数。
     *
     * @param lockName  锁名称
     * @param sessionId 会话 ID
     * @param timeout   最大等待时间
     * @param unit      时间单位
     * @return CompletableFuture，成功时返回 LockToken，失败时返回 null
     */
    CompletableFuture<LockToken> tryAcquireAsync(String lockName, String sessionId,
                                                   long timeout, TimeUnit unit);

    /**
     * 异步释放锁（批量提交到 Raft）。
     *
     * @param token 锁令牌
     * @return CompletableFuture，完成时表示释放成功
     */
    CompletableFuture<Void> releaseAsync(LockToken token);

    /**
     * 同步获取锁（兼容原有 LockService 接口）。
     *
     * <p>内部调用 {@link #tryAcquireAsync} 并阻塞等待结果。
     */
    @Override
    default LockToken tryAcquire(String lockName, String sessionId, long timeout, TimeUnit unit) {
        try {
            return tryAcquireAsync(lockName, sessionId, timeout, unit).get(timeout, unit);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 同步释放锁（兼容原有 LockService 接口）。
     *
     * <p>内部调用 {@link #releaseAsync} 并阻塞等待结果。
     */
    @Override
    default void release(LockToken token) {
        try {
            releaseAsync(token).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // 释放失败，依赖会话过期机制兜底
        }
    }
}
