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
package com.hutulock.server.mem;

import com.hutulock.model.util.Numbers;
import com.hutulock.server.ioc.Lifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 内存管理器
 *
 * <p>统一管理服务端所有内存优化组件，作为单例注册到 IoC 容器。
 *
 * <p>包含：
 * <ul>
 *   <li>{@link ZNodePathCache} — ZNodePath 路径对象缓存，消除重复 new</li>
 *   <li>{@link ObjectPool}{@code <PooledLockToken>} — LockToken 对象池</li>
 * </ul>
 *
 * <p>GC 优化效果（压力测试对比）：
 * <pre>
 *   优化前：每次锁操作 new ~5 个短生命周期对象（ZNodePath × 3 + LockToken + Builder）
 *   优化后：路径命中缓存时 0 次 new，LockToken 命中池时 0 次 new
 * </pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class MemoryManager implements Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);

    /** LockToken 对象池容量（建议 = 预期并发锁数 × 2） */
    private static final int LOCK_TOKEN_POOL_SIZE = Numbers.LOCK_TOKEN_POOL_SIZE;

    private final ZNodePathCache              pathCache;
    private final ObjectPool<PooledLockToken> lockTokenPool;

    public MemoryManager() {
        this.pathCache     = new ZNodePathCache();
        this.lockTokenPool = new ObjectPool<>(LOCK_TOKEN_POOL_SIZE, PooledLockToken::new);
    }

    public ZNodePathCache              getPathCache()      { return pathCache;     }
    public ObjectPool<PooledLockToken> getLockTokenPool()  { return lockTokenPool; }

    @Override
    public void start() {
        log.info("MemoryManager started — pathCache.maxSize=8192, lockTokenPool.capacity={}",
            LOCK_TOKEN_POOL_SIZE);
    }

    @Override
    public void shutdown() {
        log.info("MemoryManager stats — pathCache.size={}, lockToken.localHitRate={:.2f}%, globalHitRate={:.2f}%, borrows={}",
            pathCache.size(),
            lockTokenPool.localHitRate()  * 100,
            lockTokenPool.globalHitRate() * 100,
            lockTokenPool.getBorrowCount());
    }
}
