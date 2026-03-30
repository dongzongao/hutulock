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

import com.hutulock.config.api.ConfigProvider;
import com.hutulock.config.api.ServerProperties;
import com.hutulock.server.event.DefaultEventBus;
import com.hutulock.server.ioc.ApplicationContext;
import com.hutulock.server.ioc.BeanDefinition;
import com.hutulock.server.ioc.BeanModule;
import com.hutulock.server.mem.MemoryManager;
import com.hutulock.server.metrics.MetricsHttpServer;
import com.hutulock.server.metrics.PrometheusMetricsCollector;
import com.hutulock.spi.event.EventBus;
import com.hutulock.spi.metrics.MetricsCollector;
import com.hutulock.proxy.support.ProxyConfigurator;

/**
 * 基础设施层模块
 *
 * <p>负责注册最底层、无业务依赖的基础组件：
 * <ul>
 *   <li>配置（ServerProperties）</li>
 *   <li>内存管理器（MemoryManager）</li>
 *   <li>事件总线（EventBus）</li>
 *   <li>指标收集（MetricsCollector + MetricsHttpServer）</li>
 * </ul>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class InfraModule implements BeanModule {

    private final String          nodeId;
    private final ConfigProvider  config;
    private final ProxyConfigurator proxy;

    public InfraModule(String nodeId, ConfigProvider config, ProxyConfigurator proxy) {
        this.nodeId  = nodeId;
        this.config  = config;
        this.proxy   = proxy;
    }

    @Override
    public void register(ApplicationContext ctx) {
        // ---- 配置 ----
        ctx.register(BeanDefinition.of("serverProperties", ServerProperties.class,
                () -> config.getServerProperties()));

        // ---- 内存管理器 ----
        ctx.register(BeanDefinition.of("memoryManager", MemoryManager.class, () -> {
            ServerProperties props = ctx.getBean(ServerProperties.class);
            return new MemoryManager(props.lockTokenPoolSize);
        }));

        // ---- 事件总线 ----
        ctx.register(BeanDefinition.of("eventBus", EventBus.class, () -> {
            DefaultEventBus bus = new DefaultEventBus();
            return proxy.apply(EventBus.class, bus);
        }));

        // ---- 指标收集 ----
        ctx.register(BeanDefinition.of("metrics", MetricsCollector.class, () -> {
            ServerProperties props = ctx.getBean(ServerProperties.class);
            return props.metricsEnabled
                    ? new PrometheusMetricsCollector(nodeId)
                    : MetricsCollector.noop();
        }));

        ctx.register(BeanDefinition.of("metricsHttpServer", MetricsHttpServer.class, () -> {
            ServerProperties props = ctx.getBean(ServerProperties.class);
            if (!props.metricsEnabled) return null;
            MetricsCollector metrics = ctx.getBean(MetricsCollector.class);
            return (metrics instanceof PrometheusMetricsCollector)
                    ? new MetricsHttpServer(props.metricsPort, nodeId, (PrometheusMetricsCollector) metrics)
                    : null;
        }));
    }
}
