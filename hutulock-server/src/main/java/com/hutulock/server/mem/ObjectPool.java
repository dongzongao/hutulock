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

import java.util.ArrayDeque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import com.hutulock.model.util.Numbers;

/**
 * Two-level object pool (Thread-Local local pool + global shared pool).
 *
 * @param <T> pooled object type, must implement {@link Pooled}
 */
public final class ObjectPool<T extends ObjectPool.Pooled> {

    private static final int LOCAL_MAX = Numbers.POOL_LOCAL_MAX;
    private static final int BATCH     = Numbers.POOL_BATCH;

    private final ArrayBlockingQueue<T> global;
    private final Supplier<T>           factory;
    private final int                   globalCapacity;

    private final ThreadLocal<ArrayDeque<T>> local =
        ThreadLocal.withInitial(() -> new ArrayDeque<>(LOCAL_MAX));

    private final LongAdder borrowCount = new LongAdder();
    private final LongAdder localHits   = new LongAdder();
    private final LongAdder globalHits  = new LongAdder();
    private final LongAdder newAllocs   = new LongAdder();

    public ObjectPool(int globalCapacity, Supplier<T> factory) {
        this.globalCapacity = globalCapacity;
        this.global  = new ArrayBlockingQueue<>(Math.max(1, globalCapacity));
        this.factory = factory;
        for (int i = 0; i < globalCapacity / 2; i++) {
            global.offer(factory.get());
        }
    }

    public T borrow() {
        borrowCount.increment();
        ArrayDeque<T> localPool = local.get();

        T obj = localPool.poll();
        if (obj != null) {
            localHits.increment();
            return obj;
        }

        T item = global.poll();
        if (item != null) {
            globalHits.increment();
            return item;
        }

        newAllocs.increment();
        return factory.get();
    }

    public void release(T obj) {
        obj.reset();
        ArrayDeque<T> localPool = local.get();

        if (localPool.size() < LOCAL_MAX) {
            localPool.offer(obj);
            return;
        }

        for (int i = 0; i < BATCH && !localPool.isEmpty(); i++) {
            T item = localPool.poll();
            if (!global.offer(item)) break;
        }
        localPool.offer(obj);
    }

    public double localHitRate() {
        long total = borrowCount.sum();
        return total == 0 ? 0.0 : (double) localHits.sum() / total;
    }

    public double globalHitRate() {
        long total = borrowCount.sum();
        return total == 0 ? 0.0 : (double) globalHits.sum() / total;
    }

    public double newAllocRate() {
        long total = borrowCount.sum();
        return total == 0 ? 0.0 : (double) newAllocs.sum() / total;
    }

    public long getBorrowCount() { return borrowCount.sum(); }
    public long getLocalHits()   { return localHits.sum();   }
    public long getGlobalHits()  { return globalHits.sum();  }
    public long getNewAllocs()   { return newAllocs.sum();   }
    public int  globalPoolSize() { return global.size();     }

    public interface Pooled {
        void reset();
    }
}
