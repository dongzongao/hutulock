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
 * 无操作 MetricsCollector（Null Object 模式）
 *
 * <p>通过 {@link MetricsCollector#noop()} 获取单例。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
final class NoOpMetricsCollector implements MetricsCollector {

    static final NoOpMetricsCollector INSTANCE = new NoOpMetricsCollector();

    private NoOpMetricsCollector() {}

    @Override public void onLockAcquired(String l)              {}
    @Override public void onLockWaiting(String l)               {}
    @Override public void onLockGrantedFromQueue(String l)      {}
    @Override public void onLockReleased(String l)              {}
    @Override public void onLockExpired(String l)               {}
    @Override public void recordLockAcquireDuration(String l, long d) {}
    @Override public void onSessionCreated()                    {}
    @Override public void onSessionExpired()                    {}
    @Override public void onSessionClosed()                     {}
    @Override public void onSessionReconnected()                {}
    @Override public void onZNodeCreated()                      {}
    @Override public void onZNodeDeleted()                      {}
    @Override public void onWatcherRegistered()                 {}
    @Override public void onWatcherFired()                      {}
    @Override public void onRaftElectionStarted()               {}
    @Override public void onRaftLeaderChanged()                 {}
    @Override public void onRaftProposeSuccess()                {}
    @Override public void onRaftProposeTimeout()                {}
    @Override public void onRaftProposeRejected()               {}
    @Override public void recordRaftProposeDuration(long d)     {}
}
