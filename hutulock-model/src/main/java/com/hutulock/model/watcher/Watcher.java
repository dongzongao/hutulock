/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.model.watcher;

/**
 * Watcher 接口（对标 ZooKeeper Watcher）
 *
 * <p>Watcher 是一次性的（One-shot）：触发后自动注销，需重新注册。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
@FunctionalInterface
public interface Watcher {
    void process(WatchEvent event);
}
