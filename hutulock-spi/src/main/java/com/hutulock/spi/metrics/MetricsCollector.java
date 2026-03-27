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
package com.hutulock.spi.metrics;

/**
 * Metrics 收集器接口（SPI 边界契约）
 *
 * <p>与具体监控后端（Prometheus / JMX / Logging）解耦。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public interface MetricsCollector {

    // 锁
    void onLockAcquired(String lockName);
    void onLockWaiting(String lockName);
    void onLockGrantedFromQueue(String lockName);
    void onLockReleased(String lockName);
    void onLockExpired(String lockName);
    void recordLockAcquireDuration(String lockName, long durationMs);

    // 会话
    void onSessionCreated();
    void onSessionExpired();
    void onSessionClosed();
    void onSessionReconnected();

    // ZNode
    void onZNodeCreated();
    void onZNodeDeleted();
    void onWatcherRegistered();
    void onWatcherFired();

    // Raft
    void onRaftElectionStarted();
    void onRaftLeaderChanged();
    void onRaftProposeSuccess();
    void onRaftProposeTimeout();
    void onRaftProposeRejected();
    void recordRaftProposeDuration(long durationMs);

    /** 返回无操作实现（Null Object）。 */
    static MetricsCollector noop() { return NoOpMetricsCollector.INSTANCE; }
}
