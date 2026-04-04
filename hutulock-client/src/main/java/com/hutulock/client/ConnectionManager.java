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

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 连接管理器 - 负责自动重连、节点健康管理、自适应超时
 *
 * <p>核心功能：
 * <ol>
 *   <li>自动重连：指数退避 + 最大重试次数</li>
 *   <li>节点健康度：HEALTHY / DEGRADED / UNHEALTHY 三级</li>
 *   <li>智能选择：优先选择健康且延迟低的节点</li>
 *   <li>自适应超时：基于 RTT 动态调整超时时间</li>
 *   <li>熔断器：连续失败后暂时跳过节点</li>
 * </ol>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class ConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class);

    /**
     * 节点健康状态
     */
    public enum NodeHealth {
        HEALTHY,    // 健康
        DEGRADED,   // 降级（延迟高但可用）
        UNHEALTHY,  // 不健康（连续失败）
        UNKNOWN     // 未知（未尝试连接）
    }

    /**
     * 节点信息
     */
    public static class NodeInfo {
        public final String id;
        public final String host;
        public final int port;
        public volatile NodeHealth health = NodeHealth.UNKNOWN;
        public volatile long lastSuccessTime;
        public volatile long lastFailureTime;
        public final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        public final AtomicInteger consecutiveSuccesses = new AtomicInteger(0);
        public final AtomicLong avgLatencyMs = new AtomicLong(0);

        public NodeInfo(String id, String host, int port) {
            this.id = id;
            this.host = host;
            this.port = port;
        }

        @Override
        public String toString() {
            return String.format("%s(%s:%d, health=%s, latency=%dms)",
                    id, host, port, health, avgLatencyMs.get());
        }
    }

    /**
     * 配置
     */
    public static class Config {
        public int maxReconnectAttempts = 5;
        public long initialReconnectDelayMs = 100;
        public long maxReconnectDelayMs = 30_000;
        public double reconnectBackoffMultiplier = 2.0;

        public int unhealthyThreshold = 3;      // 连续失败 3 次标记为不健康
        public int healthyThreshold = 2;        // 连续成功 2 次恢复健康
        public long healthCheckIntervalMs = 10_000;  // 健康检查间隔

        public long adaptiveTimeoutMinMs = 1_000;
        public long adaptiveTimeoutMaxMs = 30_000;
        public int connectTimeoutMs = 3_000;
        public int maxFrameLength = 4096;

        public long circuitBreakerTimeoutMs = 30_000;  // 熔断 30s 后尝试恢复
    }

    private final Config config;
    private final EventLoopGroup group;
    private final List<NodeInfo> nodes = new CopyOnWriteArrayList<>();

    private volatile Channel currentChannel;
    private volatile LockClientHandler currentHandler;
    private volatile String currentNodeId;

    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private volatile boolean reconnecting = false;
    private final AtomicLong adaptiveTimeoutMs = new AtomicLong(5_000);

    private final ScheduledExecutorService healthCheckScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "hutulock-health-check");
                t.setDaemon(true);
                return t;
            });

    private volatile Consumer<String> onConnectionLost;
    private volatile Consumer<String> onConnectionRestored;

    public ConnectionManager(Config config, EventLoopGroup group) {
        this.config = config;
        this.group = group;
        startHealthCheck();
    }

    public void addNode(String id, String host, int port) {
        nodes.add(new NodeInfo(id, host, port));
        log.info("Added node: {}", new NodeInfo(id, host, port));
    }

    public List<NodeInfo> getNodes() {
        return new ArrayList<>(nodes);
    }

    /**
     * 获取当前连接，如果断开则自动重连
     */
    public Channel getConnection() throws Exception {
        if (currentChannel != null && currentChannel.isActive()) {
            return currentChannel;
        }

        // 触发重连
        if (!reconnecting) {
            reconnect();
        }

        // 等待重连完成（最多 30 秒）
        for (int i = 0; i < 300; i++) {
            if (currentChannel != null && currentChannel.isActive()) {
                return currentChannel;
            }
            Thread.sleep(100);
        }

        throw new RuntimeException("Failed to get connection: all nodes unreachable");
    }

    public LockClientHandler getHandler() {
        return currentHandler;
    }

    public String getCurrentNodeId() {
        return currentNodeId;
    }

    public boolean isConnected() {
        return currentChannel != null && currentChannel.isActive();
    }

    /**
     * 自动重连（指数退避）
     */
    public synchronized void reconnect() throws Exception {
        if (reconnecting) {
            return;
        }

        reconnecting = true;
        reconnectAttempts.set(0);

        try {
            while (reconnectAttempts.get() < config.maxReconnectAttempts) {
                NodeInfo node = selectBestNode();
                if (node == null) {
                    log.error("No available nodes for reconnection");
                    break;
                }

                int attempt = reconnectAttempts.incrementAndGet();
                log.info("Attempting to reconnect to {} (attempt {}/{})",
                        node, attempt, config.maxReconnectAttempts);

                try {
                    doConnect(node);
                    reconnectAttempts.set(0);

                    if (onConnectionRestored != null) {
                        onConnectionRestored.accept(node.id);
                    }

                    log.info("Reconnected to {}", node);
                    return;

                } catch (Exception e) {
                    updateNodeHealth(node, false, 0);

                    long delay = calculateReconnectDelay(attempt);
                    log.warn("Reconnect to {} failed: {}, retrying in {}ms",
                            node, e.getMessage(), delay);

                    Thread.sleep(delay);
                }
            }

            throw new RuntimeException("Reconnection failed after " +
                    config.maxReconnectAttempts + " attempts");

        } finally {
            reconnecting = false;
        }
    }

    /**
     * 连接到指定节点
     */
    private void doConnect(NodeInfo node) throws InterruptedException {
        LockClientHandler handler = new LockClientHandler();
        handler.setRedirectListener(leaderId -> handleRedirect(leaderId));

        Channel channel = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.connectTimeoutMs)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new LineBasedFrameDecoder(config.maxFrameLength))
                                .addLast(new StringDecoder(CharsetUtil.UTF_8))
                                .addLast(new StringEncoder(CharsetUtil.UTF_8))
                                .addLast(handler);
                    }
                })
                .connect(node.host, node.port).sync().channel();

        // 监听连接断开
        channel.closeFuture().addListener(future -> {
            log.warn("Connection to {} lost", node);
            if (onConnectionLost != null) {
                onConnectionLost.accept(node.id);
            }
        });

        this.currentChannel = channel;
        this.currentHandler = handler;
        this.currentNodeId = node.id;

        updateNodeHealth(node, true, 0);
    }

    /**
     * 选择最佳节点（健康度 + 延迟优先）
     */
    private NodeInfo selectBestNode() {
        List<NodeInfo> healthy = new ArrayList<>();
        List<NodeInfo> degraded = new ArrayList<>();
        List<NodeInfo> unknown = new ArrayList<>();

        long now = System.currentTimeMillis();

        for (NodeInfo node : nodes) {
            // 熔断器：不健康节点在超时后可重试
            if (node.health == NodeHealth.UNHEALTHY) {
                if (now - node.lastFailureTime > config.circuitBreakerTimeoutMs) {
                    log.info("Node {} circuit breaker timeout, will retry", node);
                    node.health = NodeHealth.UNKNOWN;
                } else {
                    continue;  // 跳过熔断中的节点
                }
            }

            switch (node.health) {
                case HEALTHY:
                    healthy.add(node);
                    break;
                case DEGRADED:
                    degraded.add(node);
                    break;
                case UNKNOWN:
                    unknown.add(node);
                    break;
            }
        }

        // 优先级：HEALTHY > UNKNOWN > DEGRADED
        NodeInfo selected = selectLowestLatency(healthy);
        if (selected == null) selected = selectLowestLatency(unknown);
        if (selected == null) selected = selectLowestLatency(degraded);

        // 所有节点都不健康，选择最久未尝试的
        if (selected == null && !nodes.isEmpty()) {
            selected = nodes.stream()
                    .min(Comparator.comparingLong(n -> n.lastFailureTime))
                    .orElse(null);
        }

        return selected;
    }

    private NodeInfo selectLowestLatency(List<NodeInfo> candidates) {
        if (candidates.isEmpty()) return null;
        return candidates.stream()
                .min(Comparator.comparingLong(n -> n.avgLatencyMs.get()))
                .orElse(null);
    }

    /**
     * 更新节点健康度
     */
    public void updateNodeHealth(NodeInfo node, boolean success, long latencyMs) {
        long now = System.currentTimeMillis();

        if (success) {
            node.lastSuccessTime = now;
            node.consecutiveFailures.set(0);
            int successes = node.consecutiveSuccesses.incrementAndGet();

            // 更新平均延迟（指数移动平均）
            long oldAvg = node.avgLatencyMs.get();
            long newAvg = oldAvg == 0 ? latencyMs : (long) (oldAvg * 0.8 + latencyMs * 0.2);
            node.avgLatencyMs.set(newAvg);

            // 更新自适应超时（3 倍平均延迟）
            long newTimeout = Math.max(config.adaptiveTimeoutMinMs,
                    Math.min(newAvg * 3, config.adaptiveTimeoutMaxMs));
            adaptiveTimeoutMs.set(newTimeout);

            // 恢复健康
            if (node.health == NodeHealth.UNKNOWN) {
                // 从未知状态，第一次成功就变为健康
                node.health = NodeHealth.HEALTHY;
            } else if (node.health == NodeHealth.UNHEALTHY && successes >= config.healthyThreshold) {
                // 从不健康状态恢复，需要连续成功达到阈值
                node.health = NodeHealth.HEALTHY;
                log.info("Node {} recovered to HEALTHY", node);
            } else if (node.health == NodeHealth.DEGRADED && successes >= 1) {
                // 从降级状态，1 次成功就恢复健康
                node.health = NodeHealth.HEALTHY;
            }

        } else {
            node.lastFailureTime = now;
            int failures = node.consecutiveFailures.incrementAndGet();
            node.consecutiveSuccesses.set(0);

            // 降级健康度
            if (failures >= config.unhealthyThreshold) {
                node.health = NodeHealth.UNHEALTHY;
                log.warn("Node {} marked as UNHEALTHY (failures: {})", node, failures);
            } else if (failures >= 2 && failures < config.unhealthyThreshold) {
                // 失败 2 次以上但未达到不健康阈值，标记为降级
                node.health = NodeHealth.DEGRADED;
                log.warn("Node {} degraded (failures: {})", node, failures);
            }
        }
    }

    /**
     * 记录请求成功
     */
    public void onRequestSuccess(long latencyMs) {
        NodeInfo node = findNode(currentNodeId);
        if (node == null && !nodes.isEmpty()) {
            // 如果没有当前节点，使用第一个节点
            node = nodes.get(0);
        }
        if (node != null) {
            updateNodeHealth(node, true, latencyMs);
        }
    }

    /**
     * 记录请求失败
     */
    public void onRequestFailure() {
        NodeInfo node = findNode(currentNodeId);
        if (node == null && !nodes.isEmpty()) {
            // 如果没有当前节点，使用第一个节点
            node = nodes.get(0);
        }
        if (node != null) {
            updateNodeHealth(node, false, 0);
        }
    }

    private NodeInfo findNode(String nodeId) {
        if (nodeId == null) return null;
        return nodes.stream()
                .filter(n -> n.id.equals(nodeId))
                .findFirst()
                .orElse(null);
    }

    /**
     * 处理重定向
     */
    private void handleRedirect(String leaderId) {
        log.info("Redirected to leader: {}, reconnecting...", leaderId);
        try {
            reconnect();
        } catch (Exception e) {
            log.error("Reconnect after redirect failed", e);
        }
    }

    /**
     * 计算重连延迟（指数退避）
     */
    private long calculateReconnectDelay(int attempt) {
        long delay = config.initialReconnectDelayMs;
        for (int i = 1; i < attempt; i++) {
            delay = (long) (delay * config.reconnectBackoffMultiplier);
        }
        return Math.min(delay, config.maxReconnectDelayMs);
    }

    /**
     * 获取自适应超时时间
     */
    public long getAdaptiveTimeoutMs() {
        return adaptiveTimeoutMs.get();
    }

    /**
     * 健康检查循环
     */
    private void startHealthCheck() {
        healthCheckScheduler.scheduleAtFixedRate(() -> {
            try {
                long now = System.currentTimeMillis();
                for (NodeInfo node : nodes) {
                    if (node.health == NodeHealth.UNHEALTHY) {
                        long sinceFailure = now - node.lastFailureTime;
                        if (sinceFailure > config.circuitBreakerTimeoutMs) {
                            log.info("Node {} circuit breaker timeout, marking as UNKNOWN", node);
                            node.health = NodeHealth.UNKNOWN;
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Health check error", e);
            }
        }, config.healthCheckIntervalMs, config.healthCheckIntervalMs, TimeUnit.MILLISECONDS);
    }

    public void setOnConnectionLost(Consumer<String> callback) {
        this.onConnectionLost = callback;
    }

    public void setOnConnectionRestored(Consumer<String> callback) {
        this.onConnectionRestored = callback;
    }

    public void close() {
        healthCheckScheduler.shutdown();
        if (currentChannel != null) {
            currentChannel.close();
        }
    }
}
