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
import com.hutulock.server.LockServerHandler;
import com.hutulock.server.admin.AdminHttpServer;
import com.hutulock.server.impl.DefaultLockManager;
import com.hutulock.server.impl.DefaultSessionManager;
import com.hutulock.server.impl.DefaultZNodeTree;
import com.hutulock.server.ioc.ApplicationContext;
import com.hutulock.server.ioc.BeanDefinition;
import com.hutulock.server.ioc.BeanModule;
import com.hutulock.server.raft.RaftNode;
import com.hutulock.server.security.*;
import com.hutulock.spi.security.Authenticator;
import com.hutulock.spi.security.Authorizer;
import com.hutulock.spi.session.SessionTracker;
import com.hutulock.spi.storage.ZNodeStorage;

/**
 * 网络层模块
 *
 * <p>负责注册安全上下文、请求处理器和 Admin 控制台：
 * <ul>
 *   <li>{@link SecurityContext} — 认证、授权、限流、审计</li>
 *   <li>{@link LockServerHandler} — 客户端请求处理器</li>
 *   <li>{@link AdminHttpServer} — 管理控制台（可选）</li>
 * </ul>
 *
 * <p>依赖：RaftModule、BusinessModule、InfraModule
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class NetworkModule implements BeanModule {

    private final String nodeId;

    public NetworkModule(String nodeId) {
        this.nodeId = nodeId;
    }

    @Override
    public void register(ApplicationContext ctx) {
        registerSecurity(ctx);
        registerHandler(ctx);
        registerAdmin(ctx);
    }

    private void registerSecurity(ApplicationContext ctx) {
        ctx.register(BeanDefinition.of("securityContext", SecurityContext.class, () -> {
            ServerProperties props = ctx.getBean(ServerProperties.class);
            if (!props.securityEnabled) return SecurityContext.disabled();
            return SecurityContext.builder()
                    .authenticator(Authenticator.allowAll())
                    .authorizer(Authorizer.allowAll())
                    .auditLogger(new Slf4jAuditLogger())
                    .rateLimiter(new TokenBucketRateLimiter(props.rateLimitQps, props.rateLimitBurst))
                    .build();
        }));
    }

    private void registerHandler(ApplicationContext ctx) {
        ctx.register(BeanDefinition.of("lockServerHandler", LockServerHandler.class, () -> {
            ZNodeStorage storage = ctx.getBean(ZNodeStorage.class);
            DefaultZNodeTree tree = (storage instanceof DefaultZNodeTree) ? (DefaultZNodeTree) storage : null;
            return new LockServerHandler(
                    ctx.getBean(DefaultLockManager.class),
                    ctx.getBean(SessionTracker.class),
                    ctx.getBean(RaftNode.class),
                    tree,
                    ctx.getBean(ServerProperties.class));
        }));
    }

    private void registerAdmin(ApplicationContext ctx) {
        ctx.register(BeanDefinition.of("adminHttpServer", AdminHttpServer.class, () -> {
            ServerProperties props = ctx.getBean(ServerProperties.class);
            if (!props.adminEnabled) return null;
            ZNodeStorage storage = ctx.getBean(ZNodeStorage.class);
            DefaultZNodeTree tree = (storage instanceof DefaultZNodeTree) ? (DefaultZNodeTree) storage : null;
            return new AdminHttpServer(
                    props.adminPort, nodeId,
                    ctx.getBean(RaftNode.class),
                    ctx.getBean(DefaultSessionManager.class),
                    tree,
                    props);
        }));
    }
}
