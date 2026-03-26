/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.spi.security;

/**
 * 限流器接口（SPI 边界契约）
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public interface RateLimiter {

    /** 尝试获取令牌（非阻塞）。返回 false 表示被限流。 */
    boolean tryAcquire(String clientId);

    static RateLimiter unlimited() { return clientId -> true; }
}
