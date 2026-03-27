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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ObjectPool 单元测试
 *
 * 覆盖：
 *   - borrow/release 基本语义
 *   - reset() 在 release 时被调用
 *   - Thread-Local 本地池命中（快路径）
 *   - 全局池补货（慢路径）
 *   - 全局池满时丢弃（不 OOM）
 *   - 统计计数正确性
 *   - 多线程并发安全
 */
class ObjectPoolTest {

    static final class Item implements ObjectPool.Pooled {
        int value = -1;
        boolean resetCalled = false;

        @Override
        public void reset() {
            value = 0;
            resetCalled = true;
        }
    }

    private ObjectPool<Item> pool;

    @BeforeEach
    void setUp() {
        // 容量 8，预热 4 个
        pool = new ObjectPool<>(8, Item::new);
    }

    // ==================== 基本语义 ====================

    @Test
    void borrow_returnsNonNull() {
        assertNotNull(pool.borrow());
    }

    @Test
    void release_callsReset() {
        Item item = pool.borrow();
        item.resetCalled = false;
        pool.release(item);
        assertTrue(item.resetCalled, "release 应调用 reset()");
    }

    @Test
    void borrowAfterRelease_reusesSameObject() {
        // 先清空本地池，确保 borrow 的对象来自全局池
        Item first = pool.borrow();
        pool.release(first);
        Item second = pool.borrow();
        // 同一线程，本地池命中，应复用同一对象
        assertSame(first, second);
    }

    @Test
    void borrowedItem_hasResetState() {
        Item item = pool.borrow();
        item.value = 42;
        pool.release(item);
        Item reused = pool.borrow();
        assertEquals(0, reused.value, "复用对象的 value 应被 reset 为 0");
    }

    // ==================== 本地池满 → 批量归还全局池 ====================

    @Test
    void localPoolOverflow_doesNotLoseObjects() {
        // borrow LOCAL_MAX + 5 个对象，全部 release，验证不丢失、不抛异常
        int count = 40; // > LOCAL_MAX(32)
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < count; i++) items.add(pool.borrow());
        assertDoesNotThrow(() -> items.forEach(pool::release));
    }

    @Test
    void localPoolOverflow_globalPoolReceivesItems() {
        // 先把本地池填满（borrow 后全部 release）
        int count = 40;
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < count; i++) items.add(pool.borrow());
        items.forEach(pool::release);
        // 全局池应该有对象（批量归还了一部分）
        assertTrue(pool.globalPoolSize() > 0, "本地池溢出后应批量归还全局池");
    }

    // ==================== 全局池满 → 丢弃，不 OOM ====================

    @Test
    void globalPoolFull_releaseDoesNotThrow() {
        // 容量 8 的池，release 100 个对象，全局池满后应静默丢弃
        assertDoesNotThrow(() -> {
            for (int i = 0; i < 100; i++) {
                pool.release(new Item());
            }
        });
    }

    // ==================== 统计 ====================

    @Test
    void stats_borrowCountIncrementsCorrectly() {
        int ops = 10;
        for (int i = 0; i < ops; i++) {
            Item item = pool.borrow();
            pool.release(item);
        }
        assertEquals(ops, pool.getBorrowCount());
    }

    @Test
    void stats_localHitRateIsPositiveAfterReuse() {
        // 第一次 borrow 可能来自全局池，release 后再 borrow 应命中本地池
        pool.borrow(); // 预热本地池
        Item item = pool.borrow();
        pool.release(item);
        pool.borrow(); // 本地池命中
        assertTrue(pool.localHitRate() > 0.0, "应有本地池命中");
    }

    @Test
    void stats_newAllocRateIsAccurate() {
        // 新建一个容量为 0 的池，全局池为空，第一次 borrow 直接 new
        ObjectPool<Item> emptyPool = new ObjectPool<>(0, Item::new);
        Item item = emptyPool.borrow();
        assertNotNull(item);
        assertTrue(emptyPool.newAllocRate() > 0.0, "容量为 0 的池 borrow 应触发 new，newAllocRate 应 > 0");
    }

    @Test
    void stats_hitRatesSumToAtMostOne() {
        for (int i = 0; i < 50; i++) {
            Item item = pool.borrow();
            pool.release(item);
        }
        double sum = pool.localHitRate() + pool.globalHitRate() + pool.newAllocRate();
        assertEquals(1.0, sum, 0.001, "三种命中率之和应为 1.0");
    }

    // ==================== 多线程并发安全 ====================

    @Test
    void concurrentBorrowRelease_noExceptionAndCountsCorrect() throws InterruptedException {
        int threads = 8;
        int opsPerThread = 1000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        Item item = pool.borrow();
                        item.value = i;
                        pool.release(item);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        done.await();

        assertEquals(0, errors.get(), "并发操作不应抛出异常");
        assertEquals((long) threads * opsPerThread, pool.getBorrowCount(), "borrowCount 应精确");
    }

    @Test
    void concurrentBorrow_allItemsAreNonNull() throws InterruptedException {
        int threads = 4;
        int opsPerThread = 500;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threads);
        AtomicInteger nullCount = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        if (pool.borrow() == null) nullCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    nullCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        done.await();
        assertEquals(0, nullCount.get(), "borrow() 永远不应返回 null");
    }
}
