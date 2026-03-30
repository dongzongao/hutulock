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

import com.hutulock.model.znode.ZNodePath;
import com.hutulock.model.util.Numbers;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * ZNodePath 路径缓存（String intern 模式）
 *
 * <p>锁路径高度重复（如 {@code /locks/order-lock/seq-0000000001}），
 * 每次 {@link ZNodePath#of(String)} 都会 new 一个新对象。
 * 通过缓存已创建的路径对象，消除重复分配。
 *
 * <p>缓存策略：
 * <ul>
 *   <li>PERSISTENT 路径（锁根节点）：永久缓存，数量有限</li>
 *   <li>EPHEMERAL_SEQ 路径（顺序节点）：节点删除时主动 evict</li>
 *   <li>超过容量上限时不缓存（降级为直接 new），并计入 {@link #getMissCount()} 降级计数</li>
 * </ul>
 *
 * <p>顺序节点路径格式预计算：
 * {@code /locks/{lockName}/seq-} + {@link #formatSeq(int)} 避免 {@code String.format}。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class ZNodePathCache {

    /** 最大缓存条目数，超出后不缓存（防止内存泄漏） */
    private static final int MAX_SIZE = Numbers.PATH_CACHE_MAX_SIZE;

    /** 预计算的 10 位补零序号字符串，覆盖 0 ~ 99999 */
    private static final int    PRECOMPUTED_LIMIT = Numbers.PATH_CACHE_PRECOMPUTED;
    private static final String[] SEQ_STRINGS     = new String[PRECOMPUTED_LIMIT];

    static {
        for (int i = 0; i < PRECOMPUTED_LIMIT; i++) {
            SEQ_STRINGS[i] = String.format("%010d", i);
        }
    }

    private final ConcurrentHashMap<String, ZNodePath> cache = new ConcurrentHashMap<>();

    // ---- 命中率统计 ----
    private final LongAdder hits      = new LongAdder();
    private final LongAdder misses    = new LongAdder(); // 未命中但已缓存
    private final LongAdder bypasses  = new LongAdder(); // 超容量降级，未缓存
    private final LongAdder evictions = new LongAdder();

    /**
     * 获取或创建 ZNodePath，优先从缓存返回。
     */
    public ZNodePath get(String pathStr) {
        ZNodePath cached = cache.get(pathStr);
        if (cached != null) {
            hits.increment();
            return cached;
        }
        if (cache.size() >= MAX_SIZE) {
            bypasses.increment();
            return ZNodePath.of(pathStr); // 降级：不缓存
        }
        misses.increment();
        return cache.computeIfAbsent(pathStr, ZNodePath::of);
    }

    /**
     * 构造顺序节点路径，使用预计算序号字符串避免 String.format。
     *
     * @param prefix  路径前缀，如 {@code /locks/order-lock/seq-}
     * @param seqNum  序号（1-based）
     */
    public ZNodePath getSeqPath(String prefix, int seqNum) {
        String seq = seqNum < PRECOMPUTED_LIMIT ? SEQ_STRINGS[seqNum] : String.format("%010d", seqNum);
        return get(prefix + seq);
    }

    /**
     * 主动移除缓存条目（节点删除时调用，防止内存泄漏）。
     */
    public void evict(String pathStr) {
        if (cache.remove(pathStr) != null) {
            evictions.increment();
        }
    }

    /**
     * 格式化序号为 10 位补零字符串（静态工具方法）。
     */
    public static String formatSeq(int seqNum) {
        return seqNum < PRECOMPUTED_LIMIT ? SEQ_STRINGS[seqNum] : String.format("%010d", seqNum);
    }

    // ==================== 统计 ====================

    public int  size()          { return cache.size();    }
    public long getHitCount()   { return hits.sum();      }
    public long getMissCount()  { return misses.sum();    }
    public long getBypassCount(){ return bypasses.sum();  }
    public long getEvictCount() { return evictions.sum(); }

    /** 缓存命中率（0.0 ~ 1.0），未发生任何查询时返回 0.0。 */
    public double hitRate() {
        long total = hits.sum() + misses.sum() + bypasses.sum();
        return total == 0 ? 0.0 : (double) hits.sum() / total;
    }
}
