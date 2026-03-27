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
package com.hutulock.spi.lock;

import java.util.concurrent.TimeUnit;

/**
 * 分布式锁服务接口（SPI 边界契约）
 *
 * <p>定义分布式锁的核心操作语义，server 和 client 均以此接口为边界。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public interface LockService {

    /**
     * 尝试获取分布式锁，阻塞直到成功或超时。
     *
     * @param lockName  锁名称
     * @param sessionId 调用方会话 ID
     * @param timeout   最大等待时间
     * @param unit      时间单位
     * @return 锁令牌，用于后续释放
     */
    LockToken tryAcquire(String lockName, String sessionId, long timeout, TimeUnit unit);

    /**
     * 释放分布式锁。
     *
     * @param token 由 {@link #tryAcquire} 返回的锁令牌
     */
    void release(LockToken token);

    /**
     * 续期（心跳）：重置服务端看门狗计时器。
     *
     * @param lockName  锁名称
     * @param sessionId 会话 ID
     */
    void renew(String lockName, String sessionId);
}
