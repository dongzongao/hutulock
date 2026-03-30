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

import com.hutulock.server.impl.DefaultWatcherRegistry;
import com.hutulock.server.impl.DefaultZNodeTree;
import com.hutulock.server.ioc.ApplicationContext;
import com.hutulock.server.ioc.BeanDefinition;
import com.hutulock.server.ioc.BeanModule;
import com.hutulock.server.mem.MemoryManager;
import com.hutulock.spi.event.EventBus;
import com.hutulock.spi.metrics.MetricsCollector;
import com.hutulock.spi.storage.WatcherRegistry;
import com.hutulock.spi.storage.ZNodeStorage;
import com.hutulock.proxy.support.ProxyConfigurator;

/**
 * 存储层模块
 *
 * <p>负责注册 ZNode 树和 Watcher 注册表：
 * <ul>
 *   <li>{@link WatcherRegistry} — 监听器注册表</li>
 *   <li>{@link ZNodeStorage} — ZNode 存储（含内存管理器集成）</li>
 * </ul>
 *
 * <p>依赖：InfraModule（metrics、eventBus、memoryManager）
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class StorageModule implements BeanModule {

    private final String          nodeId;
    private final ProxyConfigurator proxy;

    public StorageModule(String nodeId, ProxyConfigurator proxy) {
        this.nodeId  = nodeId;
        this.proxy   = proxy;
    }

    @Override
    public void register(ApplicationContext ctx) {
        ctx.register(BeanDefinition.of("watcherRegistry", WatcherRegistry.class,
                () -> new DefaultWatcherRegistry(ctx.getBean(MetricsCollector.class))));

        ctx.register(BeanDefinition.of("zNodeStorage", ZNodeStorage.class, () -> {
            DefaultZNodeTree tree = new DefaultZNodeTree(
                    ctx.getBean(WatcherRegistry.class),
                    ctx.getBean(MetricsCollector.class),
                    ctx.getBean(EventBus.class),
                    ctx.getBean(MemoryManager.class));
            tree.setNodeId(nodeId);
            return proxy.apply(ZNodeStorage.class, tree);
        }));
    }
}
