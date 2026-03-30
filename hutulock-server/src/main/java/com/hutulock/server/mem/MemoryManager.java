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
 * <p>容量配置（通过构造器注入，默认值来自 {@link Numbers}）：
 * <pre>
 *   new MemoryManager()                          // 使用默认容量
 *   new MemoryManager(lockTokenPoolSize)         // 自定义 LockToken 池容量
 * </pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class MemoryManager implements Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);

    private final int                         lockTokenPoolSize;
    private final ZNodePathCache              pathCache;
    private final ObjectPool<PooledLockToken> lockTokenPool;

    /** 使用默认容量构造。 */
    public MemoryManager() {
        this(Numbers.LOCK_TOKEN_POOL_SIZE);
    }

    /**
     * 使用自定义 LockToken 池容量构造。
     *
     * @param lockTokenPoolSize LockToken 对象池容量（建议 = 预期并发锁数 × 2）
     */
    public MemoryManager(int lockTokenPoolSize) {
        this.lockTokenPoolSize = lockTokenPoolSize;
        this.pathCache         = new ZNodePathCache();
        this.lockTokenPool     = new ObjectPool<>(lockTokenPoolSize, PooledLockToken::new);
    }

    public ZNodePathCache              getPathCache()     { return pathCache;     }
    public Pool<PooledLockToken>       getLockTokenPool() { return lockTokenPool; }

    // ==================== 实时快照 ====================

    /**
     * 返回当前内存管理器的运行时快照，可用于指标上报或健康检查。
     */
    public Stats snapshot() {
        return new Stats(pathCache.stats(), lockTokenPool.stats());
    }

    /**
     * 内存管理器运行时快照（聚合 {@link CacheStats} 和 {@link PoolStats}）。
     */
    public static final class Stats {
        public final CacheStats cache;
        public final PoolStats  pool;

        Stats(CacheStats cache, PoolStats pool) {
            this.cache = cache;
            this.pool  = pool;
        }

        /** 是否存在任何需要关注的异常信号（缓存降级或对象池丢弃）。 */
        public boolean hasWarnings() {
            return cache.hasBypasses() || pool.hasDiscards();
        }

        @Override
        public String toString() {
            return "MemoryStats{" + cache + ", " + pool + "}";
        }
    }

    // ==================== 生命周期 ====================

    @Override
    public void start() {
        log.info("MemoryManager started — pathCache.maxSize={}, lockTokenPool.capacity={}",
            Numbers.PATH_CACHE_MAX_SIZE, lockTokenPoolSize);
    }

    @Override
    public void shutdown() {
        log.info("MemoryManager shutdown — {}", snapshot());
    }
}
