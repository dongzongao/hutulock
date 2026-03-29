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
package com.hutulock.config.api;

/**
 * 客户端配置属性（不可变值对象）
 *
 * <p>通过 {@link Builder} 构建，支持链式调用。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class ClientProperties {

    /** 连接超时（毫秒），默认 3s */
    public final int  connectTimeoutMs;
    /** 获取锁的默认超时（秒），默认 30s */
    public final int  lockTimeoutS;
    /** 看门狗 TTL（毫秒），默认 30s，应与服务端一致 */
    public final long watchdogTtlMs;
    /** 看门狗心跳间隔（毫秒），默认 10s，应 < watchdogTtl/3 */
    public final long watchdogIntervalMs;
    /** 最大帧长度（字节），默认 1024 */
    public final int  maxFrameLength;
    /** 是否启用客户端 Metrics，默认 true */
    public final boolean metricsEnabled;

    private ClientProperties(Builder b) {
        this.connectTimeoutMs  = b.connectTimeoutMs;
        this.lockTimeoutS      = b.lockTimeoutS;
        this.watchdogTtlMs     = b.watchdogTtlMs;
        this.watchdogIntervalMs= b.watchdogIntervalMs;
        this.maxFrameLength    = b.maxFrameLength;
        this.metricsEnabled    = b.metricsEnabled;
    }

    public static ClientProperties defaults() { return builder().build(); }

    public static Builder builder() { return new Builder(); }

    /** 客户端配置构建器 */
    public static final class Builder {
        private int     connectTimeoutMs   = 3_000;
        private int     lockTimeoutS       = 30;
        private long    watchdogTtlMs      = 30_000;
        private long    watchdogIntervalMs = 9_000;
        private int     maxFrameLength     = 1024;
        private boolean metricsEnabled     = true;

        public Builder connectTimeout(int ms)    { connectTimeoutMs   = ms;  return this; }
        public Builder lockTimeout(int s)        { lockTimeoutS       = s;   return this; }
        public Builder watchdogTtl(long ms)      { watchdogTtlMs      = ms;  return this; }
        public Builder watchdogInterval(long ms) { watchdogIntervalMs = ms;  return this; }
        public Builder maxFrameLength(int n)     { maxFrameLength     = n;   return this; }
        public Builder metricsEnabled(boolean b) { metricsEnabled     = b;   return this; }

        public ClientProperties build() {
            if (watchdogIntervalMs >= watchdogTtlMs / 3) {
                throw new IllegalArgumentException(
                    "watchdogInterval should be < watchdogTtl/3 to ensure renewal before expiry");
            }
            return new ClientProperties(this);
        }
    }
}
