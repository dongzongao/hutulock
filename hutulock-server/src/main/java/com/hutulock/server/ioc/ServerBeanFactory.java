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
import com.hutulock.proxy.support.ProxyConfigurator;
import com.hutulock.server.ioc.module.*;

/**
 * 服务端 Bean 工厂
 *
 * <p>将各层模块按依赖顺序安装到容器，自身不包含任何 Bean 创建逻辑。
 *
 * <p>模块安装顺序（从底层到上层）：
 * <pre>
 *   InfraModule    — 配置、内存、事件总线、指标
 *   StorageModule  — WatcherRegistry、ZNodeStorage
 *   BusinessModule — SessionManager、LockManager
 *   RaftModule     — RaftNode（含快照管理器）
 *   NetworkModule  — SecurityContext、LockServerHandler、AdminHttpServer
 * </pre>
 *
 * <p>替换某一层只需在 {@code install} 之后覆盖注册对应 Bean，
 * 或直接传入自定义 {@link BeanModule} 实现。
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
     */
    public static final String PROXY_PROP = "hutulock.proxy";

    private ServerBeanFactory() {}

    /**
     * 向容器安装所有服务端模块。
     *
     * @param ctx      IoC 容器
     * @param nodeId   节点 ID
     * @param raftPort Raft 通信端口
     * @param config   配置提供者
     */
    public static void register(ApplicationContext ctx, String nodeId, int raftPort, ConfigProvider config) {
        ProxyConfigurator proxy = ProxyConfigurator.fromSystemProperty();

        ctx.install(new InfraModule(nodeId, config, proxy))
           .install(new StorageModule(nodeId, proxy))
           .install(new BusinessModule(nodeId, proxy))
           .install(new RaftModule(nodeId, raftPort))
           .install(new NetworkModule(nodeId));
    }
}
