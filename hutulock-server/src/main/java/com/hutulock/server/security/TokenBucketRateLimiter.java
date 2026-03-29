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

    /** 超过此条目数时触发一次清理（移除长时间未活跃的 bucket） */
    private static final int CLEANUP_THRESHOLD = 10_000;
    /** 超过此时间（纳秒）未活跃的 bucket 视为过期，约 5 分钟 */
    private static final long BUCKET_IDLE_NS = 5L * 60 * 1_000_000_000L;

    @Override
    public boolean tryAcquire(String clientId) {
        Bucket bucket = buckets.computeIfAbsent(clientId, k -> new Bucket(bucketCapacity));
        boolean allowed = bucket.tryConsume(tokensPerSecond, bucketCapacity);
        if (!allowed) {
            log.warn("Rate limit exceeded for clientId={}", clientId);
        }
        // 懒清理：bucket 数量超过阈值时移除长时间未活跃的条目，防止内存无限增长
        if (buckets.size() > CLEANUP_THRESHOLD) {
            evictIdleBuckets();
        }
        return allowed;
    }

    private void evictIdleBuckets() {
        long now = System.nanoTime();
        buckets.entrySet().removeIf(e -> e.getValue().isIdle(now, BUCKET_IDLE_NS));
        log.debug("Rate limiter evicted idle buckets, remaining={}", buckets.size());
    }

    // ==================== 令牌桶内部状态 ====================

    private static final class Bucket {
        /** 当前令牌数（乘以 1000 存储，避免浮点精度问题） */
        private final AtomicLong tokens;
        /** 上次补充时间（纳秒），用 AtomicLong 替代 volatile long，支持 CAS 无锁更新 */
        private final AtomicLong lastRefillNanos;
        /** 上次访问时间（纳秒），用于懒清理过期 bucket */
        private volatile long lastAccessNanos;

        Bucket(long initialTokens) {
            this.tokens          = new AtomicLong(initialTokens * Numbers.RATE_LIMITER_TOKEN_SCALE);
            this.lastRefillNanos = new AtomicLong(System.nanoTime());
            this.lastAccessNanos = System.nanoTime();
        }

        boolean isIdle(long now, long idleThresholdNs) {
            return now - lastAccessNanos > idleThresholdNs;
        }

        /**
         * 无锁令牌消费（CAS 替代 synchronized）。
         *
         * <p>先补充令牌（refill），再 CAS 扣减。
         * refill 用 compareAndSet 保证只有一个线程更新 lastRefillNanos，
         * 其他线程看到 elapsed=0 直接跳过，避免重复补充。
         */
        boolean tryConsume(double tokensPerSecond, long capacity) {
            lastAccessNanos = System.nanoTime();
            refill(tokensPerSecond, capacity);
            // CAS 循环扣减一个令牌，无锁
            while (true) {
                long current = tokens.get();
                if (current < Numbers.RATE_LIMITER_TOKEN_SCALE) return false;
                if (tokens.compareAndSet(current, current - Numbers.RATE_LIMITER_TOKEN_SCALE)) return true;
                // CAS 失败说明有并发竞争，重试（通常 1-2 次即可）
            }
        }

        private void refill(double tokensPerSecond, long capacity) {
            long now  = System.nanoTime();
            long last = lastRefillNanos.get();
            long elapsed = now - last;
            if (elapsed <= 0) return;

            long toAdd = (long)(elapsed * tokensPerSecond / 1_000_000_000.0 * Numbers.RATE_LIMITER_TOKEN_SCALE);
            if (toAdd <= 0) return;

            // CAS 更新 lastRefillNanos，只有一个线程成功，避免重复补充
            if (!lastRefillNanos.compareAndSet(last, now)) return;

            long maxTokens = capacity * Numbers.RATE_LIMITER_TOKEN_SCALE;
            // 循环 CAS 更新 tokens，上限为桶容量
            while (true) {
                long current = tokens.get();
                long updated = Math.min(current + toAdd, maxTokens);
                if (tokens.compareAndSet(current, updated)) break;
            }
        }
    }
}
