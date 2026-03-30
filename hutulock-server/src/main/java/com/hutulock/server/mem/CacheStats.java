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
 * 路径缓存运行时统计快照（不可变值对象）
 *
 * <p>从 {@link MemoryManager.Stats} 中独立出来，使缓存统计可以单独使用和测试。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class CacheStats {

    public final int    size;
    public final double hitRate;
    public final long   hitCount;
    public final long   missCount;
    public final long   bypassCount;
    public final long   evictCount;

    public CacheStats(int size, double hitRate, long hitCount,
                      long missCount, long bypassCount, long evictCount) {
        this.size        = size;
        this.hitRate     = hitRate;
        this.hitCount    = hitCount;
        this.missCount   = missCount;
        this.bypassCount = bypassCount;
        this.evictCount  = evictCount;
    }

    /**
     * 是否存在容量降级（缓存满后直接 new，未缓存）。
     * bypassCount > 0 说明缓存容量可能需要扩大。
     */
    public boolean hasBypasses() {
        return bypassCount > 0;
    }

    @Override
    public String toString() {
        return String.format(
            "CacheStats{size=%d, hitRate=%.2f%%, hits=%d, misses=%d, bypasses=%d, evictions=%d}",
            size, hitRate * 100, hitCount, missCount, bypassCount, evictCount);
    }
}
