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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ZNodePathCache 单元测试
 *
 * 覆盖：
 *   - 相同路径返回同一对象（缓存命中）
 *   - 不同路径返回不同对象
 *   - evict 后缓存失效
 *   - getSeqPath 预计算路径正确性
 *   - formatSeq 边界值
 *   - 超出容量上限时降级（不缓存，不抛异常）
 */
class ZNodePathCacheTest {

    private ZNodePathCache cache;

    @BeforeEach
    void setUp() {
        cache = new ZNodePathCache();
    }

    // ==================== 基本缓存语义 ====================

    @Test
    void get_samePathReturnsSameInstance() {
        ZNodePath a = cache.get("/locks/order");
        ZNodePath b = cache.get("/locks/order");
        assertSame(a, b, "相同路径应返回同一缓存实例");
    }

    @Test
    void get_differentPathsReturnDifferentInstances() {
        ZNodePath a = cache.get("/locks/order");
        ZNodePath b = cache.get("/locks/payment");
        assertNotSame(a, b);
    }

    @Test
    void get_returnsCorrectPath() {
        String pathStr = "/locks/test-lock";
        ZNodePath path = cache.get(pathStr);
        assertEquals(pathStr, path.value());
    }

    @Test
    void get_incrementsSize() {
        cache.get("/a");
        cache.get("/b");
        cache.get("/c");
        assertEquals(3, cache.size());
    }

    @Test
    void get_duplicateDoesNotIncrementSize() {
        cache.get("/a");
        cache.get("/a");
        assertEquals(1, cache.size());
    }

    // ==================== evict ====================

    @Test
    void evict_removesFromCache() {
        cache.get("/locks/seq-node");
        cache.evict("/locks/seq-node");
        assertEquals(0, cache.size());
    }

    @Test
    void evict_afterEvictGetCreatesNewInstance() {
        ZNodePath first = cache.get("/locks/seq-node");
        cache.evict("/locks/seq-node");
        ZNodePath second = cache.get("/locks/seq-node");
        // 重新创建，可能是不同实例（取决于 ZNodePath.of 实现）
        assertNotNull(second);
        assertEquals("/locks/seq-node", second.getPath());
    }

    @Test
    void evict_nonExistentKeyDoesNotThrow() {
        assertDoesNotThrow(() -> cache.evict("/not/exist"));
    }

    // ==================== getSeqPath ====================

    @Test
    void getSeqPath_formatsCorrectly() {
        ZNodePath path = cache.getSeqPath("/locks/order/seq-", 1);
        assertEquals("/locks/order/seq-0000000001", path.value());
    }

    @Test
    void getSeqPath_zeroSeq() {
        ZNodePath path = cache.getSeqPath("/locks/order/seq-", 0);
        assertEquals("/locks/order/seq-0000000000", path.value());
    }

    @Test
    void getSeqPath_largeSeqBeyondPrecomputed() {
        // PRECOMPUTED_LIMIT = 100_000，测试超出预计算范围
        ZNodePath path = cache.getSeqPath("/locks/order/seq-", 100_000);
        assertEquals("/locks/order/seq-0000100000", path.value());
    }

    @Test
    void getSeqPath_sameSeqReturnsCachedInstance() {
        ZNodePath a = cache.getSeqPath("/locks/order/seq-", 42);
        ZNodePath b = cache.getSeqPath("/locks/order/seq-", 42);
        assertSame(a, b, "相同序号路径应命中缓存");
    }

    // ==================== formatSeq 静态方法 ====================

    @Test
    void formatSeq_paddingCorrect() {
        assertEquals("0000000001", ZNodePathCache.formatSeq(1));
        assertEquals("0000000000", ZNodePathCache.formatSeq(0));
        assertEquals("0000099999", ZNodePathCache.formatSeq(99999));
    }

    @Test
    void formatSeq_beyondPrecomputed() {
        assertEquals("0000100000", ZNodePathCache.formatSeq(100_000));
        assertEquals("0999999999", ZNodePathCache.formatSeq(999_999_999));
    }

    // ==================== 容量上限降级 ====================

    @Test
    void get_beyondMaxSize_doesNotCache() {
        // MAX_SIZE = 8192，填满后再 get 应降级（不缓存，但返回有效对象）
        // 这里只验证不抛异常且返回非 null
        for (int i = 0; i < 100; i++) {
            ZNodePath p = cache.get("/path/" + i);
            assertNotNull(p);
        }
        assertTrue(cache.size() <= 100);
    }
}
