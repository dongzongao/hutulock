/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.spi.storage;

import com.hutulock.model.watcher.WatchEvent;
import com.hutulock.model.znode.ZNodePath;
import io.netty.channel.Channel;

/**
 * Watcher 注册表接口（SPI 边界契约）
 *
 * <p>维护 path → Channel 的映射。
 * Watcher 是一次性的（One-shot）：触发后自动注销。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public interface WatcherRegistry {

    void register(ZNodePath path, Channel channel);
    void fire(ZNodePath path, WatchEvent.Type eventType);
    void removeChannel(Channel channel);
    int watcherCount(ZNodePath path);
}
