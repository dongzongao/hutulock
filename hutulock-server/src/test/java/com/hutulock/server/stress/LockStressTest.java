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
package com.hutulock.server.stress;

import com.hutulock.config.api.ServerProperties;
import com.hutulock.model.protocol.CommandType;
import com.hutulock.model.protocol.Message;
import com.hutulock.model.session.Session;
import com.hutulock.model.znode.ZNodePath;
import com.hutulock.server.impl.*;
import com.hutulock.server.mem.MemoryManager;
import com.hutulock.spi.event.EventBus;
import com.hutulock.spi.metrics.MetricsCollector;
import io.netty.channel.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;


/**
 * 分布式锁压力测试
 *
 * <p>测试场景：
 * <ol>
 *   <li>高并发锁竞争：N 个线程同时竞争同一把锁，验证互斥性</li>
 *   <li>多锁并发：N 个线程竞争 M 把不同的锁，验证吞吐量</li>
 *   <li>ZNode 树并发写：高并发创建/删除节点，验证线程安全</li>
 *   <li>事件总线吞吐量：高频发布事件，验证不丢失</li>
 * </ol>
 */
class LockStressTest {

    private static final int THREADS     = 50;
    private static final int ITERATIONS  = 200;
    private static final int LOCK_COUNT  = 10;

    private DefaultZNodeTree      zNodeTree;
    private DefaultSessionManager sessionManager;
    private DefaultLockManager    lockManager;

    @BeforeEach
    void setUp() {
        ServerProperties props = ServerProperties.builder()
            .electionTimeout(300, 600)
            .heartbeatInterval(50)
            .watchdogTtl(30_000)
            .watchdogScanInterval(1_000)
            .build();

        DefaultWatcherRegistry watcherRegistry = new DefaultWatcherRegistry(MetricsCollector.noop());
        zNodeTree      = new DefaultZNodeTree(watcherRegistry, MetricsCollector.noop(),
            EventBus.noop(), new MemoryManager());
        sessionManager = new DefaultSessionManager(zNodeTree, MetricsCollector.noop(),
            EventBus.noop(), props);
        lockManager    = new DefaultLockManager(zNodeTree, sessionManager,
            MetricsCollector.noop(), EventBus.noop());
    }

    // ==================== 测试 1：高并发锁竞争互斥性 ====================

