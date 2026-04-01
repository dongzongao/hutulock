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

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.PriorityQueue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 对象池底层数据结构基准测试
 *
 * 对比四种实现在对象池场景（borrow/release）下的吞吐量和延迟：
 *
 *   SinglyLinkedPool  — 手写单向链表（无锁头节点 CAS，Treiber Stack）
 *   DoublyLinkedPool  — 手写双向链表（head/tail 指针，synchronized）
 *   BinaryHeapPool    — 二叉堆（PriorityQueue，O(log n)，synchronized）
 *   BlockingQueuePool — ArrayBlockingQueue（当前实现，线程安全）
 *
 * 测试维度：
 *   1. 单线程吞吐量（无竞争基线）
 *   2. 多线程并发吞吐量（16 线程）
 *   3. P99 延迟（单线程 100 万次操作）
 */
class ObjectPoolBenchmark {

    private static final int WARMUP_OPS   = 100_000;
    private static final int MEASURE_OPS  = 1_000_000;
    private static final int THREADS      = 16;
    private static final int POOL_CAP     = 512;

    // ==================== 池化对象 ====================

    static final class Item implements ObjectPool.Pooled {
        int value;
        @Override public void reset() { value = 0; }
    }

    // ==================== 单向链表池（Treiber Stack，无锁）====================

    /**
     * 基于 Treiber Stack 的无锁单向链表对象池。
     *
     * <p>原理：用 AtomicReference 指向栈顶节点，CAS 实现无锁 push/pop。
     * 单向链表：每个节点只有 next 指针，内存占用最小。
     */
    static final class SinglyLinkedPool<T extends ObjectPool.Pooled> {

        private static final class Node<T> {
            final T    item;
            Node<T>    next;
            Node(T item) { this.item = item; }
        }

        private final java.util.concurrent.atomic.AtomicReference<Node<T>> head =
            new java.util.concurrent.atomic.AtomicReference<>(null);
        private final java.util.function.Supplier<T> factory;
        private final java.util.concurrent.atomic.AtomicInteger size =
            new java.util.concurrent.atomic.AtomicInteger(0);
        private final int capacity;

        SinglyLinkedPool(int capacity, java.util.function.Supplier<T> factory) {
            this.capacity = capacity;
            this.factory  = factory;
            // 预热
            for (int i = 0; i < capacity / 2; i++) push(factory.get());
        }

        T borrow() {
            Node<T> top;
            do {
                top = head.get();
                if (top == null) return factory.get(); // 池空，直接 new
            } while (!head.compareAndSet(top, top.next));
            size.decrementAndGet();
            return top.item;
        }

        void release(T item) {
            item.reset();
            if (size.get() >= capacity) return; // 池满，丢弃
            Node<T> node = new Node<>(item);
            do {
                node.next = head.get();
            } while (!head.compareAndSet(node.next, node));
            size.incrementAndGet();
        }

        private void push(T item) {
            Node<T> node = new Node<>(item);
            do { node.next = head.get(); } while (!head.compareAndSet(node.next, node));
            size.incrementAndGet();
        }
    }

    // ==================== 双向链表池（手写 DLL + synchronized）====================

    /**
     * 基于手写双向链表的对象池。
     *
     * <p>每个节点持有 prev/next 两个指针，borrow 从 head 摘取，
     * release 追加到 tail，O(1) 操作。加 synchronized 保证线程安全。
     */
    static final class DoublyLinkedPool<T extends ObjectPool.Pooled> {

        private static final class Node<T> {
            T       item;
            Node<T> prev;
            Node<T> next;
            Node(T item) { this.item = item; }
        }

        private Node<T>                              head;     // 哨兵头
        private Node<T>                              tail;     // 哨兵尾
        private int                                  size;
        private final int                            capacity;
        private final java.util.function.Supplier<T> factory;

        DoublyLinkedPool(int capacity, java.util.function.Supplier<T> factory) {
            this.capacity = capacity;
            this.factory  = factory;
            // 初始化哨兵节点
            head = new Node<>(null);
            tail = new Node<>(null);
            head.next = tail;
            tail.prev = head;
            // 预热
            for (int i = 0; i < capacity / 2; i++) linkTail(factory.get());
        }

        /** 在 tail 哨兵前插入节点（O(1)）*/
        private void linkTail(T item) {
            Node<T> node = new Node<>(item);
            node.prev       = tail.prev;
            node.next       = tail;
            tail.prev.next  = node;
            tail.prev       = node;
            size++;
        }

