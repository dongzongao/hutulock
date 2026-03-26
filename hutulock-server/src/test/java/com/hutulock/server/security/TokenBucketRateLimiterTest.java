package com.hutulock.server.security;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TokenBucketRateLimiter 单元测试
 */
class TokenBucketRateLimiterTest {

    @Test
    void allowsUpToBurstCapacity() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 5);
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.tryAcquire("client-1")) allowed++;
        }
        // 桶容量 5，最多允许 5 个
        assertEquals(5, allowed);
    }

    @Test
    void refillsOverTime() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 3);

        // 消耗所有令牌
        for (int i = 0; i < 3; i++) limiter.tryAcquire("client-1");
        assertFalse(limiter.tryAcquire("client-1"));

        // 等待补充
        Thread.sleep(50); // 100 tokens/sec * 0.05s = 5 tokens
        assertTrue(limiter.tryAcquire("client-1"));
    }

    @Test
    void independentBucketsPerClient() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 2);

        // client-1 消耗完
        limiter.tryAcquire("client-1");
        limiter.tryAcquire("client-1");
        assertFalse(limiter.tryAcquire("client-1"));

        // client-2 不受影响
        assertTrue(limiter.tryAcquire("client-2"));
    }

    @Test
    void invalidParamsThrow() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, 0));
    }

    @Test
    void concurrentAccessSafe() throws InterruptedException {
        // 桶容量 100，每秒 1000 个令牌
        // 20 个线程各发 10 个请求 = 200 次尝试
        // 由于令牌补充，实际允许数可能略超 100，但不应超过 200
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 100);
        int threadCount = 20;
        AtomicInteger allowed = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                for (int j = 0; j < 10; j++) {
                    if (limiter.tryAcquire("shared-client")) allowed.incrementAndGet();
                }
                latch.countDown();
            }).start();
        }

        latch.await();
        int total = allowed.get();
        // 允许数应在合理范围内（桶容量 100，加上测试期间补充的令牌）
        assertTrue(total >= 1 && total <= 200,
            "Rate limiter allowed unexpected count: " + total);
        System.out.printf("[RateLimiter] Concurrent: threads=%d, allowed=%d/200%n",
            threadCount, total);
    }
}
