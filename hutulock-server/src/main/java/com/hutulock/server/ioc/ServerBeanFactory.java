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

    /**
     * 系统属性：控制启用的代理类型，逗号分隔。
     *
     * <p>示例：{@code -Dhutulock.proxy=logging,metrics}
     * <ul>
     *   <li>{@code logging} — 方法调用日志（入参、耗时、异常）</li>
     *   <li>{@code metrics} — 调用次数、失败次数、平均耗时统计</li>
     *   <li>{@code all}     — 等价于 logging,metrics</li>
     * </ul>
     * 不设置或设为空则不启用任何代理（生产默认行为）。
     */
    public static final String PROXY_PROP = "hutulock.proxy";

    private ServerBeanFactory() {}

    /** 判断是否启用了指定代理类型（读取系统属性）。 */
    private static boolean proxyEnabled(String type) {
        String val = System.getProperty(PROXY_PROP, "").toLowerCase();
        return val.contains(type) || val.contains("all");
    }

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

        // ---- 1b. 内存管理器（路径缓存 + 对象池）----
        ctx.register(BeanDefinition.of("memoryManager", com.hutulock.server.mem.MemoryManager.class,
                () -> new com.hutulock.server.mem.MemoryManager()));

        // ---- 2. 事件总线 ----
        ctx.register(BeanDefinition.of("eventBus", EventBus.class, () -> {
            DefaultEventBus bus = new DefaultEventBus();
            if (!proxyEnabled("logging")) return bus;
            return ProxyBuilder.wrap(EventBus.class, bus).withLogging().build();
        }));

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
                    ctx.getBean(EventBus.class),
                    ctx.getBean(com.hutulock.server.mem.MemoryManager.class));
            tree.setNodeId(nodeId);
            ZNodeStorage storage = tree;  // 显式向上转型，帮助泛型推断
            ProxyBuilder<ZNodeStorage> builder = ProxyBuilder.wrap(ZNodeStorage.class, storage);
            if (proxyEnabled("logging")) builder.withLogging();
            if (proxyEnabled("metrics")) builder.withMetrics();
            return (proxyEnabled("logging") || proxyEnabled("metrics")) ? builder.build() : storage;
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
        // 对外暴露代理版本（日志增强，可选）
        ctx.register(BeanDefinition.of("sessionTracker", SessionTracker.class, () -> {
            DefaultSessionManager mgr = ctx.getBean(DefaultSessionManager.class);
            if (!proxyEnabled("logging")) return mgr;
            return ProxyBuilder.wrap(SessionTracker.class, mgr).withLogging().build();
        }));

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

        ctx.register(BeanDefinition.of("raftNode", RaftNode.class, () -> {
            // dataDir 从系统属性读取，默认 ./data/{nodeId}
            String dataDir = System.getProperty("hutulock.dataDir",
                "data" + java.io.File.separator + nodeId);
            RaftNode node = new RaftNode(nodeId, raftPort,
                    ctx.getBean(DefaultLockManager.class),
                    ctx.getBean(ServerProperties.class),
                    ctx.getBean(MetricsCollector.class),
                    ctx.getBean(EventBus.class),
                    dataDir);

            // 注入快照管理器（ZNodeTree 实现才支持快照）
            ZNodeStorage storage = ctx.getBean(ZNodeStorage.class);
            DefaultZNodeTree tree = (storage instanceof DefaultZNodeTree)
                ? (DefaultZNodeTree) storage : null;
            if (tree != null) {
                com.hutulock.server.persistence.SnapshotManager snapMgr =
                    new com.hutulock.server.persistence.SnapshotManager(dataDir);
                node.setSnapshotManager(snapMgr, tree);
            }
            return node;
        }));

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

        // ---- 9. Admin console ----
        // Note: AdminApiServer is registered externally by hutulock-admin module
        // ---- 9. Admin console ----
        ctx.register(BeanDefinition.of("adminHttpServer", com.hutulock.server.admin.AdminHttpServer.class, () -> {
            ServerProperties props = ctx.getBean(ServerProperties.class);
            if (!props.adminEnabled) return null;
            ZNodeStorage storage = ctx.getBean(ZNodeStorage.class);
            DefaultZNodeTree tree = (storage instanceof DefaultZNodeTree)
                ? (DefaultZNodeTree) storage : null;
            DefaultSessionManager sessionMgr = ctx.getBean(DefaultSessionManager.class);
            return new com.hutulock.server.admin.AdminHttpServer(
                props.adminPort, nodeId,
                ctx.getBean(RaftNode.class),
                sessionMgr,
                tree);
        }));
    }
}
