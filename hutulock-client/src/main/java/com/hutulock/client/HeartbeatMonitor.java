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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * 心跳监控器 - 分级告警 + 提前续期
 *
 * <p>核心功能：
 * <ol>
 *   <li>心跳状态分级：HEALTHY / WARNING / CRITICAL / DISCONNECTED</li>
 *   <li>会话过期预测：基于 TTL 和最后成功时间</li>
 *   <li>提前续期：接近过期时主动发送心跳</li>
 *   <li>状态变更回调：业务方可监听状态变化</li>
 * </ol>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class HeartbeatMonitor {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatMonitor.class);

    /** 心跳状态 */
    public enum State {
        HEALTHY,        // 健康
        WARNING,        // 警告（部分心跳失败）
        CRITICAL,       // 危急（多次心跳失败）
        DISCONNECTED    // 断连
    }

    /** 配置 */
    public static class Config {
        long intervalMs = 9_000;            // 心跳间隔
        long timeoutMs = 3_000;             // 心跳超时
        int warningThreshold = 2;           // 2 次失败进入警告
        int criticalThreshold = 3;          // 3 次失败进入危急
        long sessionTtlMs = 30_000;         // 会话 TTL
        double preemptiveRenewRatio = 0.7;  // 提前续期比例（70% TTL 时触发）
    }

    private final Config config;
    private volatile State state = State.DISCONNECTED;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicInteger consecutiveSuccesses = new AtomicInteger(0);
    private final AtomicLong lastSuccessTime = new AtomicLong(0);
    private final AtomicLong lastHeartbeatTime = new AtomicLong(0);

    private volatile BiConsumer<State, State> onStateChange;

    public HeartbeatMonitor(Config config) {
        this.config = config;
    }

    public static HeartbeatMonitor defaults() {
        return new HeartbeatMonitor(new Config());
    }

    /**
     * 记录心跳成功
     */
    public void recordSuccess() {
        long now = System.currentTimeMillis();
        lastSuccessTime.set(now);
        lastHeartbeatTime.set(now);

        consecutiveFailures.set(0);
        int successes = consecutiveSuccesses.incrementAndGet();

        // 状态恢复
        State current = state;
        if (current != State.HEALTHY) {
            if (successes >= 2) {  // 连续 2 次成功恢复健康
                updateState(State.HEALTHY);
            }
        }
    }

    /**
     * 记录心跳失败
     */
    public void recordFailure() {
        long now = System.currentTimeMillis();
        lastHeartbeatTime.set(now);

        int failures = consecutiveFailures.incrementAndGet();
        consecutiveSuccesses.set(0);

        log.warn("Heartbeat failed (consecutive failures: {})", failures);

        // 状态降级
        if (failures >= config.criticalThreshold) {
            updateState(State.CRITICAL);
        } else if (failures >= config.warningThreshold) {
            updateState(State.WARNING);
        }
    }

    /**
     * 检查是否接近过期
     */
    public boolean isNearExpiry() {
        long lastSuccess = lastSuccessTime.get();
        if (lastSuccess == 0) {
            return false;
        }

        long elapsed = System.currentTimeMillis() - lastSuccess;
        long threshold = (long) (config.sessionTtlMs * config.preemptiveRenewRatio);
        return elapsed > threshold;
    }

    /**
     * 获取距离过期的剩余时间
     */
    public long getTimeUntilExpiryMs() {
        long lastSuccess = lastSuccessTime.get();
        if (lastSuccess == 0) {
            return config.sessionTtlMs;
        }

        long elapsed = System.currentTimeMillis() - lastSuccess;
        return Math.max(0, config.sessionTtlMs - elapsed);
    }

    /**
     * 更新状态并触发回调
     */
    private void updateState(State newState) {
        State oldState = state;
        if (oldState == newState) {
            return;
        }

        state = newState;
        log.info("Heartbeat state changed: {} -> {}", oldState, newState);

        if (onStateChange != null) {
            try {
                onStateChange.accept(oldState, newState);
            } catch (Exception e) {
                log.error("State change callback error", e);
            }
        }
    }

    public State getState() {
        return state;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    public long getLastSuccessTime() {
        return lastSuccessTime.get();
    }

    public void setOnStateChange(BiConsumer<State, State> callback) {
        this.onStateChange = callback;
    }

    public void reset() {
        state = State.DISCONNECTED;
        consecutiveFailures.set(0);
        consecutiveSuccesses.set(0);
        lastSuccessTime.set(0);
        lastHeartbeatTime.set(0);
    }
}
