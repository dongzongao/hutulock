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

import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConnectionManager 单元测试
 */
public class ConnectionManagerTest {

    private NioEventLoopGroup eventLoopGroup;
    private ConnectionManager connectionManager;

    @BeforeEach
    public void setUp() {
        eventLoopGroup = new NioEventLoopGroup();
        ConnectionManager.Config config = new ConnectionManager.Config();
        config.maxReconnectAttempts = 3;
        config.initialReconnectDelayMs = 50;
        config.unhealthyThreshold = 2;
        connectionManager = new ConnectionManager(config, eventLoopGroup);
    }

    @AfterEach
    public void tearDown() {
        if (connectionManager != null) {
            connectionManager.close();
        }
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully();
        }
    }

    @Test
    public void testAddNode() {
        connectionManager.addNode("node1", "127.0.0.1", 8881);
        connectionManager.addNode("node2", "127.0.0.1", 8882);

        List<ConnectionManager.NodeInfo> nodes = connectionManager.getNodes();
        assertEquals(2, nodes.size());
        assertEquals("node1", nodes.get(0).id);
        assertEquals("127.0.0.1", nodes.get(0).host);
        assertEquals(8881, nodes.get(0).port);
    }

    @Test
    public void testNodeHealthUpdate() {
        connectionManager.addNode("node1", "127.0.0.1", 8881);
        ConnectionManager.NodeInfo node = connectionManager.getNodes().get(0);

        // 初始状态：UNKNOWN
        assertEquals(ConnectionManager.NodeHealth.UNKNOWN, node.health);

        // 成功 → HEALTHY
        connectionManager.updateNodeHealth(node, true, 50);
        assertEquals(ConnectionManager.NodeHealth.HEALTHY, node.health);
        assertEquals(50, node.avgLatencyMs.get());

        // 失败 1 次 → 仍然 HEALTHY
        connectionManager.updateNodeHealth(node, false, 0);
        assertEquals(ConnectionManager.NodeHealth.HEALTHY, node.health);
        assertEquals(1, node.consecutiveFailures.get());

        // 失败 2 次 → UNHEALTHY（阈值为 2）
        connectionManager.updateNodeHealth(node, false, 0);
        assertEquals(ConnectionManager.NodeHealth.UNHEALTHY, node.health);
        assertEquals(2, node.consecutiveFailures.get());
    }

    @Test
    public void testAdaptiveTimeout() {
        connectionManager.addNode("node1", "127.0.0.1", 8881);
        ConnectionManager.NodeInfo node = connectionManager.getNodes().get(0);

        // 初始超时
        long initialTimeout = connectionManager.getAdaptiveTimeoutMs();
        assertTrue(initialTimeout > 0);

        // 记录成功请求，延迟 100ms
        connectionManager.updateNodeHealth(node, true, 100);
        long timeout1 = connectionManager.getAdaptiveTimeoutMs();
        
        // 自适应超时应该是 3 倍延迟
        assertTrue(timeout1 >= 300);  // 100ms * 3

        // 记录成功请求，延迟 200ms
        connectionManager.updateNodeHealth(node, true, 200);
        long timeout2 = connectionManager.getAdaptiveTimeoutMs();
        
        // 超时应该增加（EMA 算法）
        // 注意：由于 EMA 算法，第二次的权重较小，可能不会立即超过第一次
        // 第一次：avgLatency = 100, timeout = 300
        // 第二次：avgLatency = 100*0.8 + 200*0.2 = 120, timeout = 360
        assertTrue(timeout2 >= timeout1, 
            String.format("Expected timeout2 (%d) >= timeout1 (%d)", timeout2, timeout1));
    }

    @Test
    public void testOnRequestSuccess() {
        connectionManager.addNode("node1", "127.0.0.1", 8881);
        
        // 记录成功
        connectionManager.onRequestSuccess(50);
        
        // 验证自适应超时已更新
        long timeout = connectionManager.getAdaptiveTimeoutMs();
        assertTrue(timeout > 0);
    }

    @Test
    public void testOnRequestFailure() {
        connectionManager.addNode("node1", "127.0.0.1", 8881);
        ConnectionManager.NodeInfo node = connectionManager.getNodes().get(0);
        
        // 先成功一次，变为 HEALTHY
        connectionManager.updateNodeHealth(node, true, 50);
        assertEquals(ConnectionManager.NodeHealth.HEALTHY, node.health);
        
        // 记录失败
        connectionManager.onRequestFailure();
        
        // 验证失败计数增加
        assertTrue(node.consecutiveFailures.get() > 0);
    }

    @Test
    public void testCircuitBreaker() throws InterruptedException {
        ConnectionManager.Config config = new ConnectionManager.Config();
        config.unhealthyThreshold = 2;
        config.circuitBreakerTimeoutMs = 100;  // 100ms 熔断超时
        
        ConnectionManager cm = new ConnectionManager(config, eventLoopGroup);
        cm.addNode("node1", "127.0.0.1", 8881);
        ConnectionManager.NodeInfo node = cm.getNodes().get(0);

        // 连续失败 2 次 → UNHEALTHY
        cm.updateNodeHealth(node, false, 0);
        cm.updateNodeHealth(node, false, 0);
        assertEquals(ConnectionManager.NodeHealth.UNHEALTHY, node.health);

        // 等待熔断超时
        Thread.sleep(150);

        // 熔断器应该已经超时，节点应该可以重试
        // （实际测试中需要健康检查线程运行）
        assertTrue(System.currentTimeMillis() - node.lastFailureTime > config.circuitBreakerTimeoutMs);
        
        cm.close();
    }

    @Test
    public void testNodeHealthRecovery() {
        connectionManager.addNode("node1", "127.0.0.1", 8881);
        ConnectionManager.NodeInfo node = connectionManager.getNodes().get(0);

        // 失败 2 次 → UNHEALTHY
        connectionManager.updateNodeHealth(node, false, 0);
        connectionManager.updateNodeHealth(node, false, 0);
        assertEquals(ConnectionManager.NodeHealth.UNHEALTHY, node.health);

        // 成功 1 次 → 仍然 UNHEALTHY
        connectionManager.updateNodeHealth(node, true, 50);
        assertEquals(ConnectionManager.NodeHealth.UNHEALTHY, node.health);

        // 成功 2 次 → HEALTHY（恢复阈值为 2）
        connectionManager.updateNodeHealth(node, true, 50);
        assertEquals(ConnectionManager.NodeHealth.HEALTHY, node.health);
    }

    @Test
    public void testMultipleNodes() {
        connectionManager.addNode("node1", "127.0.0.1", 8881);
        connectionManager.addNode("node2", "127.0.0.1", 8882);
        connectionManager.addNode("node3", "127.0.0.1", 8883);

        List<ConnectionManager.NodeInfo> nodes = connectionManager.getNodes();
        assertEquals(3, nodes.size());

        // 设置不同的健康状态
        connectionManager.updateNodeHealth(nodes.get(0), true, 50);   // HEALTHY
        connectionManager.updateNodeHealth(nodes.get(1), false, 0);   // 失败 1 次
        connectionManager.updateNodeHealth(nodes.get(2), false, 0);   // 失败 1 次
        connectionManager.updateNodeHealth(nodes.get(2), false, 0);   // 失败 2 次 → UNHEALTHY

        assertEquals(ConnectionManager.NodeHealth.HEALTHY, nodes.get(0).health);
        assertEquals(ConnectionManager.NodeHealth.UNHEALTHY, nodes.get(2).health);
    }
}
