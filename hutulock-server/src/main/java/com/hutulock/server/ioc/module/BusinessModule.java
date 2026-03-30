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
package com.hutulock.server.ioc.module;

import com.hutulock.config.api.ServerProperties;
import com.hutulock.server.impl.DefaultLockManager;
import com.hutulock.server.impl.DefaultSessionManager;
import com.hutulock.server.ioc.ApplicationContext;
import com.hutulock.server.ioc.BeanDefinition;
import com.hutulock.server.ioc.BeanModule;
import com.hutulock.spi.event.EventBus;
import com.hutulock.spi.metrics.MetricsCollector;
import com.hutulock.spi.session.SessionTracker;
import com.hutulock.spi.storage.ZNodeStorage;
import com.hutulock.proxy.support.ProxyConfigurator;

/**
 * 业务层模块
 *
 * <p>负责注册会话管理和锁管理：
 * <ul>
 *   <li>{@link DefaultSessionManager} — 会话管理实现（Lifecycle Bean）</li>
 *   <li>{@link SessionTracker} — 对外暴露的会话接口（可选代理增强）</li>
 *   <li>{@link DefaultLockManager} — 锁管理实现（同时作为 Raft 状态机）</li>
 * </ul>
 *
 * <p>依赖：StorageModule（zNodeStorage）、InfraModule（metrics、eventBus、serverProperties）
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class BusinessModule implements BeanModule {

    private final String          nodeId;
    private final ProxyConfigurator proxy;

    public BusinessModule(String nodeId, ProxyConfigurator proxy) {
        this.nodeId  = nodeId;
        this.proxy   = proxy;
    }

    @Override
    public void register(ApplicationContext ctx) {
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
            return proxy.apply(SessionTracker.class, mgr);
        }));

        ctx.register(BeanDefinition.of("lockManager", DefaultLockManager.class, () -> {
            DefaultLockManager mgr = new DefaultLockManager(
                    ctx.getBean(ZNodeStorage.class),
                    ctx.getBean(SessionTracker.class),
                    ctx.getBean(MetricsCollector.class),
                    ctx.getBean(EventBus.class));
            mgr.setNodeId(nodeId);
            return mgr;
        }));
    }
}
