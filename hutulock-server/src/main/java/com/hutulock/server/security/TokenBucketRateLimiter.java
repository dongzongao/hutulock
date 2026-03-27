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
package com.hutulock.server.security;

import com.hutulock.spi.security.RateLimiter;
import com.hutulock.model.util.Numbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 令牌桶限流器（{@link RateLimiter} 的令牌桶实现）
 *
 * <p>每个 clientId 独立维护一个令牌桶：
 * <ul>
 *   <li>桶容量（burst）：允许的最大突发请求数</li>
 *   <li>补充速率（rate）：每秒补充的令牌数</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 *   // 每个客户端每秒最多 10 个请求，允许突发 20 个
 *   RateLimiter limiter = new TokenBucketRateLimiter(10, 20);
 * }</pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class TokenBucketRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

    /** 每秒补充的令牌数 */
    private final double tokensPerSecond;
    /** 桶容量（最大突发数） */
    private final long   bucketCapacity;

    /** clientId → 令牌桶状态 */
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * 创建令牌桶限流器。
     *
     * @param tokensPerSecond 每秒补充令牌数（QPS 上限）
     * @param bucketCapacity  桶容量（允许的最大突发数）
     */
    public TokenBucketRateLimiter(double tokensPerSecond, long bucketCapacity) {
        if (tokensPerSecond <= 0) throw new IllegalArgumentException("tokensPerSecond must be > 0");
        if (bucketCapacity <= 0)  throw new IllegalArgumentException("bucketCapacity must be > 0");
        this.tokensPerSecond = tokensPerSecond;
        this.bucketCapacity  = bucketCapacity;
    }

    @Override
    public boolean tryAcquire(String clientId) {
        Bucket bucket = buckets.computeIfAbsent(clientId, k -> new Bucket(bucketCapacity));
        boolean allowed = bucket.tryConsume(tokensPerSecond, bucketCapacity);
        if (!allowed) {
            log.warn("Rate limit exceeded for clientId={}", clientId);
        }
        return allowed;
    }

    // ==================== 令牌桶内部状态 ====================

    private static final class Bucket {
        /** 当前令牌数（乘以 1000 存储，避免浮点精度问题） */
        private final AtomicLong tokens;
        /** 上次补充时间（纳秒） */
        private volatile long lastRefillNanos;

        Bucket(long initialTokens) {
            this.tokens          = new AtomicLong(initialTokens * Numbers.RATE_LIMITER_TOKEN_SCALE);
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryConsume(double tokensPerSecond, long capacity) {
            refill(tokensPerSecond, capacity);
            long current = tokens.get();
            if (current >= Numbers.RATE_LIMITER_TOKEN_SCALE) {
                tokens.addAndGet(-Numbers.RATE_LIMITER_TOKEN_SCALE);
                return true;
            }
            return false;
        }

        private void refill(double tokensPerSecond, long capacity) {
            long now     = System.nanoTime();
            long elapsed = now - lastRefillNanos;
            if (elapsed <= 0) return;

            // 计算应补充的令牌数（乘以 RATE_LIMITER_TOKEN_SCALE）
            long toAdd = (long)(elapsed * tokensPerSecond / 1_000_000_000.0 * Numbers.RATE_LIMITER_TOKEN_SCALE);
            if (toAdd > 0) {
                long newTokens = Math.min(tokens.get() + toAdd, capacity * Numbers.RATE_LIMITER_TOKEN_SCALE);
                tokens.set(newTokens);
                lastRefillNanos = now;
            }
        }
    }
}
