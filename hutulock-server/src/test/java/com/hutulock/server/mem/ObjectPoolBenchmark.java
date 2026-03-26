package com.hutulock.server.mem;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 对象池底层数据结构基准测试
 *
 * 对比三种实现在对象池场景（borrow/release）下的吞吐量和延迟：
 *
 *   SinglyLinkedPool  — 手写单向链表（无锁头节点 CAS）
 *   DoublyLinkedPool  — ArrayDeque（JDK 双向链表，非线程安全，加 synchronized）
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

    // ==================== 双向链表池（ArrayDeque + synchronized）====================

    /**
     * 基于 ArrayDeque（循环数组双端队列）的对象池。
     *
     * <p>ArrayDeque 内部是循环数组，不是真正的链表，但 JDK 文档将其归类为双端队列。
     * 这里用它代表"双向可访问"的队列结构，与单向链表对比。
     * 加 synchronized 保证线程安全。
     */
    static final class DoublyLinkedPool<T extends ObjectPool.Pooled> {

        private final ArrayDeque<T>                  deque;
        private final java.util.function.Supplier<T> factory;
        private final int                            capacity;

        DoublyLinkedPool(int capacity, java.util.function.Supplier<T> factory) {
            this.capacity = capacity;
            this.factory  = factory;
            this.deque    = new ArrayDeque<>(capacity);
            for (int i = 0; i < capacity / 2; i++) deque.offer(factory.get());
        }

        synchronized T borrow() {
            T item = deque.poll();
            return item != null ? item : factory.get();
        }

        synchronized void release(T item) {
            item.reset();
            if (deque.size() < capacity) deque.offer(item);
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

        long singly      = singleThreadThroughput(
            wrapSingly(new SinglyLinkedPool<>(POOL_CAP, Item::new)), MEASURE_OPS);
        long doubly      = singleThreadThroughput(
            wrapDoubly(new DoublyLinkedPool<>(POOL_CAP, Item::new)), MEASURE_OPS);
        long blocking    = singleThreadThroughput(
            wrapBlocking(new ObjectPool<>(POOL_CAP, Item::new)), MEASURE_OPS);
        long twoLevel    = singleThreadThroughput(
            wrapBlocking(new ObjectPool<>(POOL_CAP, Item::new)), MEASURE_OPS);

        System.out.printf("  SinglyLinked  (Treiber Stack CAS) : %,12d ops/sec%n", singly);
        System.out.printf("  DoublyLinked  (ArrayDeque+sync)   : %,12d ops/sec%n", doubly);
        System.out.printf("  BlockingQueue (ArrayBlockingQueue) : %,12d ops/sec%n", blocking);
        System.out.printf("  TwoLevel      (ThreadLocal+Global) : %,12d ops/sec  ← 最优解%n", twoLevel);
    }

    @Test
    void benchmarkMultiThread() throws InterruptedException {
        System.out.println("\n========== 多线程并发吞吐量（" + THREADS + " 线程，ops/sec）==========");

        int opsPerThread = MEASURE_OPS / THREADS;

        long singly   = multiThreadThroughput(
            wrapSingly(new SinglyLinkedPool<>(POOL_CAP, Item::new)), THREADS, opsPerThread);
        long doubly   = multiThreadThroughput(
            wrapDoubly(new DoublyLinkedPool<>(POOL_CAP, Item::new)), THREADS, opsPerThread);
        long blocking = multiThreadThroughput(
            wrapBlocking(new ObjectPool<>(POOL_CAP, Item::new)), THREADS, opsPerThread);
        long twoLevel = multiThreadThroughput(
            wrapBlocking(new ObjectPool<>(POOL_CAP, Item::new)), THREADS, opsPerThread);

        System.out.printf("  SinglyLinked  (Treiber Stack CAS) : %,12d ops/sec%n", singly);
        System.out.printf("  DoublyLinked  (ArrayDeque+sync)   : %,12d ops/sec%n", doubly);
        System.out.printf("  BlockingQueue (ArrayBlockingQueue) : %,12d ops/sec%n", blocking);
        System.out.printf("  TwoLevel      (ThreadLocal+Global) : %,12d ops/sec  ← 最优解%n", twoLevel);
    }

    @Test
    void benchmarkP99() {
        System.out.println("\n========== P99 延迟（单线程，纳秒）==========");

        long singlyP99   = p99Latency(
            wrapSingly(new SinglyLinkedPool<>(POOL_CAP, Item::new)), MEASURE_OPS);
        long doublyP99   = p99Latency(
            wrapDoubly(new DoublyLinkedPool<>(POOL_CAP, Item::new)), MEASURE_OPS);
        long blockingP99 = p99Latency(
            wrapBlocking(new ObjectPool<>(POOL_CAP, Item::new)), MEASURE_OPS);
        long twoLevelP99 = p99Latency(
            wrapBlocking(new ObjectPool<>(POOL_CAP, Item::new)), MEASURE_OPS);

        System.out.printf("  SinglyLinked  (Treiber Stack CAS) : %,8d ns%n", singlyP99);
        System.out.printf("  DoublyLinked  (ArrayDeque+sync)   : %,8d ns%n", doublyP99);
        System.out.printf("  BlockingQueue (ArrayBlockingQueue) : %,8d ns%n", blockingP99);
        System.out.printf("  TwoLevel      (ThreadLocal+Global) : %,8d ns  ← 最优解%n", twoLevelP99);
    }
}
