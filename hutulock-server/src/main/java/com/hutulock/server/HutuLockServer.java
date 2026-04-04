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
package com.hutulock.server;

// Performance benchmark: automated testing on every commit
import com.hutulock.config.api.ConfigProvider;
import com.hutulock.config.api.ServerProperties;
import com.hutulock.config.impl.YamlConfigProvider;
import com.hutulock.server.ioc.ApplicationContext;
import com.hutulock.server.ioc.BeanDefinition;
import com.hutulock.server.ioc.ServerBeanFactory;
import com.hutulock.server.raft.RaftNode;
import com.hutulock.server.security.SecurityContext;
import com.hutulock.server.security.SecurityChannelHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * HutuLock 服务端启动入口
 *
 * <p>使用 {@link ApplicationContext}（IoC 容器）管理所有内存组件，
 * 组件依赖关系集中声明在 {@link ServerBeanFactory}，启动/关闭生命周期由容器统一调度。
 *
 * <p>启动方式：
 * <pre>
 *   java -jar hutulock-server.jar &lt;nodeId&gt; &lt;clientPort&gt; &lt;raftPort&gt; [peerId:host:raftPort ...]
 * </pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class HutuLockServer {

    private static final Logger log = LoggerFactory.getLogger(HutuLockServer.class);

    private final String             nodeId;
    private final int                clientPort;
    private final ApplicationContext ctx;

    /**
     * 构造服务端：向 IoC 容器注册所有 Bean，不触发实例化。
     *
     * @param nodeId     节点 ID（集群内唯一）
     * @param clientPort 客户端连接端口
     * @param raftPort   Raft 节点间通信端口
     * @param config     配置提供者
     */
    public HutuLockServer(String nodeId, int clientPort, int raftPort, ConfigProvider config) {
        this.nodeId      = nodeId;
        this.clientPort  = clientPort;
        this.ctx         = new ApplicationContext();
        ServerBeanFactory.register(ctx, nodeId, raftPort, config);
    }

    /**
     * 替换安全上下文（在 {@link #start()} 之前调用）。
     * 覆盖 {@link ServerBeanFactory} 中注册的默认安全上下文。
     *
     * @param secCtx 自定义安全上下文
     * @return this（链式调用）
     */
    public HutuLockServer withSecurity(SecurityContext secCtx) {
        ctx.register(BeanDefinition.of("securityContext", SecurityContext.class, () -> secCtx));
        log.info("Custom security context registered: enabled={}", secCtx.isEnabled());
        return this;
    }

    /** 添加 Raft 集群节点（在 {@link #start()} 之前调用）。 */
    public void addPeer(String peerId, String host, int raftPort) {
        ctx.getBean(RaftNode.class).addPeer(peerId, host, raftPort);
    }

    /**
     * 启动服务端（阻塞直到关闭）。
     *
     * <p>容器按注册顺序启动所有 {@link com.hutulock.server.ioc.Lifecycle} Bean，
     * 关闭时按逆序 shutdown。
     *
     * @throws Exception 启动失败
     */
    public void start() throws Exception {
        ctx.start();

        ServerProperties props = ctx.getBean(ServerProperties.class);
        NettyServerConfig nettyConfig = NettyServerConfig.from(props);

        ChannelPipelineFactory pipelineFactory = new ChannelPipelineFactory(
            nettyConfig,
            new SecurityChannelHandler(ctx.getBean(SecurityContext.class)),
            ctx.getBean(LockServerHandler.class)
        );

        bind(nettyConfig, pipelineFactory, props);
    }

    private void bind(NettyServerConfig cfg, ChannelPipelineFactory pipeline,
                      ServerProperties props) throws InterruptedException {
        EventLoopGroup boss   = new NioEventLoopGroup(cfg.bossThreads);
        EventLoopGroup worker = new NioEventLoopGroup(cfg.workerThreads);
        try {
            ChannelFuture future = new ServerBootstrap()
                .group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, cfg.soBacklog)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(pipeline)
                .bind(clientPort).sync();

            log.info("HutuLockServer [{}] started — clientPort={}, tls={}, security={}, metricsPort={}",
                nodeId, clientPort, props.tlsEnabled, props.securityEnabled,
                props.metricsEnabled ? props.metricsPort : "disabled");

            future.channel().closeFuture().sync();
        } finally {
            shutdownNetty(boss, worker);
            ctx.close();
        }
    }

    private void shutdownNetty(EventLoopGroup boss, EventLoopGroup worker) {
        try {
            boss.shutdownGracefully(0, 3, TimeUnit.SECONDS).sync();
            worker.shutdownGracefully(0, 3, TimeUnit.SECONDS).sync();
        } catch (InterruptedException e) {
            boss.shutdownNow();
            worker.shutdownNow();
            Thread.currentThread().interrupt();
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

        ConfigProvider config = new YamlConfigProvider();
        HutuLockServer server = new HutuLockServer(nodeId, clientPort, raftPort, config);
        for (int i = 3; i < args.length; i++) {
            String[] peer = args[i].split(":");
            server.addPeer(peer[0], peer[1], Integer.parseInt(peer[2]));
        }
        server.start();
    }
}
