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

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * HeartbeatMonitor 单元测试
 */
public class HeartbeatMonitorTest {

    private HeartbeatMonitor monitor;

    @Before
    public void setUp() {
        HeartbeatMonitor.Config config = new HeartbeatMonitor.Config();
        config.warningThreshold = 2;
        config.criticalThreshold = 3;
        config.sessionTtlMs = 30_000;
        config.preemptiveRenewRatio = 0.7;
        monitor = new HeartbeatMonitor(config);
    }

    @Test
    public void testInitialState() {
        assertEquals(HeartbeatMonitor.State.DISCONNECTED, monitor.getState());
        assertEquals(0, monitor.getConsecutiveFailures());
    }

    @Test
    public void testRecordSuccess() {
        monitor.recordSuccess();

        assertEquals(HeartbeatMonitor.State.HEALTHY, monitor.getState());
        assertEquals(0, monitor.getConsecutiveFailures());
        assertTrue(monitor.getLastSuccessTime() > 0);
    }

    @Test
    public void testRecordFailure_Warning() {
        // 先成功一次，进入 HEALTHY
        monitor.recordSuccess();
        assertEquals(HeartbeatMonitor.State.HEALTHY, monitor.getState());

        // 失败 1 次 → 仍然 HEALTHY
        monitor.recordFailure();
        assertEquals(HeartbeatMonitor.State.HEALTHY, monitor.getState());
        assertEquals(1, monitor.getConsecutiveFailures());

        // 失败 2 次 → WARNING
        monitor.recordFailure();
        assertEquals(HeartbeatMonitor.State.WARNING, monitor.getState());
        assertEquals(2, monitor.getConsecutiveFailures());
    }

    @Test
    public void testRecordFailure_Critical() {
        monitor.recordSuccess();

        // 失败 3 次 → CRITICAL
        monitor.recordFailure();
        monitor.recordFailure();
        monitor.recordFailure();

        assertEquals(HeartbeatMonitor.State.CRITICAL, monitor.getState());
        assertEquals(3, monitor.getConsecutiveFailures());
    }

    @Test
    public void testStateRecovery() {
        monitor.recordSuccess();

        // 失败 3 次 → CRITICAL
        monitor.recordFailure();
        monitor.recordFailure();
        monitor.recordFailure();
        assertEquals(HeartbeatMonitor.State.CRITICAL, monitor.getState());

        // 成功 1 次 → 仍然 CRITICAL
        monitor.recordSuccess();
        assertEquals(HeartbeatMonitor.State.CRITICAL, monitor.getState());

        // 成功 2 次 → HEALTHY
        monitor.recordSuccess();
        assertEquals(HeartbeatMonitor.State.HEALTHY, monitor.getState());
        assertEquals(0, monitor.getConsecutiveFailures());
    }

    @Test
    public void testIsNearExpiry() throws InterruptedException {
        HeartbeatMonitor.Config config = new HeartbeatMonitor.Config();
        config.sessionTtlMs = 1000;  // 1 秒 TTL
        config.preemptiveRenewRatio = 0.7;  // 70% = 700ms
        HeartbeatMonitor mon = new HeartbeatMonitor(config);

        // 初始状态：未成功过，不接近过期
        assertFalse(mon.isNearExpiry());

        // 记录成功
        mon.recordSuccess();
        assertFalse(mon.isNearExpiry());

        // 等待 800ms（超过 70% TTL）
        Thread.sleep(800);
        assertTrue(mon.isNearExpiry());
    }

    @Test
    public void testGetTimeUntilExpiry() throws InterruptedException {
        HeartbeatMonitor.Config config = new HeartbeatMonitor.Config();
        config.sessionTtlMs = 1000;  // 1 秒 TTL
        HeartbeatMonitor mon = new HeartbeatMonitor(config);

        // 初始状态：返回完整 TTL
        assertEquals(1000, mon.getTimeUntilExpiryMs());

        // 记录成功
        mon.recordSuccess();
        long remaining1 = mon.getTimeUntilExpiryMs();
        assertTrue(remaining1 <= 1000);
        assertTrue(remaining1 > 900);

        // 等待 500ms
        Thread.sleep(500);
        long remaining2 = mon.getTimeUntilExpiryMs();
        assertTrue(remaining2 <= 500);
        assertTrue(remaining2 > 400);
    }

    @Test
    public void testStateChangeCallback() {
        AtomicInteger callbackCount = new AtomicInteger(0);
        AtomicInteger warningCount = new AtomicInteger(0);
        AtomicInteger criticalCount = new AtomicInteger(0);

        monitor.setOnStateChange((oldState, newState) -> {
            callbackCount.incrementAndGet();
            if (newState == HeartbeatMonitor.State.WARNING) {
                warningCount.incrementAndGet();
            } else if (newState == HeartbeatMonitor.State.CRITICAL) {
                criticalCount.incrementAndGet();
            }
        });

        // DISCONNECTED → HEALTHY
        monitor.recordSuccess();
        assertEquals(1, callbackCount.get());

        // HEALTHY → WARNING
        monitor.recordFailure();
        monitor.recordFailure();
        assertEquals(2, callbackCount.get());
        assertEquals(1, warningCount.get());

        // WARNING → CRITICAL
        monitor.recordFailure();
        assertEquals(3, callbackCount.get());
        assertEquals(1, criticalCount.get());

        // CRITICAL → HEALTHY (需要 2 次成功)
        monitor.recordSuccess();
        monitor.recordSuccess();
        assertEquals(4, callbackCount.get());
    }

    @Test
    public void testReset() {
        monitor.recordSuccess();
        monitor.recordFailure();
        monitor.recordFailure();

        assertEquals(HeartbeatMonitor.State.WARNING, monitor.getState());
        assertEquals(2, monitor.getConsecutiveFailures());

        // 重置
        monitor.reset();

        assertEquals(HeartbeatMonitor.State.DISCONNECTED, monitor.getState());
        assertEquals(0, monitor.getConsecutiveFailures());
        assertEquals(0, monitor.getLastSuccessTime());
    }

    @Test
    public void testMultipleSuccessesAfterFailures() {
        monitor.recordSuccess();

        // 失败 2 次 → WARNING
        monitor.recordFailure();
        monitor.recordFailure();
        assertEquals(HeartbeatMonitor.State.WARNING, monitor.getState());

        // 连续成功 5 次 → 应该恢复 HEALTHY
        for (int i = 0; i < 5; i++) {
            monitor.recordSuccess();
        }
        assertEquals(HeartbeatMonitor.State.HEALTHY, monitor.getState());
        assertEquals(0, monitor.getConsecutiveFailures());
    }

    @Test
    public void testAlternatingSuccessFailure() {
        monitor.recordSuccess();

        // 交替成功失败
        for (int i = 0; i < 5; i++) {
            monitor.recordFailure();
            assertEquals(1, monitor.getConsecutiveFailures());
            
            monitor.recordSuccess();
            assertEquals(0, monitor.getConsecutiveFailures());
        }

        // 应该保持 HEALTHY
        assertEquals(HeartbeatMonitor.State.HEALTHY, monitor.getState());
    }
}
