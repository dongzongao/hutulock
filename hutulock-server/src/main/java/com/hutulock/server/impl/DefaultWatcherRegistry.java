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
package com.hutulock.server.impl;

import com.hutulock.model.watcher.WatchEvent;
import com.hutulock.model.znode.ZNodePath;
import com.hutulock.spi.metrics.MetricsCollector;
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

    /** path.value() → 注册了 Watcher 的 Channel 集合（One-shot，触发后自动注销） */
    private final Map<String, Set<Channel>> watchers = new ConcurrentHashMap<>();
    /**
     * path.value() → 持久 Watcher Channel 集合（参考 ZooKeeper 3.6 addWatch）。
     * 触发后不自动注销，适合需要持续监听的场景（如客户端监听锁队列整体变化）。
     */
    private final Map<String, Set<Channel>> persistentWatchers = new ConcurrentHashMap<>();
    /**
     * Channel ID → 该 Channel 监听的路径集合（反向索引）。
     * 将 removeChannel 从 O(全部路径) 降为 O(该 Channel 的 watcher 数)。
     */
    private final Map<String, Set<String>>  channelPaths = new ConcurrentHashMap<>();

    private final MetricsCollector metrics;

    public DefaultWatcherRegistry(MetricsCollector metrics) {
        this.metrics = metrics;
    }

    @Override
    public void register(ZNodePath path, Channel channel) {
        watchers.computeIfAbsent(path.value(), k -> ConcurrentHashMap.newKeySet())
                .add(channel);
        channelPaths.computeIfAbsent(channel.id().asShortText(), k -> ConcurrentHashMap.newKeySet())
                    .add(path.value());
        metrics.onWatcherRegistered();
        log.debug("Watcher registered: path={}, channel={}", path, channel.id().asShortText());
    }

    @Override
    public void registerPersistent(ZNodePath path, Channel channel) {
        persistentWatchers.computeIfAbsent(path.value(), k -> ConcurrentHashMap.newKeySet())
                          .add(channel);
        metrics.onWatcherRegistered();
        log.debug("Persistent watcher registered: path={}, channel={}", path, channel.id().asShortText());
    }

    @Override
    public void fire(ZNodePath path, WatchEvent.Type eventType) {
        WatchEvent event   = new WatchEvent(eventType, path);
        String     payload = event.serializeWithNewline();  // 预计算，无额外分配

        // One-shot watcher：remove 后推送，确保不重复触发
        Set<Channel> oneShot = watchers.remove(path.value());
        if (oneShot != null && !oneShot.isEmpty()) {
            log.debug("Firing one-shot watch event: {}, notifying {} watchers", event, oneShot.size());
            metrics.onWatcherFired();
            for (Channel ch : oneShot) {
                if (ch.isActive()) ch.writeAndFlush(payload);
                Set<String> paths = channelPaths.get(ch.id().asShortText());
                if (paths != null) paths.remove(path.value());
            }
        }

        // 持久 watcher：推送但不 remove
        Set<Channel> persistent = persistentWatchers.get(path.value());
        if (persistent != null && !persistent.isEmpty()) {
            log.debug("Firing persistent watch event: {}, notifying {} watchers", event, persistent.size());
            for (Channel ch : persistent) {
                if (ch.isActive()) ch.writeAndFlush(payload);
            }
        }
    }

    @Override
    public void removeChannel(Channel channel) {
        String channelId = channel.id().asShortText();
        // 清理 one-shot watcher 的反向索引
        Set<String> paths = channelPaths.remove(channelId);
        if (paths != null) {
            for (String p : paths) {
                Set<Channel> chs = watchers.get(p);
                if (chs != null) chs.remove(channel);
            }
        }
        // 清理持久 watcher（遍历所有路径，移除该 channel）
        // 注意：持久 watcher 没有反向索引，需全量扫描，但持久 watcher 数量通常很少
        persistentWatchers.values().forEach(set -> set.remove(channel));
        log.debug("Removed all watchers for channel: {}", channelId);
    }

    @Override
    public int watcherCount(ZNodePath path) {
        Set<Channel> set = watchers.get(path.value());
        return set == null ? 0 : set.size();
    }
}
