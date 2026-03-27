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
