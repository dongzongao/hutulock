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
package com.hutulock.proxy.handler;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 重试退避策略接口
 *
 * <p>决定第 {@code attempt}（1-based）次重试前等待多少毫秒。
 *
 * <p>内置实现：
 * <ul>
 *   <li>{@link #fixed(long)}        — 固定间隔</li>
 *   <li>{@link #exponential(long, long)} — 指数退避（带上限）</li>
 *   <li>{@link #jitter(long, long)} — 指数退避 + 随机抖动（推荐生产使用）</li>
 * </ul>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
@FunctionalInterface
public interface BackoffStrategy {

    /**
     * 计算第 {@code attempt} 次重试前的等待时间（ms）。
     *
     * @param attempt 当前重试次数（1-based，第一次重试传 1）
     * @return 等待毫秒数，&gt;= 0
     */
    long delayMs(int attempt);

    // ==================== 内置策略工厂方法 ====================

    /**
     * 固定间隔退避。
     *
     * <p>每次重试等待相同时间，适合幂等操作且服务恢复时间可预期的场景。
     *
     * @param intervalMs 固定等待时间（ms）
     */
    static BackoffStrategy fixed(long intervalMs) {
        return attempt -> intervalMs;
    }

    /**
     * 指数退避（无抖动）。
     *
     * <p>等待时间 = min(base * 2^(attempt-1), maxMs)。
     * 多客户端同时重试时可能产生"惊群"，建议配合 {@link #jitter} 使用。
     *
     * @param baseMs 基础等待时间（ms），第一次重试等待此时间
     * @param maxMs  等待时间上限（ms）
     */
    static BackoffStrategy exponential(long baseMs, long maxMs) {
        return attempt -> {
            long delay = baseMs << (attempt - 1); // baseMs * 2^(attempt-1)
            return Math.min(delay, maxMs);
        };
    }

    /**
     * 指数退避 + 随机抖动（Full Jitter，AWS 推荐）。
     *
     * <p>等待时间 = random(0, min(base * 2^(attempt-1), maxMs))。
     * 分散重试时间窗口，避免多客户端同时重试造成的"惊群效应"。
     *
     * @param baseMs 基础等待时间（ms）
     * @param maxMs  等待时间上限（ms）
     */
    static BackoffStrategy jitter(long baseMs, long maxMs) {
        return attempt -> {
            long cap = Math.min(baseMs << (attempt - 1), maxMs);
            return cap <= 0 ? 0 : ThreadLocalRandom.current().nextLong(cap + 1);
        };
    }
}
