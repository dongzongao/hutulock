/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server.event;

import com.hutulock.spi.event.EventBus;
import com.hutulock.spi.event.EventListener;
import com.hutulock.spi.event.HutuEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件总线默认实现
 *
 * <p>基于发布-订阅模式，支持：
 * <ul>
 *   <li>按事件类型精确订阅（如 {@code LockEvent.class}）</li>
 *   <li>按父类订阅所有事件（如 {@code HutuEvent.class}）</li>
 *   <li>异步分发（后台线程池，不阻塞发布者）</li>
 *   <li>同步分发（{@link #publishSync}，用于测试）</li>
 *   <li>订阅者异常隔离（单个订阅者异常不影响其他订阅者）</li>
 * </ul>
 *
 * <p>线程模型：
 * <ul>
 *   <li>发布操作：将事件入队，立即返回（O(1)）</li>
 *   <li>分发线程：固定大小线程池，从队列取事件并分发给所有匹配订阅者</li>
 * </ul>
 *
 * <p>订阅者匹配规则：
 * <ul>
 *   <li>精确匹配：订阅 {@code LockEvent.class} 只收到 {@code LockEvent}</li>
 *   <li>父类匹配：订阅 {@code HutuEvent.class} 收到所有事件</li>
 * </ul>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 * @see EventBus
 */
public class DefaultEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(DefaultEventBus.class);

    /** 默认分发线程数 */
    private static final int DEFAULT_DISPATCHER_THREADS = 2;
    /** 事件队列容量 */
    private static final int QUEUE_CAPACITY = 10_000;

    /** eventType.getName() → 监听器列表 */
    private final Map<String, List<EventListener<HutuEvent>>> listeners = new ConcurrentHashMap<>();

    /** 异步事件队列 */
    private final BlockingQueue<HutuEvent> eventQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    /** 分发线程池 */
    private final ExecutorService dispatcher;

    /** 已发布事件总数（用于监控） */
    private final AtomicLong publishedCount = new AtomicLong(0);
    /** 已分发事件总数 */
    private final AtomicLong dispatchedCount = new AtomicLong(0);
    /** 分发失败次数 */
    private final AtomicLong failedCount = new AtomicLong(0);

    private volatile boolean running = true;

    /**
     * 使用默认配置创建事件总线（2 个分发线程）。
     */
    public DefaultEventBus() {
        this(DEFAULT_DISPATCHER_THREADS);
    }

    /**
     * 创建事件总线，指定分发线程数。
     *
     * @param dispatcherThreads 分发线程数，建议 1~4
     */
    public DefaultEventBus(int dispatcherThreads) {
        this.dispatcher = Executors.newFixedThreadPool(dispatcherThreads, r -> {
            Thread t = new Thread(r, "hutulock-eventbus-dispatcher");
            t.setDaemon(true);
            return t;
        });

        // 启动分发线程
        for (int i = 0; i < dispatcherThreads; i++) {
            dispatcher.submit(this::dispatchLoop);
        }

        log.info("DefaultEventBus started with {} dispatcher threads", dispatcherThreads);
    }

    // ==================== EventBus 接口实现 ====================

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HutuEvent> void subscribe(Class<T> eventType, EventListener<T> listener) {
        listeners.computeIfAbsent(eventType.getName(), k -> new CopyOnWriteArrayList<>())
                 .add((EventListener<HutuEvent>) listener);
        log.debug("Subscribed {} to {}", listener.getClass().getSimpleName(), eventType.getSimpleName());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HutuEvent> void unsubscribe(Class<T> eventType, EventListener<T> listener) {
        List<EventListener<HutuEvent>> list = listeners.get(eventType.getName());
        if (list != null) {
            list.remove(listener);
        }
    }

    @Override
    public void publish(HutuEvent event) {
        if (!running) {
            log.warn("EventBus is shutting down, dropping event: {}", event);
            return;
        }
        boolean offered = eventQueue.offer(event);
        if (offered) {
            publishedCount.incrementAndGet();
        } else {
            log.warn("EventBus queue full, dropping event: {}", event);
            failedCount.incrementAndGet();
        }
    }

    @Override
    public void publishSync(HutuEvent event) {
        dispatch(event);
    }

    @Override
    public void shutdown() {
        running = false;
        dispatcher.shutdown();
        try {
            if (!dispatcher.awaitTermination(5, TimeUnit.SECONDS)) {
                dispatcher.shutdownNow();
            }
        } catch (InterruptedException e) {
            dispatcher.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("DefaultEventBus shutdown. published={}, dispatched={}, failed={}",
            publishedCount.get(), dispatchedCount.get(), failedCount.get());
    }

    // ==================== 统计信息 ====================

    /** 获取已发布事件总数。 */
    public long getPublishedCount()  { return publishedCount.get();  }
    /** 获取已分发事件总数。 */
    public long getDispatchedCount() { return dispatchedCount.get(); }
    /** 获取分发失败次数。 */
    public long getFailedCount()     { return failedCount.get();     }
    /** 获取当前队列积压数量。 */
    public int  getQueueSize()       { return eventQueue.size();     }

    // ==================== 内部分发逻辑 ====================

    /** 分发线程主循环：从队列取事件并分发。 */
    private void dispatchLoop() {
        while (running || !eventQueue.isEmpty()) {
            try {
                HutuEvent event = eventQueue.poll(100, TimeUnit.MILLISECONDS);
                if (event != null) {
                    dispatch(event);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * 将事件分发给所有匹配的订阅者。
     *
     * <p>匹配规则：遍历事件的类继承链，找到所有注册了该类型（或父类型）的订阅者。
     */
    private void dispatch(HutuEvent event) {
        Class<?> clazz = event.getClass();

        // 遍历类继承链，支持父类订阅
        while (clazz != null && HutuEvent.class.isAssignableFrom(clazz)) {
            List<EventListener<HutuEvent>> list = listeners.get(clazz.getName());
            if (list != null) {
                for (EventListener<HutuEvent> listener : list) {
                    invokeListener(listener, event);
                }
            }
            clazz = clazz.getSuperclass();
        }

        dispatchedCount.incrementAndGet();
        log.trace("Dispatched event: {}", event);
    }

    /** 安全调用监听器，捕获并记录异常，不影响其他监听器。 */
    private void invokeListener(EventListener<HutuEvent> listener, HutuEvent event) {
        try {
            listener.onEvent(event);
        } catch (Exception e) {
            failedCount.incrementAndGet();
            log.error("EventListener {} threw exception while handling {}: {}",
                listener.getClass().getSimpleName(), event.getEventType(), e.getMessage(), e);
        }
    }
}
