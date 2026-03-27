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
package com.hutulock.spi.storage;

import com.hutulock.model.watcher.WatchEvent;
import com.hutulock.model.znode.ZNodePath;
import io.netty.channel.Channel;

/**
 * Watcher 注册表接口（SPI 边界契约）
 *
 * <p>维护 path → Channel 的映射。
 * 默认 Watcher 是一次性的（One-shot）：触发后自动注销。
 * 持久 Watcher（{@link #registerPersistent}）触发后不注销，参考 ZooKeeper 3.6 addWatch。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public interface WatcherRegistry {

    /** 注册 One-shot Watcher，触发一次后自动注销。 */
    void register(ZNodePath path, Channel channel);

    /**
     * 注册持久 Watcher，触发后不自动注销（参考 ZooKeeper 3.6 addWatch PERSISTENT 模式）。
     * 适合需要持续监听的场景，如客户端监听锁队列整体变化。
     * 连接断开时通过 {@link #removeChannel} 清理。
     */
    void registerPersistent(ZNodePath path, Channel channel);

    void fire(ZNodePath path, WatchEvent.Type eventType);
    void removeChannel(Channel channel);
    int watcherCount(ZNodePath path);
}