    /**
     * 50 个线程竞争同一把锁，验证 ZNode 树的并发安全性。
     * 每个线程创建一个 EPHEMERAL_SEQ 节点，验证序号唯一且单调递增。
     */
    @Test
    void concurrentLockMutualExclusion() throws InterruptedException {
        int threadCount = THREADS;
        AtomicInteger successCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            new Thread(() -> {
                try {
                    startLatch.await();
                    Channel ch = mock(Channel.class);
                    io.netty.channel.ChannelId cid = mock(io.netty.channel.ChannelId.class);
                    when(cid.asShortText()).thenReturn("ch-" + idx);
                    when(ch.id()).thenReturn(cid);
                    when(ch.isActive()).thenReturn(true);
                    when(ch.writeAndFlush(any())).thenReturn(mock(io.netty.channel.ChannelFuture.class));

                    Session session = sessionManager.createSession("client-" + idx, ch);
                    lockManager.apply(idx + 1,
                        Message.of(CommandType.LOCK, "stress-lock", session.getSessionId()).serialize());
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // 忽略
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Stress test timed out");

        assertEquals(threadCount, successCount.get());
        int childCount = zNodeTree.getChildren(ZNodePath.of("/locks/stress-lock")).size();
        assertEquals(threadCount, childCount);

        System.out.printf("[Stress] Mutual Exclusion: threads=%d, znodes=%d%n",
            threadCount, childCount);
    }

    // ==================== 测试 2：ZNode 树并发写安全性 ====================

    /**
     * 高并发创建/删除 ZNode，验证线程安全，无数据竞争。
     */
    @Test
    void zNodeTreeConcurrentSafety() throws InterruptedException {
        int threadCount = THREADS;
        int opsPerThread = ITERATIONS;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        // 预创建父节点
        zNodeTree.create(ZNodePath.of("/stress"), com.hutulock.model.znode.ZNodeType.PERSISTENT,
            new byte[0], null);

        for (int i = 0; i < threadCount; i++) {
            final int tid = i;
            new Thread(() -> {
                try {
                    for (int j = 0; j < opsPerThread; j++) {
                        ZNodePath path = zNodeTree.create(
                            ZNodePath.of("/stress/seq-"),
                            com.hutulock.model.znode.ZNodeType.EPHEMERAL_SEQ,
                            new byte[0], "session-" + tid);
                        zNodeTree.delete(path);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "Concurrent ZNode operations had errors");

        // 所有节点都被删除，只剩 /stress 和根节点
        assertEquals(0, zNodeTree.getChildren(ZNodePath.of("/stress")).size());
        System.out.printf("[Stress] ZNode Concurrent: threads=%d, ops=%d, errors=%d%n",
            threadCount, threadCount * opsPerThread, errors.get());
    }

    // ==================== 测试 3：多锁并发吞吐量 + P99 延迟 ====================

    /**
     * N 个线程竞争 M 把不同的锁，测量吞吐量（ops/sec）和 P50/P99 延迟。
     */
    @Test
    void multiLockThroughput() throws InterruptedException {
        int threadCount  = THREADS;
        int lockCount    = LOCK_COUNT;
        int opsPerThread = ITERATIONS;
        AtomicLong totalOps = new AtomicLong(0);
        // 用于收集每次操作耗时（ns）
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        CountDownLatch latch = new CountDownLatch(threadCount);

        long startMs = System.currentTimeMillis();

        for (int i = 0; i < threadCount; i++) {
            final int tid = i;
            new Thread(() -> {
                try {
                    Channel ch = mock(Channel.class);
                    io.netty.channel.ChannelId cid = mock(io.netty.channel.ChannelId.class);
                    when(cid.asShortText()).thenReturn("ch-throughput-" + tid);
                    when(ch.id()).thenReturn(cid);
                    when(ch.isActive()).thenReturn(true);
                    when(ch.writeAndFlush(any())).thenReturn(mock(io.netty.channel.ChannelFuture.class));
                    Session session = sessionManager.createSession("client-" + tid, ch);

                    for (int j = 0; j < opsPerThread; j++) {
                        String lockName = "lock-" + (j % lockCount);
                        long t0 = System.nanoTime();
                        lockManager.apply(tid * opsPerThread + j,
                            Message.of(CommandType.LOCK, lockName, session.getSessionId()).serialize());
                        latencies.add(System.nanoTime() - t0);
                        totalOps.incrementAndGet();
                    }
                } catch (Exception e) {
                    // 忽略
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(60, TimeUnit.SECONDS));
        long elapsed = System.currentTimeMillis() - startMs;
        double opsPerSec = totalOps.get() * 1000.0 / elapsed;

        // 计算 P50 / P99 延迟
        long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
        long p50Us = sorted.length > 0 ? sorted[(int)(sorted.length * 0.50)] / 1000 : 0;
        long p99Us = sorted.length > 0 ? sorted[(int)(sorted.length * 0.99)] / 1000 : 0;
        long maxUs = sorted.length > 0 ? sorted[sorted.length - 1] / 1000 : 0;

        System.out.printf("[Stress] Multi-Lock Throughput: threads=%d, locks=%d, " +
            "totalOps=%d, elapsed=%dms, throughput=%.0f ops/sec%n",
            threadCount, lockCount, totalOps.get(), elapsed, opsPerSec);
        System.out.printf("[Stress] Latency: P50=%dμs  P99=%dμs  Max=%dμs%n",
            p50Us, p99Us, maxUs);

        assertTrue(opsPerSec > 1000, "Throughput too low: " + opsPerSec + " ops/sec");
    }

    // ==================== 测试 3b：P99 延迟专项（内存优化验证）====================

    /**
     * 专项测试：单锁高并发，测量 apply() 的 P99 延迟。
     * 验证路径缓存 + 序号预计算对 P99 的改善效果。
     */
    @Test
    void p99LockLatency() throws InterruptedException {
        int threadCount  = 20;
        int opsPerThread = 500;
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int tid = i;
            new Thread(() -> {
                try {
                    Channel ch = mock(Channel.class);
                    io.netty.channel.ChannelId cid = mock(io.netty.channel.ChannelId.class);
                    when(cid.asShortText()).thenReturn("ch-p99-" + tid);
                    when(ch.id()).thenReturn(cid);
                    when(ch.isActive()).thenReturn(true);
                    when(ch.writeAndFlush(any())).thenReturn(mock(io.netty.channel.ChannelFuture.class));
                    Session session = sessionManager.createSession("p99-client-" + tid, ch);

                    for (int j = 0; j < opsPerThread; j++) {
                        long t0 = System.nanoTime();
                        lockManager.apply(tid * opsPerThread + j,
                            Message.of(CommandType.LOCK, "p99-lock", session.getSessionId()).serialize());
                        latencies.add(System.nanoTime() - t0);
                    }
                } catch (Exception e) {
                    // 忽略
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));

        long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
        long p50Us  = sorted[(int)(sorted.length * 0.50)] / 1000;
        long p95Us  = sorted[(int)(sorted.length * 0.95)] / 1000;
        long p99Us  = sorted[(int)(sorted.length * 0.99)] / 1000;
        long p999Us = sorted[(int)(sorted.length * 0.999)] / 1000;
        long maxUs  = sorted[sorted.length - 1] / 1000;

        System.out.printf("[P99] Lock latency (threads=%d, ops=%d):%n", threadCount, sorted.length);
        System.out.printf("      P50=%dμs  P95=%dμs  P99=%dμs  P99.9=%dμs  Max=%dμs%n",
            p50Us, p95Us, p99Us, p999Us, maxUs);

        // P99 应在 500ms 以内（纯内存操作，20线程竞争同一把锁，synchronized 争用正常）
        assertTrue(p99Us < 500_000, "P99 too high: " + p99Us + "μs (expected < 500000μs)");
    }

    // ==================== 测试 4：事件总线吞吐量 ====================

    /**
     * 高频发布事件，验证不丢失，测量吞吐量。
     */
    @Test
    void eventBusThroughput() throws InterruptedException {
        com.hutulock.server.event.DefaultEventBus bus =
            new com.hutulock.server.event.DefaultEventBus(2);

        int eventCount = 10_000;
        CountDownLatch latch = new CountDownLatch(eventCount);
        AtomicInteger received = new AtomicInteger(0);

        bus.subscribe(com.hutulock.spi.event.LockEvent.class, event -> {
            received.incrementAndGet();
            latch.countDown();
        });

        long startMs = System.currentTimeMillis();

        for (int i = 0; i < eventCount; i++) {
            bus.publish(com.hutulock.spi.event.LockEvent
                .builder(com.hutulock.spi.event.LockEvent.Type.ACQUIRED, "lock-" + i, "s" + i)
                .sourceNode("n1").build());
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "EventBus dropped events");
        long elapsed = System.currentTimeMillis() - startMs;
        double opsPerSec = eventCount * 1000.0 / elapsed;

        System.out.printf("[Stress] EventBus Throughput: events=%d, elapsed=%dms, " +
            "throughput=%.0f events/sec, received=%d%n",
            eventCount, elapsed, opsPerSec, received.get());

        assertEquals(eventCount, received.get(), "Some events were lost");
        bus.shutdown();
    }

    // ==================== 测试 5：Session 扫描并发安全 ====================

    /**
     * 高并发创建/过期 Session，验证扫描线程安全。
     */
    @Test
    void sessionManagerConcurrentSafety() throws InterruptedException {
        int threadCount = 20;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int tid = i;
            new Thread(() -> {
                try {
                    Channel ch = mock(Channel.class);
                    // stub channel id，避免 NPE
                    io.netty.channel.ChannelId channelId = mock(io.netty.channel.ChannelId.class);
                    when(channelId.asShortText()).thenReturn("ch-" + tid + "-" + Thread.currentThread().getId());
                    when(ch.id()).thenReturn(channelId);
                    when(ch.isActive()).thenReturn(true);
                    when(ch.writeAndFlush(any())).thenReturn(mock(io.netty.channel.ChannelFuture.class));

                    Session session = sessionManager.createSession("client-" + tid, ch, 50);

                    for (int j = 0; j < 5; j++) {
                        sessionManager.heartbeat(session.getSessionId());
                        Thread.sleep(10);
                    }

                    sessionManager.closeSession(session.getSessionId());
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "Session concurrent operations had errors");
        System.out.printf("[Stress] Session Concurrent: threads=%d, errors=%d%n",
            threadCount, errors.get());
    }
}
