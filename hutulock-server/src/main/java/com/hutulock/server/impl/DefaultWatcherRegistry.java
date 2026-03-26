/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server.impl;

import com.hutulock.spi.metrics.MetricsCollector;
import com.hutulock.model.watcher.WatchEvent;
import com.hutulock.model.znode.ZNodePath;
import com.hutulock.spi.storage.WatcherRegistry;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watcher 注册表默认实现（内存存储）
 *
 * <p>维护 {@code path → Set<Channel>} 的映射。
 * 触发事件时向所有注册了该路径的 Channel 推送，并自动注销（One-shot）。
 *
 * <p>线程安全：使用 {@link ConcurrentHashMap} 保证并发安全。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 * @see WatcherRegistry
 */
public class DefaultWatcherRegistry implements WatcherRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultWatcherRegistry.class);

    /** path.value() → 注册了 Watcher 的 Channel 集合 */
    private final Map<String, Set<Channel>> watchers = new ConcurrentHashMap<>();

    private final MetricsCollector metrics;

    public DefaultWatcherRegistry(MetricsCollector metrics) {
        this.metrics = metrics;
    }

    @Override
    public void register(ZNodePath path, Channel channel) {
        watchers.computeIfAbsent(path.value(), k -> ConcurrentHashMap.newKeySet())
                .add(channel);
        metrics.onWatcherRegistered();
        log.debug("Watcher registered: path={}, channel={}", path, channel.id().asShortText());
    }

    @Override
    public void fire(ZNodePath path, WatchEvent.Type eventType) {
        // One-shot：remove 后推送，确保不重复触发
        Set<Channel> channels = watchers.remove(path.value());
        if (channels == null || channels.isEmpty()) return;

        WatchEvent event   = new WatchEvent(eventType, path);
        String     payload = event.serialize() + "\n";

        log.debug("Firing watch event: {}, notifying {} watchers", event, channels.size());
        metrics.onWatcherFired();

        for (Channel ch : channels) {
            if (ch.isActive()) {
                ch.writeAndFlush(payload);
            }
        }
    }

    @Override
    public void removeChannel(Channel channel) {
        watchers.values().forEach(set -> set.remove(channel));
    }

    @Override
    public int watcherCount(ZNodePath path) {
        Set<Channel> set = watchers.get(path.value());
        return set == null ? 0 : set.size();
    }
}
