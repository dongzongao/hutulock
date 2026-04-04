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
package com.hutulock.client.example;

import com.hutulock.client.*;
import com.hutulock.config.api.ClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 增强版客户端使用示例 - 展示容错特性
 *
 * <p>演示功能：
 * <ol>
 *   <li>自动重连：网络断开后自动恢复</li>
 *   <li>智能重试：失败后指数退避重试</li>
 *   <li>心跳监控：分级告警 + 提前续期</li>
 *   <li>节点健康管理：自动选择最佳节点</li>
 * </ol>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class EnhancedLockClientExample {

    private static final Logger log = LoggerFactory.getLogger(EnhancedLockClientExample.class);

    public static void main(String[] args) throws Exception {
        // 示例 1：基本使用（自动重连 + 重试）
        basicExample();

        // 示例 2：心跳监控 + 提前续期
        heartbeatMonitoringExample();

        // 示例 3：节点健康管理
        nodeHealthExample();

        // 示例 4：网络抖动容错
        networkJitterExample();
    }

    /**
     * 示例 1：基本使用（自动重连 + 重试）
     */
    private static void basicExample() throws Exception {
        log.info("=== Example 1: Basic Usage with Auto-Reconnect ===");

        HutuLockClient client = HutuLockClient.builder()
            .addNode("127.0.0.1", 8881)
            .addNode("127.0.0.1", 8882)
            .addNode("127.0.0.1", 8883)
            .build();

        try {
            client.connect();
            log.info("Connected, session: {}", client.getSessionId());

            // 获取锁（自动重试）
            boolean acquired = client.lock("order-lock");
            if (acquired) {
                log.info("Lock acquired successfully");

                // 临界区操作
                Thread.sleep(5000);

                client.unlock("order-lock");
                log.info("Lock released");
            }

        } finally {
            client.close();
        }
    }

    /**
     * 示例 2：心跳监控 + 提前续期
     */
    private static void heartbeatMonitoringExample() throws Exception {
        log.info("=== Example 2: Heartbeat Monitoring ===");

        // 创建心跳监控器
        HeartbeatMonitor.Config hbConfig = new HeartbeatMonitor.Config();
        hbConfig.warningThreshold = 2;
        hbConfig.criticalThreshold = 3;
        hbConfig.preemptiveRenewRatio = 0.7;  // 70% TTL 时提前续期
        HeartbeatMonitor monitor = new HeartbeatMonitor(hbConfig);

        // 监听状态变化
        monitor.setOnStateChange((oldState, newState) -> {
            log.warn("Heartbeat state changed: {} -> {}", oldState, newState);

            if (newState == HeartbeatMonitor.State.CRITICAL) {
                log.error("CRITICAL: Session may expire soon! Consider releasing locks.");
            } else if (newState == HeartbeatMonitor.State.WARNING) {
                log.warn("WARNING: Heartbeat degraded, monitoring closely.");
            }
        });

        HutuLockClient client = HutuLockClient.builder()
            .addNode("127.0.0.1", 8881)
            .build();

        try {
            client.connect();

            AtomicBoolean abortWork = new AtomicBoolean(false);

            LockContext ctx = LockContext.builder("order-lock", client.getSessionId())
                .ttl(30, TimeUnit.SECONDS)
                .watchdogInterval(9, TimeUnit.SECONDS)
                .onExpired(lockName -> {
                    log.error("Lock {} expired! Aborting work.", lockName);
                    abortWork.set(true);
                })
                .build();

            if (client.lock(ctx, 30, TimeUnit.SECONDS)) {
                log.info("Lock acquired, starting long-running task");

                // 模拟长时间任务
                for (int i = 0; i < 100 && !abortWork.get(); i++) {
                    Thread.sleep(500);

                    // 检查是否接近过期
                    if (monitor.isNearExpiry()) {
                        log.warn("Session is near expiry! Time remaining: {}ms",
                            monitor.getTimeUntilExpiryMs());
                    }

                    // 模拟心跳
                    if (i % 10 == 0) {
                        if (Math.random() > 0.8) {
                            monitor.recordFailure();
                        } else {
                            monitor.recordSuccess();
                        }
                    }
                }

                if (!abortWork.get()) {
                    client.unlock(ctx);
                    log.info("Task completed, lock released");
                }
            }

        } finally {
            client.close();
        }
    }

    /**
     * 示例 3：节点健康管理
     */
    private static void nodeHealthExample() throws Exception {
        log.info("=== Example 3: Node Health Management ===");

        // 创建连接管理器
        ConnectionManager.Config connConfig = new ConnectionManager.Config();
        connConfig.unhealthyThreshold = 3;
        connConfig.healthyThreshold = 2;
        connConfig.circuitBreakerTimeoutMs = 30_000;

        io.netty.channel.nio.NioEventLoopGroup group = new io.netty.channel.nio.NioEventLoopGroup();
        ConnectionManager connManager = new ConnectionManager(connConfig, group);

        connManager.addNode("node1", "127.0.0.1", 8881);
        connManager.addNode("node2", "127.0.0.1", 8882);
        connManager.addNode("node3", "127.0.0.1", 8883);

        try {
            connManager.reconnect();

            // 查看节点健康状态
            for (ConnectionManager.NodeInfo node : connManager.getNodes()) {
                log.info("Node: {}, Health: {}, Latency: {}ms",
                    node.id, node.health, node.avgLatencyMs.get());
            }

            // 模拟请求
            for (int i = 0; i < 10; i++) {
                long start = System.currentTimeMillis();

                try {
                    // 模拟请求
                    Thread.sleep((long) (Math.random() * 100));
                    long latency = System.currentTimeMillis() - start;

                    connManager.onRequestSuccess(latency);
                    log.info("Request {} succeeded, latency: {}ms, adaptive timeout: {}ms",
                        i, latency, connManager.getAdaptiveTimeoutMs());

                } catch (Exception e) {
                    connManager.onRequestFailure();
                    log.warn("Request {} failed", i);
                }
            }

            // 再次查看节点健康状态
            log.info("=== Final Node Health ===");
            for (ConnectionManager.NodeInfo node : connManager.getNodes()) {
                log.info("Node: {}, Health: {}, Latency: {}ms, Failures: {}",
                    node.id, node.health, node.avgLatencyMs.get(),
                    node.consecutiveFailures.get());
            }

        } finally {
            connManager.close();
            group.shutdownGracefully();
        }
    }

    /**
     * 示例 4：网络抖动容错
     */
    private static void networkJitterExample() throws Exception {
        log.info("=== Example 4: Network Jitter Tolerance ===");

        // 创建重试策略
        RetryPolicy.Config retryConfig = new RetryPolicy.Config();
        retryConfig.maxAttempts = 5;
        retryConfig.initialDelayMs = 100;
        retryConfig.backoffMultiplier = 2.0;
        RetryPolicy retryPolicy = new RetryPolicy(retryConfig);

        // 模拟不稳定的操作
        int attempt = 0;
        try {
            retryPolicy.execute(() -> {
                int currentAttempt = ++attempt;
                log.info("Attempt {}", currentAttempt);

                // 模拟网络抖动：前 3 次失败
                if (currentAttempt < 4) {
                    throw new RuntimeException("PROPOSE_TIMEOUT");
                }

                log.info("Operation succeeded on attempt {}", currentAttempt);
                return "success";
            });

        } catch (Exception e) {
            log.error("Operation failed after retries: {}", e.getMessage());
        }

        log.info("Total attempts: {}", attempt);
    }
}