        /** 摘除 head 哨兵后第一个节点（O(1)）*/
        private T unlinkHead() {
            if (head.next == tail) return null;
            Node<T> node = head.next;
            head.next       = node.next;
            node.next.prev  = head;
            node.prev       = null;
            node.next       = null;
            size--;
            return node.item;
        }

        synchronized T borrow() {
            T item = unlinkHead();
            return item != null ? item : factory.get();
        }

        synchronized void release(T item) {
            item.reset();
            if (size < capacity) linkTail(item);
        }
    }

    // ==================== 二叉堆池（PriorityQueue + synchronized）====================

    /**
     * 基于二叉堆（最小堆）的对象池。
     *
     * <p>用对象的 identityHashCode 作为优先级键，borrow/release 均为 O(log n)。
     * 主要用于对比：堆的 sift-up/sift-down 开销 vs 链表的 O(1) 指针操作。
     */
    static final class BinaryHeapPool<T extends ObjectPool.Pooled> {

        private final PriorityQueue<T>               heap;
        private final int                            capacity;
        private final java.util.function.Supplier<T> factory;

        @SuppressWarnings("unchecked")
        BinaryHeapPool(int capacity, java.util.function.Supplier<T> factory) {
            this.capacity = capacity;
            this.factory  = factory;
            // 用 identityHashCode 作为自然排序键（需要 Comparable 包装，这里直接用 comparator）
            this.heap = new PriorityQueue<>(Math.max(1, capacity),
                (a, b) -> Integer.compare(
                    System.identityHashCode(a), System.identityHashCode(b)));
            for (int i = 0; i < capacity / 2; i++) heap.offer(factory.get());
        }

        synchronized T borrow() {
            T item = heap.poll();   // O(log n) sift-down
            return item != null ? item : factory.get();
        }

        synchronized void release(T item) {
            item.reset();
            if (heap.size() < capacity) heap.offer(item); // O(log n) sift-up
        }
    }

    // ==================== 基准测试工具 ====================

    interface Pool<T extends ObjectPool.Pooled> {
        T borrow();
        void release(T item);
    }

    static Pool<Item> wrapSingly(SinglyLinkedPool<Item> p) {
        return new Pool<Item>() {
            public Item borrow()        { return p.borrow();  }
            public void release(Item i) { p.release(i);       }
        };
    }

    static Pool<Item> wrapDoubly(DoublyLinkedPool<Item> p) {
        return new Pool<Item>() {
            public Item borrow()        { return p.borrow();  }
            public void release(Item i) { p.release(i);       }
        };
    }

    static Pool<Item> wrapHeap(BinaryHeapPool<Item> p) {
        return new Pool<Item>() {
            public Item borrow()        { return p.borrow();  }
            public void release(Item i) { p.release(i);       }
        };
    }

    static Pool<Item> wrapBlocking(ObjectPool<Item> p) {
        return new Pool<Item>() {
            public Item borrow()        { return p.borrow();  }
            public void release(Item i) { p.release(i);       }
        };
    }

