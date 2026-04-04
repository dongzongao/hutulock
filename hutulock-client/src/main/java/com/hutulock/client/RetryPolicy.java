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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * 重试策略 - 智能重试 + 指数退避 + 错误分类
 *
 * <p>核心功能：
 * <ol>
 *   <li>错误分类：可重试 vs 不可重试</li>
 *   <li>指数退避：100ms → 200ms → 400ms → ...</li>
 *   <li>最大重试次数限制</li>
 *   <li>重定向处理：NOT_LEADER 触发重连</li>
 * </ol>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class RetryPolicy {

    private static final Logger log = LoggerFactory.getLogger(RetryPolicy.class);

    /** 重试决策 */
    public enum Decision {
        RETRY,      // 重试
        FAIL,       // 失败
        REDIRECT    // 重定向后重试
    }

    /** 配置 */
    public static class Config {
        public int maxAttempts = 3;
        public long initialDelayMs = 100;
        public long maxDelayMs = 5_000;
        public double backoffMultiplier = 2.0;
        public boolean exponentialBackoff = true;

        // 可重试的错误
        public Set<String> retryableErrors = new HashSet<>(Arrays.asList(
            "PROPOSE_TIMEOUT",
            "LEADER_CHANGED",
            "CONNECTION_FAILED",
            "REQUEST_TIMEOUT",
            "connection lost"
        ));

        // 不可重试的错误
        public Set<String> nonRetryableErrors = new HashSet<>(Arrays.asList(
            "LOCK_NOT_HELD",
            "SESSION_EXPIRED",
            "INVALID_COMMAND",
            "MISSING_ARGUMENT",
            "NODE_ALREADY_EXISTS"
        ));
    }

    private final Config config;

    public RetryPolicy(Config config) {
        this.config = config;
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(new Config());
    }

    /**
     * 判断是否应该重试
     */
    public Decision shouldRetry(int attempt, String errorMessage) {
        if (attempt >= config.maxAttempts) {
            return Decision.FAIL;
        }

        // 检查是否明确不可重试
        for (String nonRetryable : config.nonRetryableErrors) {
            if (errorMessage.contains(nonRetryable)) {
                return Decision.FAIL;
            }
        }

        // 检查是否需要重定向
        if (errorMessage.contains("NOT_LEADER") || errorMessage.contains("REDIRECT")) {
            return Decision.REDIRECT;
        }

        // 检查是否可重试
        for (String retryable : config.retryableErrors) {
            if (errorMessage.contains(retryable)) {
                return Decision.RETRY;
            }
        }

        // 默认：未知错误重试
        return Decision.RETRY;
    }

    /**
     * 计算重试延迟
     */
    public long getRetryDelay(int attempt) {
        if (!config.exponentialBackoff) {
            return config.initialDelayMs;
        }

        long delay = config.initialDelayMs;
        for (int i = 1; i < attempt; i++) {
            delay = (long) (delay * config.backoffMultiplier);
        }
        return Math.min(delay, config.maxDelayMs);
    }

    /**
     * 执行带重试的操作
     */
    public <T> T execute(Callable<T> operation) throws Exception {
        return execute(operation, null);
    }

    /**
     * 执行带重试的操作（带重定向回调）
     */
    public <T> T execute(Callable<T> operation, Consumer<String> onRedirect) throws Exception {
        int attempt = 0;
        Exception lastException = null;

        while (true) {
            try {
                return operation.call();

            } catch (Exception e) {
                attempt++;
                lastException = e;

                Decision decision = shouldRetry(attempt, e.getMessage());

                if (decision == Decision.FAIL) {
                    log.debug("Not retrying after attempt {}: {}", attempt, e.getMessage());
                    throw e;
                }

                if (decision == Decision.REDIRECT) {
                    log.info("Redirect detected, triggering reconnect");
                    if (onRedirect != null) {
                        onRedirect.accept(e.getMessage());
                    }
                }

                long delay = getRetryDelay(attempt);
                log.warn("Request failed (attempt {}/{}): {}, retrying in {}ms",
                    attempt, config.maxAttempts, e.getMessage(), delay);

                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted", ie);
                }
            }
        }
    }

    /**
     * 执行带重试的操作（无返回值）
     */
    public void executeVoid(RunnableWithException operation) throws Exception {
        execute(() -> {
            operation.run();
            return null;
        });
    }

    @FunctionalInterface
    public interface RunnableWithException {
        void run() throws Exception;
    }
}
