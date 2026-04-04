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
package com.hutulock.server.fast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 批量写入队列（秒杀场景优化）
 *
 * <p>设计理念：
 * <ul>
 *   <li>批量窗口：写操作进入队列后等待固定时间窗口（默认 10ms）</li>
 *   <li>队列满触发：队列达到阈值（默认 100 条）时立即 flush</li>
 *   <li>异步 flush：批量提交到 Raft，不阻塞入队线程</li>
 * </ul>
 *
 * <p>性能分析：
 * <ul>
 *   <li>批量大小 100，窗口 10ms → 理论吞吐 10万 QPS</li>
 *   <li>P99 延迟：10ms（窗口）+ 30ms（Raft 复制）= 40ms</li>
 *   <li>内存占用：100 条 × 200 字节 = 20KB（可忽略）</li>
 * </ul>
 *
 * @author HutuLock Authors
 * @since 1.1.0
 */
public class WriteBatchQueue implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WriteBatchQueue.class);

    /** 批量写入请求 */
    public static class WriteRequest {
        public final String                    command;
        public final CompletableFuture<String> future;

        public WriteRequest(String command, CompletableFuture<String> future) {
            this.command = command;
            this.future  = future;
        }
    }

    private final int                      batchSize;
    private final long                     flushIntervalMs;
    private final Consumer<List<WriteRequest>> flushHandler;

    private final BlockingQueue<WriteRequest> queue;
    private final ScheduledExecutorService    scheduler;
    private final ExecutorService             flushExecutor;

    private volatile boolean running = true;

    /**
     * 构造批量队列。
     *
     * @param batchSize       批量大小（队列满时触发 flush）
     * @param flushIntervalMs 批量窗口（毫秒，定时触发 flush）
     * @param flushHandler    flush 回调（接收批量请求列表）
     */
    public WriteBatchQueue(int batchSize, long flushIntervalMs,
                            Consumer<List<WriteRequest>> flushHandler) {
        this.batchSize       = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.flushHandler    = flushHandler;
        this.queue           = new LinkedBlockingQueue<>();

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "write-batch-scheduler");
            t.setDaemon(true);
            return t;
        });

        this.flushExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "write-batch-flusher");
            t.setDaemon(true);
            return t;
        });

        // 定时 flush（批量窗口）
        scheduler.scheduleAtFixedRate(this::tryFlush, flushIntervalMs, flushIntervalMs,
            TimeUnit.MILLISECONDS);
    }

    /**
     * 提交写请求到批量队列。
     *
     * @param command 写命令（如 "LOCK:order-123:session-456"）
     * @return CompletableFuture，flush 后完成
     */
    public CompletableFuture<String> submit(String command) {
        if (!running) {
            CompletableFuture<String> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("WriteBatchQueue is closed"));
            return f;
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        WriteRequest req = new WriteRequest(command, future);

        try {
            queue.put(req);
            // 队列满时立即触发 flush
            if (queue.size() >= batchSize) {
                tryFlush();
            }
        } catch (InterruptedException e) {
            future.completeExceptionally(e);
            Thread.currentThread().interrupt();
        }

        return future;
    }

    /**
     * 尝试 flush 队列（非阻塞）。
     */
    private void tryFlush() {
        if (queue.isEmpty()) return;

        List<WriteRequest> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);

        if (batch.isEmpty()) return;

        // 异步 flush，避免阻塞定时器线程
        flushExecutor.submit(() -> {
            try {
                flushHandler.accept(batch);
                log.debug("Flushed {} write requests", batch.size());
            } catch (Exception e) {
                log.error("Flush failed: {}", e.getMessage(), e);
                // 失败时通知所有 future
                batch.forEach(req -> req.future.completeExceptionally(e));
            }
        });
    }

    /**
     * 强制 flush 所有待处理请求（关闭前调用）。
     */
    public void flushAll() {
        while (!queue.isEmpty()) {
            tryFlush();
        }
    }

    @Override
    public void close() {
        running = false;
        flushAll();

        scheduler.shutdown();
        flushExecutor.shutdown();

        try {
            if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            if (!flushExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                flushExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            flushExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log.info("WriteBatchQueue closed");
    }
}
