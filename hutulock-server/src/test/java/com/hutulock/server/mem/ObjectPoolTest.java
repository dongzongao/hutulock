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
 * ObjectPool unit tests.
 *
 * Coverage:
 *   - borrow/release basic semantics
 *   - reset() called on release
 *   - Thread-Local local pool hit (fast path)
 *   - Global pool refill (slow path)
 *   - Global pool full: discard without OOM
 *   - Stats counter correctness
 *   - Multi-thread concurrency safety
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
        // capacity 8, pre-warm 4
        pool = new ObjectPool<>(8, Item::new);
    }

    // ==================== Basic semantics ====================

    @Test
    void borrow_returnsNonNull() {
        assertNotNull(pool.borrow());
    }

    @Test
    void release_callsReset() {
        Item item = pool.borrow();
        item.resetCalled = false;
        pool.release(item);
        assertTrue(item.resetCalled, "release should call reset()");
    }

    @Test
    void borrowAfterRelease_reusesSameObject() {
        // Same thread: local pool should return the same object after release
        Item first = pool.borrow();
        pool.release(first);
        Item second = pool.borrow();
        assertSame(first, second);
    }

    @Test
    void borrowedItem_hasResetState() {
        Item item = pool.borrow();
        item.value = 42;
        pool.release(item);
        Item reused = pool.borrow();
        assertEquals(0, reused.value, "reused object value should be reset to 0");
    }

    // ==================== Local pool overflow -> batch return to global ====================

    @Test
    void localPoolOverflow_doesNotLoseObjects() {
        // borrow LOCAL_MAX + 5 objects, release all, verify no exception
        int count = 40; // > LOCAL_MAX(32)
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < count; i++) items.add(pool.borrow());
        assertDoesNotThrow(() -> items.forEach(pool::release));
    }

    @Test
    void localPoolOverflow_globalPoolReceivesItems() {
        // Fill local pool then release all; global pool should receive batch
        int count = 40;
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < count; i++) items.add(pool.borrow());
        items.forEach(pool::release);
        assertTrue(pool.globalPoolSize() > 0, "global pool should receive items after local overflow");
    }

    // ==================== Global pool full -> discard, no OOM ====================

    @Test
    void globalPoolFull_releaseDoesNotThrow() {
        // Pool capacity 8, release 100 objects; overflow should be silently discarded
        assertDoesNotThrow(() -> {
            for (int i = 0; i < 100; i++) {
                pool.release(new Item());
            }
        });
    }

    // ==================== Stats ====================

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
        // First borrow may come from global pool; after release, next borrow hits local pool
        pool.borrow(); // warm up local pool
        Item item = pool.borrow();
        pool.release(item);
        pool.borrow(); // local pool hit
        assertTrue(pool.localHitRate() > 0.0, "should have local pool hits");
    }

    @Test
    void stats_newAllocRateIsAccurate() {
        // Pool with capacity 0: global pool empty, first borrow must allocate new
        ObjectPool<Item> emptyPool = new ObjectPool<>(0, Item::new);
        Item item = emptyPool.borrow();
        assertNotNull(item);
        assertTrue(emptyPool.newAllocRate() > 0.0, "capacity-0 pool borrow should trigger new alloc");
    }

    @Test
    void stats_hitRatesSumToAtMostOne() {
        for (int i = 0; i < 50; i++) {
            Item item = pool.borrow();
            pool.release(item);
        }
        double sum = pool.localHitRate() + pool.globalHitRate() + pool.newAllocRate();
        assertEquals(1.0, sum, 0.001, "sum of all hit rates should equal 1.0");
    }

    // ==================== Multi-thread concurrency safety ====================

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

        assertEquals(0, errors.get(), "concurrent ops should not throw");
        assertEquals((long) threads * opsPerThread, pool.getBorrowCount(), "borrowCount should be exact");
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
        assertEquals(0, nullCount.get(), "borrow() should never return null");
    }
}
