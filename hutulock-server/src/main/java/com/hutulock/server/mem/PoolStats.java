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

/**
 * 对象池运行时统计快照（不可变值对象）
 *
 * <p>从 {@link MemoryManager.Stats} 中独立出来，使对象池统计可以单独使用和测试。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class PoolStats {

    public final long   borrowCount;
    public final double localHitRate;
    public final double globalHitRate;
    public final double newAllocRate;
    public final long   discardCount;
    public final int    globalPoolSize;

    public PoolStats(long borrowCount, double localHitRate, double globalHitRate,
                     double newAllocRate, long discardCount, int globalPoolSize) {
        this.borrowCount    = borrowCount;
        this.localHitRate   = localHitRate;
        this.globalHitRate  = globalHitRate;
        this.newAllocRate   = newAllocRate;
        this.discardCount   = discardCount;
        this.globalPoolSize = globalPoolSize;
    }

    /** 是否存在丢弃（全局池满导致对象被丢弃），可用于判断是否需要扩容。 */
    public boolean hasDiscards() {
        return discardCount > 0;
    }

    @Override
    public String toString() {
        return String.format(
            "PoolStats{borrows=%d, localHit=%.2f%%, globalHit=%.2f%%, newAlloc=%.2f%%, discards=%d, poolSize=%d}",
            borrowCount, localHitRate * 100, globalHitRate * 100,
            newAllocRate * 100, discardCount, globalPoolSize);
    }
}
