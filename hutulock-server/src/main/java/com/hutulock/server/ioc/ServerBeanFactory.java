/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server.ioc;

import com.hutulock.config.api.ConfigProvider;
import com.hutulock.config.api.ServerProperties;
import com.hutulock.spi.event.EventBus;
import com.hutulock.spi.metrics.MetricsCollector;
import com.hutulock.spi.security.Authenticator;
import com.hutulock.spi.security.Authorizer;
import com.hutulock.spi.session.SessionTracker;
import com.hutulock.spi.storage.WatcherRegistry;
import com.hutulock.spi.storage.ZNodeStorage;
import com.hutulock.server.LockServerHandler;
import com.hutulock.server.event.DefaultEventBus;
import com.hutulock.server.impl.*;
import com.hutulock.server.metrics.MetricsHttpServer;
import com.hutulock.server.metrics.PrometheusMetricsCollector;
import com.hutulock.server.raft.RaftNode;
import com.hutulock.server.security.*;
import com.hutulock.proxy.support.ProxyBuilder;

/**
 * 服务端 Bean 工厂
 *
 * <p>将所有服务端组件的依赖关系集中声明，注册到 {@link ApplicationContext}。
 * 遵循"依赖倒置"原则：上层组件依赖 SPI 接口，而非具体实现类。
 *
 * <p>组件注册顺序（从底层到上层）：
 * <pre>
 *   config → serverProperties
 *     → eventBus
 *     → metrics → metricsHttpServer
 *     → watcherRegistry
 *     → zNodeStorage
 *     → sessionTracker
 *     → lockManager
 *     → raftNode
 *     → securityContext
 *     → lockServerHandler
 * </pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class ServerBeanFactory {

    private ServerBeanFactory() {}

    /**
     * 向容器注册所有服务端 Bean。
     *
     * @param ctx      IoC 容器
     * @param nodeId   节点 ID
     * @param raftPort Raft 通信端口
     * @param config   配置提供者
     */
    public static void register(ApplicationContext ctx, String nodeId, int raftPort, ConfigProvider config) {

        // ---- 1. 配置 ----
        ctx.register(BeanDefinition.of("serverProperties", ServerProperties.class,
                () -> config.getServerProperties()));

        // ---- 2. 事件总线 ----
        ctx.register(BeanDefinition.of("eventBus", EventBus.class,
                () -> ProxyBuilder.wrap(EventBus.class, new DefaultEventBus())
                        .withLogging()
                        .build()));

        // ---- 3. Metrics ----
        ctx.register(BeanDefinition.of("metrics", MetricsCollector.class, () -> {
            ServerProperties props = ctx.getBean(ServerProperties.class);
            if (props.metricsEnabled) {
                return new PrometheusMetricsCollector(nodeId);
            }
            return MetricsCollector.noop();
        }));

        ctx.register(BeanDefinition.of("metricsHttpServer", MetricsHttpServer.class, () -> {
            ServerProperties props = ctx.getBean(ServerProperties.class);
            if (!props.metricsEnabled) return null;
            MetricsCollector metrics = ctx.getBean(MetricsCollector.class);
            if (metrics instanceof PrometheusMetricsCollector) {
                return new MetricsHttpServer(props.metricsPort, nodeId, (PrometheusMetricsCollector) metrics);
            }
            return null;
        }));

        // ---- 4. 存储层 ----
        ctx.register(BeanDefinition.of("watcherRegistry", WatcherRegistry.class,
                () -> new DefaultWatcherRegistry(ctx.getBean(MetricsCollector.class))));

        ctx.register(BeanDefinition.of("zNodeStorage", ZNodeStorage.class, () -> {
            DefaultZNodeTree tree = new DefaultZNodeTree(
                    ctx.getBean(WatcherRegistry.class),
                    ctx.getBean(MetricsCollector.class),
                    ctx.getBean(EventBus.class));
            tree.setNodeId(nodeId);
            return ProxyBuilder.wrap(ZNodeStorage.class, tree)
                    .withLogging()
                    .withMetrics()
                    .build();
        }));

        // ---- 5. 会话层 ----
        // 先注册真实实现（容器可感知其 Lifecycle）
        ctx.register(BeanDefinition.of("sessionManager", DefaultSessionManager.class, () -> {
            DefaultSessionManager mgr = new DefaultSessionManager(
                    ctx.getBean(ZNodeStorage.class),
                    ctx.getBean(MetricsCollector.class),
                    ctx.getBean(EventBus.class),
                    ctx.getBean(ServerProperties.class));
            mgr.setNodeId(nodeId);
            return mgr;
        }));
        // 对外暴露代理版本（日志增强）
        ctx.register(BeanDefinition.of("sessionTracker", SessionTracker.class,
                () -> ProxyBuilder.wrap(SessionTracker.class, ctx.getBean(DefaultSessionManager.class))
                        .withLogging()
                        .build()));

        // ---- 6. 锁管理（同时作为 Raft 状态机）----
        ctx.register(BeanDefinition.of("lockManager", DefaultLockManager.class, () -> {
            DefaultLockManager mgr = new DefaultLockManager(
                    ctx.getBean(ZNodeStorage.class),
                    ctx.getBean(SessionTracker.class),
                    ctx.getBean(MetricsCollector.class),
                    ctx.getBean(EventBus.class));
            mgr.setNodeId(nodeId);
            return mgr;
        }));
        ctx.register(BeanDefinition.of("raftNode", RaftNode.class,
                () -> new RaftNode(nodeId, raftPort,
                        ctx.getBean(DefaultLockManager.class),
                        ctx.getBean(ServerProperties.class),
                        ctx.getBean(MetricsCollector.class),
                        ctx.getBean(EventBus.class))));

        // ---- 7. 安全上下文 ----
        ctx.register(BeanDefinition.of("securityContext", SecurityContext.class, () -> {
            ServerProperties props = ctx.getBean(ServerProperties.class);
            if (!props.securityEnabled) {
                return SecurityContext.disabled();
            }
            return SecurityContext.builder()
                    .authenticator(Authenticator.allowAll())
                    .authorizer(Authorizer.allowAll())
                    .auditLogger(new Slf4jAuditLogger())
                    .rateLimiter(new TokenBucketRateLimiter(props.rateLimitQps, props.rateLimitBurst))
                    .build();
        }));

        // ---- 8. 网络层 ----
        ctx.register(BeanDefinition.of("lockServerHandler", LockServerHandler.class,
                () -> new LockServerHandler(
                        ctx.getBean(DefaultLockManager.class),
                        ctx.getBean(SessionTracker.class),
                        ctx.getBean(RaftNode.class),
                        ctx.getBean(ServerProperties.class))));
    }
}
