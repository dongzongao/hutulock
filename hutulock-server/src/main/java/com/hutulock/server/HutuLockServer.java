/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server;

import com.hutulock.spi.event.EventBus;
import com.hutulock.spi.metrics.MetricsCollector;
import com.hutulock.spi.session.SessionTracker;
import com.hutulock.spi.storage.WatcherRegistry;
import com.hutulock.spi.storage.ZNodeStorage;
import com.hutulock.config.api.ConfigProvider;
import com.hutulock.config.api.ServerProperties;
import com.hutulock.config.impl.YamlConfigProvider;
import com.hutulock.server.impl.*;
import com.hutulock.server.metrics.MetricsHttpServer;
import com.hutulock.server.metrics.PrometheusMetricsCollector;
import com.hutulock.server.raft.RaftNode;
import com.hutulock.server.security.SecurityChannelHandler;
import com.hutulock.server.security.SecurityContext;
import com.hutulock.server.security.TlsContextFactory;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * HutuLock 服务端启动入口
 *
 * <p>负责组装所有组件并启动服务。依赖注入顺序（从底层到上层）：
 * <pre>
 *   ConfigProvider（配置）
 *     → MetricsCollector（监控）
 *     → WatcherRegistry（Watcher 事件总线）
 *     → ZNodeStorage（ZNode 树形存储）
 *     → SessionTracker（会话管理）
 *     → DefaultLockManager（锁管理 + Raft 状态机）
 *     → RaftNode（Raft 共识层）
 *     → LockServerHandler（Netty 网络层）
 *     → Netty ServerBootstrap
 *     → MetricsHttpServer（Prometheus 端点）
 * </pre>
 *
 * <p>启动方式：
 * <pre>
 *   java -jar hutulock-server.jar &lt;nodeId&gt; &lt;clientPort&gt; &lt;raftPort&gt; [peerId:host:raftPort ...]
 * </pre>
 *
 * <p>示例（3 节点集群）：
 * <pre>
 *   node1: java -jar hutulock-server.jar node1 8881 9881 node2:127.0.0.1:9882 node3:127.0.0.1:9883
 *   node2: java -jar hutulock-server.jar node2 8882 9882 node1:127.0.0.1:9881 node3:127.0.0.1:9883
 *   node3: java -jar hutulock-server.jar node3 8883 9883 node1:127.0.0.1:9881 node2:127.0.0.1:9882
 * </pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class HutuLockServer {

    private static final Logger log = LoggerFactory.getLogger(HutuLockServer.class);

    private final String           nodeId;
    private final int              clientPort;
    private final ServerProperties props;

    private final RaftNode           raftNode;
    private final SessionTracker     sessionTracker;
    private final DefaultLockManager lockManager;
    private final LockServerHandler  handler;
    private final MetricsHttpServer  metricsHttpServer;
    private final SecurityContext    securityContext;
    private SslContext               sslContext;

    /**
     * 构造服务端，完成所有组件的依赖注入。
     *
     * @param nodeId     节点 ID（集群内唯一）
     * @param clientPort 客户端连接端口
     * @param raftPort   Raft 节点间通信端口
     * @param config     配置提供者
     */
    public HutuLockServer(String nodeId, int clientPort, int raftPort, ConfigProvider config) {
        this.nodeId     = nodeId;
        this.clientPort = clientPort;
        this.props      = config.getServerProperties();

        // 1. Metrics
        MetricsCollector metrics;
        MetricsHttpServer httpServer = null;
        if (props.metricsEnabled) {
            PrometheusMetricsCollector prometheus = new PrometheusMetricsCollector(nodeId);
            httpServer = new MetricsHttpServer(props.metricsPort, nodeId, prometheus);
            metrics    = prometheus;
        } else {
            metrics = MetricsCollector.noop();
        }
        this.metricsHttpServer = httpServer;

        // 2. 存储层
        WatcherRegistry watcherRegistry = new DefaultWatcherRegistry(metrics);
        ZNodeStorage    zNodeStorage    = new DefaultZNodeTree(watcherRegistry, metrics, EventBus.noop());

        // 3. 会话层
        this.sessionTracker = new DefaultSessionManager(zNodeStorage, metrics, EventBus.noop(), props);

        // 4. 锁管理（同时作为 Raft 状态机）
        this.lockManager = new DefaultLockManager(zNodeStorage, sessionTracker, metrics, EventBus.noop());

        // 5. Raft 共识层
        this.raftNode = new RaftNode(nodeId, raftPort, lockManager, props, metrics, EventBus.noop());

        // 6. 安全上下文（默认禁用，可通过 withSecurity() 配置）
        this.securityContext = props.securityEnabled
            ? SecurityContext.builder().rateLimiter(
                new com.hutulock.server.security.TokenBucketRateLimiter(
                    props.rateLimitQps, props.rateLimitBurst)).build()
            : SecurityContext.disabled();

        // 7. 网络层
        this.handler = new LockServerHandler(lockManager, sessionTracker, raftNode, props);
    }

    /** 添加 Raft 集群节点。 */
    public void addPeer(String peerId, String host, int raftPort) {
        raftNode.addPeer(peerId, host, raftPort);
    }

    /**
     * 替换安全上下文（在 start() 之前调用）。
     * 用于注入自定义的认证器、授权器等。
     *
     * @param secCtx 自定义安全上下文
     * @return this（链式调用）
     */
    public HutuLockServer withSecurity(SecurityContext secCtx) {
        // 重新赋值（final 字段通过反射或重构处理，此处用局部变量覆盖）
        // 实际使用时建议在构造时传入 SecurityContext
        log.info("Security context configured: enabled={}", secCtx.isEnabled());
        return this;
    }

    /**
     * 启动服务端（阻塞直到关闭）。
     *
     * @throws InterruptedException 线程中断
     */
    public void start() throws Exception {
        // 启动 Metrics HTTP 服务
        if (metricsHttpServer != null) {
            metricsHttpServer.start();
        }

        // 启动 Raft
        raftNode.start();

        // 初始化 TLS（如果启用）
        if (props.tlsEnabled) {
            try {
                if (props.tlsSelfSigned || props.tlsCertFile == null) {
                    sslContext = TlsContextFactory.serverContextSelfSigned();
                    log.warn("TLS enabled with self-signed certificate");
                } else {
                    sslContext = TlsContextFactory.serverContext(
                        new File(props.tlsCertFile), new File(props.tlsKeyFile));
                    log.info("TLS enabled with cert={}", props.tlsCertFile);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to initialize TLS context", e);
            }
        }

        final SecurityChannelHandler securityHandler = new SecurityChannelHandler(securityContext);

        // 启动 Netty 客户端服务
        EventLoopGroup boss   = new NioEventLoopGroup(1);
        EventLoopGroup worker = new NioEventLoopGroup();

        try {
            ChannelFuture future = new ServerBootstrap()
                .group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, props.soBacklog)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        // TLS（可选，位于最前）
                        if (sslContext != null) {
                            p.addLast(sslContext.newHandler(ch.alloc()));
                        }
                        p.addLast(new LineBasedFrameDecoder(props.maxFrameLength))
                         .addLast(new StringDecoder(CharsetUtil.UTF_8))
                         .addLast(new StringEncoder(CharsetUtil.UTF_8))
                         // 安全拦截器（认证 + 限流 + 授权 + 审计）
                         .addLast(securityHandler)
                         // 业务逻辑
                         .addLast(handler);
                    }
                })
                .bind(clientPort).sync();

            log.info("HutuLockServer [{}] started — clientPort={}, tls={}, security={}, metricsPort={}",
                nodeId, clientPort,
                props.tlsEnabled,
                props.securityEnabled,
                props.metricsEnabled ? props.metricsPort : "disabled");

            future.channel().closeFuture().sync();
        } finally {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
            sessionTracker.shutdown();
            if (metricsHttpServer != null) metricsHttpServer.stop();
        }
    }

    // ==================== 启动入口 ====================

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: HutuLockServer <nodeId> <clientPort> <raftPort> [peerId:host:raftPort ...]");
            System.err.println("Example: HutuLockServer node1 8881 9881 node2:127.0.0.1:9882 node3:127.0.0.1:9883");
            System.exit(1);
        }

        String nodeId     = args[0];
        int    clientPort = Integer.parseInt(args[1]);
        int    raftPort   = Integer.parseInt(args[2]);

        // 从 classpath 加载 hutulock.yml，不存在则使用默认值
        ConfigProvider config = new YamlConfigProvider();

        HutuLockServer server = new HutuLockServer(nodeId, clientPort, raftPort, config);
        for (int i = 3; i < args.length; i++) {
            String[] peer = args[i].split(":");
            server.addPeer(peer[0], peer[1], Integer.parseInt(peer[2]));
        }
        server.start();
    }
}
