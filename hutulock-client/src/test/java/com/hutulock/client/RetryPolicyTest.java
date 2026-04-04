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
package com.hutulock.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RetryPolicy 单元测试
 */
public class RetryPolicyTest {

    private RetryPolicy retryPolicy;

    @BeforeEach
    public void setUp() {
        RetryPolicy.Config config = new RetryPolicy.Config();
        config.maxAttempts = 3;
        config.initialDelayMs = 10;  // 测试用短延迟
        config.backoffMultiplier = 2.0;
        retryPolicy = new RetryPolicy(config);
    }

    @Test
    public void testShouldRetry_RetryableError() {
        // 可重试错误
        assertEquals(RetryPolicy.Decision.RETRY,
            retryPolicy.shouldRetry(1, "PROPOSE_TIMEOUT"));
        assertEquals(RetryPolicy.Decision.RETRY,
            retryPolicy.shouldRetry(1, "LEADER_CHANGED"));
        assertEquals(RetryPolicy.Decision.RETRY,
            retryPolicy.shouldRetry(1, "connection lost"));
    }

    @Test
    public void testShouldRetry_NonRetryableError() {
        // 不可重试错误
        assertEquals(RetryPolicy.Decision.FAIL,
            retryPolicy.shouldRetry(1, "LOCK_NOT_HELD"));
        assertEquals(RetryPolicy.Decision.FAIL,
            retryPolicy.shouldRetry(1, "SESSION_EXPIRED"));
        assertEquals(RetryPolicy.Decision.FAIL,
            retryPolicy.shouldRetry(1, "INVALID_COMMAND"));
    }

    @Test
    public void testShouldRetry_RedirectError() {
        // 需要重定向
        assertEquals(RetryPolicy.Decision.REDIRECT,
            retryPolicy.shouldRetry(1, "NOT_LEADER"));
        assertEquals(RetryPolicy.Decision.REDIRECT,
            retryPolicy.shouldRetry(1, "REDIRECT to node2"));
    }

    @Test
    public void testShouldRetry_MaxAttemptsExceeded() {
        // 超过最大重试次数
        assertEquals(RetryPolicy.Decision.FAIL,
            retryPolicy.shouldRetry(3, "PROPOSE_TIMEOUT"));
        assertEquals(RetryPolicy.Decision.FAIL,
            retryPolicy.shouldRetry(4, "PROPOSE_TIMEOUT"));
    }

    @Test
    public void testGetRetryDelay_ExponentialBackoff() {
        // 指数退避
        assertEquals(10, retryPolicy.getRetryDelay(1));   // 10ms
        assertEquals(20, retryPolicy.getRetryDelay(2));   // 20ms
        assertEquals(40, retryPolicy.getRetryDelay(3));   // 40ms
        assertEquals(80, retryPolicy.getRetryDelay(4));   // 80ms
    }

    @Test
    public void testGetRetryDelay_MaxDelay() {
        RetryPolicy.Config config = new RetryPolicy.Config();
        config.initialDelayMs = 100;
        config.maxDelayMs = 500;
        config.backoffMultiplier = 2.0;
        RetryPolicy policy = new RetryPolicy(config);

        // 不超过最大延迟
        assertEquals(100, policy.getRetryDelay(1));  // 100ms
        assertEquals(200, policy.getRetryDelay(2));  // 200ms
        assertEquals(400, policy.getRetryDelay(3));  // 400ms
        assertEquals(500, policy.getRetryDelay(4));  // 500ms (capped)
        assertEquals(500, policy.getRetryDelay(5));  // 500ms (capped)
    }

    @Test
    public void testExecute_SuccessOnFirstAttempt() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);

        String result = retryPolicy.execute(() -> {
            attempts.incrementAndGet();
            return "success";
        });

        assertEquals("success", result);
        assertEquals(1, attempts.get());
    }

    @Test
    public void testExecute_SuccessAfterRetry() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);

        String result = retryPolicy.execute(() -> {
            int attempt = attempts.incrementAndGet();
            if (attempt < 3) {
                throw new RuntimeException("PROPOSE_TIMEOUT");
            }
            return "success";
        });

        assertEquals("success", result);
        assertEquals(3, attempts.get());
    }

    @Test
    public void testExecute_FailAfterMaxRetries() {
        AtomicInteger attempts = new AtomicInteger(0);

        assertThrows(RuntimeException.class, () -> {
            retryPolicy.execute(() -> {
                attempts.incrementAndGet();
                throw new RuntimeException("PROPOSE_TIMEOUT");
            });
        });

        // 验证重试了 3 次
        assertEquals(3, attempts.get());
    }

    @Test
    public void testExecute_NonRetryableError() {
        AtomicInteger attempts = new AtomicInteger(0);

        assertThrows(RuntimeException.class, () -> {
            retryPolicy.execute(() -> {
                attempts.incrementAndGet();
                throw new RuntimeException("SESSION_EXPIRED");
            });
        });

        // 不可重试错误，只尝试 1 次
        assertEquals(1, attempts.get());
    }

    @Test
    public void testExecute_WithRedirectCallback() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        AtomicInteger redirects = new AtomicInteger(0);

        String result = retryPolicy.execute(() -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                throw new RuntimeException("NOT_LEADER");
            }
            return "success";
        }, errorMsg -> {
            redirects.incrementAndGet();
        });

        assertEquals("success", result);
        assertEquals(2, attempts.get());
        assertEquals(1, redirects.get());
    }

    @Test
    public void testExecuteVoid_Success() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);

        retryPolicy.executeVoid(() -> {
            counter.incrementAndGet();
        });

        assertEquals(1, counter.get());
    }

    @Test
    public void testExecuteVoid_WithRetry() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);

        retryPolicy.executeVoid(() -> {
            int attempt = attempts.incrementAndGet();
            if (attempt < 2) {
                throw new RuntimeException("PROPOSE_TIMEOUT");
            }
        });

        assertEquals(2, attempts.get());
    }

    @Test
    public void testUnknownError_DefaultRetry() {
        // 未知错误默认重试
        assertEquals(RetryPolicy.Decision.RETRY,
            retryPolicy.shouldRetry(1, "UNKNOWN_ERROR"));
        assertEquals(RetryPolicy.Decision.RETRY,
            retryPolicy.shouldRetry(1, "Some random error"));
    }
}
