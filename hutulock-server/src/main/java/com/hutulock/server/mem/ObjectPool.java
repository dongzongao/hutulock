/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server.mem;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Supplier;

/**
 * 轻量级对象池
 *
 * <p>通过预分配 + 归还机制减少短生命周期对象的 GC 压力。
 * 适用于高频创建/销毁、结构固定的值对象（如 {@code LockToken}）。
 *
 * <p>设计原则：
 * <ul>
 *   <li>池满时直接 new，不阻塞（降级为普通分配）</li>
 *   <li>归还前必须调用 {@link Pooled#reset()} 清理状态</li>
 *   <li>线程安全：基于 {@link ArrayBlockingQueue}</li>
 * </ul>
 *
 * @param <T> 池化对象类型，必须实现 {@link Pooled}
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class ObjectPool<T extends ObjectPool.Pooled> {

    private final ArrayBlockingQueue<T> pool;
    private final Supplier<T>           factory;

    /** 统计：总借出次数 */
    private long borrowCount;
    /** 统计：命中池次数（未 new） */
    private long hitCount;

    public ObjectPool(int capacity, Supplier<T> factory) {
        this.pool    = new ArrayBlockingQueue<>(capacity);
        this.factory = factory;
        // 预热：预分配一半容量
        for (int i = 0; i < capacity / 2; i++) {
            pool.offer(factory.get());
        }
    }

    /**
     * 借出对象。池空时直接 new，不阻塞。
     */
    public T borrow() {
        borrowCount++;
        T obj = pool.poll();
        if (obj != null) {
            hitCount++;
            return obj;
        }
        return factory.get();
    }

    /**
     * 归还对象。归还前自动调用 {@link Pooled#reset()}。
     * 池满时直接丢弃（让 GC 回收）。
     */
    public void release(T obj) {
        obj.reset();
        pool.offer(obj); // 池满时 offer 返回 false，对象被 GC
    }

    /** 返回命中率（0.0 ~ 1.0）。 */
    public double hitRate() {
        return borrowCount == 0 ? 0.0 : (double) hitCount / borrowCount;
    }

    public long getBorrowCount() { return borrowCount; }
    public long getHitCount()    { return hitCount;    }
    public int  poolSize()       { return pool.size(); }

    /**
     * 池化对象必须实现的接口：归还前重置状态。
     */
    public interface Pooled {
        /** 清理对象状态，使其可被复用。 */
        void reset();
    }
}