    /** 单线程吞吐量（ops/sec）*/
    static long singleThreadThroughput(Pool<Item> pool, int ops) {
        // 预热
        for (int i = 0; i < WARMUP_OPS; i++) {
            Item item = pool.borrow();
            item.value = i;
            pool.release(item);
        }
        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            Item item = pool.borrow();
            item.value = i;
            pool.release(item);
        }
        long elapsed = System.nanoTime() - start;
        return (long)(ops * 1_000_000_000.0 / elapsed);
    }

    /** 多线程并发吞吐量（ops/sec）*/
    static long multiThreadThroughput(Pool<Item> pool, int threads, int opsPerThread)
            throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threads);
        AtomicLong total = new AtomicLong(0);

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException e) { return; }
                long count = 0;
                for (int i = 0; i < opsPerThread; i++) {
                    Item item = pool.borrow();
                    item.value = i;
                    pool.release(item);
                    count++;
                }
                total.addAndGet(count);
                done.countDown();
            }).start();
        }

        ready.await();
        long t0 = System.nanoTime();
        start.countDown();
        done.await();
        long elapsed = System.nanoTime() - t0;
        return (long)(total.get() * 1_000_000_000.0 / elapsed);
    }

    /** P99 延迟（纳秒）*/
    static long p99Latency(Pool<Item> pool, int ops) {
        // 预热
        for (int i = 0; i < WARMUP_OPS; i++) {
            Item item = pool.borrow(); item.value = i; pool.release(item);
        }
        long[] latencies = new long[ops];
        for (int i = 0; i < ops; i++) {
            long t0   = System.nanoTime();
            Item item = pool.borrow();
            item.value = i;
            pool.release(item);
            latencies[i] = System.nanoTime() - t0;
        }
        java.util.Arrays.sort(latencies);
        return latencies[(int)(ops * 0.99)];
    }

    // ==================== 测试入口 ====================

    @Test
    void benchmarkSingleThread() {
        System.out.println("\n========== 单线程吞吐量（ops/sec）==========");

        long singly   = singleThreadThroughput(
            wrapSingly(new SinglyLinkedPool<>(POOL_CAP, Item::new)), MEASURE_OPS);
        long doubly   = singleThreadThroughput(
            wrapDoubly(new DoublyLinkedPool<>(POOL_CAP, Item::new)), MEASURE_OPS);
        long heap     = singleThreadThroughput(
            wrapHeap(new BinaryHeapPool<>(POOL_CAP, Item::new)), MEASURE_OPS);
        long twoLevel = singleThreadThroughput(
            wrapBlocking(new ObjectPool<>(POOL_CAP, Item::new)), MEASURE_OPS);

        System.out.printf("  SinglyLinked  (Treiber Stack CAS)  : %,12d ops/sec%n", singly);
        System.out.printf("  DoublyLinked  (DLL head/tail+sync) : %,12d ops/sec%n", doubly);
        System.out.printf("  BinaryHeap    (PriorityQueue+sync) : %,12d ops/sec%n", heap);
        System.out.printf("  TwoLevel      (ThreadLocal+Global) : %,12d ops/sec  ← 当前实现%n", twoLevel);
    }

    @Test
    void benchmarkMultiThread() throws InterruptedException {
        System.out.println("\n========== 多线程并发吞吐量（" + THREADS + " 线程，ops/sec）==========");

        int opsPerThread = MEASURE_OPS / THREADS;

        long singly   = multiThreadThroughput(
            wrapSingly(new SinglyLinkedPool<>(POOL_CAP, Item::new)), THREADS, opsPerThread);
        long doubly   = multiThreadThroughput(
            wrapDoubly(new DoublyLinkedPool<>(POOL_CAP, Item::new)), THREADS, opsPerThread);
        long heap     = multiThreadThroughput(
            wrapHeap(new BinaryHeapPool<>(POOL_CAP, Item::new)), THREADS, opsPerThread);
        long twoLevel = multiThreadThroughput(
            wrapBlocking(new ObjectPool<>(POOL_CAP, Item::new)), THREADS, opsPerThread);

        System.out.printf("  SinglyLinked  (Treiber Stack CAS)  : %,12d ops/sec%n", singly);
        System.out.printf("  DoublyLinked  (DLL head/tail+sync) : %,12d ops/sec%n", doubly);
        System.out.printf("  BinaryHeap    (PriorityQueue+sync) : %,12d ops/sec%n", heap);
        System.out.printf("  TwoLevel      (ThreadLocal+Global) : %,12d ops/sec  ← 当前实现%n", twoLevel);
    }

    @Test
    void benchmarkP99() {
        System.out.println("\n========== P99 延迟（单线程，纳秒）==========");

        long singlyP99   = p99Latency(
            wrapSingly(new SinglyLinkedPool<>(POOL_CAP, Item::new)), MEASURE_OPS);
        long doublyP99   = p99Latency(
            wrapDoubly(new DoublyLinkedPool<>(POOL_CAP, Item::new)), MEASURE_OPS);
        long heapP99     = p99Latency(
            wrapHeap(new BinaryHeapPool<>(POOL_CAP, Item::new)), MEASURE_OPS);
        long twoLevelP99 = p99Latency(
            wrapBlocking(new ObjectPool<>(POOL_CAP, Item::new)), MEASURE_OPS);

        System.out.printf("  SinglyLinked  (Treiber Stack CAS)  : %,8d ns%n", singlyP99);
        System.out.printf("  DoublyLinked  (DLL head/tail+sync) : %,8d ns%n", doublyP99);
        System.out.printf("  BinaryHeap    (PriorityQueue+sync) : %,8d ns%n", heapP99);
        System.out.printf("  TwoLevel      (ThreadLocal+Global) : %,8d ns  ← 当前实现%n", twoLevelP99);
    }
}
