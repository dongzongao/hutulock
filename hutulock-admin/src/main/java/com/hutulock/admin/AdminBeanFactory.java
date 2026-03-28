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
package com.hutulock.admin;

import com.hutulock.config.api.ServerProperties;
import com.hutulock.server.ioc.ApplicationContext;
import com.hutulock.server.ioc.BeanDefinition;
import com.hutulock.server.impl.DefaultSessionManager;
import com.hutulock.server.impl.DefaultZNodeTree;
import com.hutulock.server.raft.RaftNode;
import com.hutulock.spi.storage.ZNodeStorage;

/**
 * Registers the AdminApiServer bean into the ApplicationContext.
 *
 * <p>Must be called from the hutulock-admin module (not hutulock-server)
 * to avoid a circular dependency.
 */
public final class AdminBeanFactory {

    private AdminBeanFactory() {}

    public static void register(ApplicationContext ctx, String nodeId) {
        ctx.register(BeanDefinition.of("adminHttpServer", AdminApiServer.class, () -> {
            ServerProperties props = ctx.getBean(ServerProperties.class);
            if (!props.adminEnabled) return null;
            ZNodeStorage storage = ctx.getBean(ZNodeStorage.class);
            DefaultZNodeTree tree = (storage instanceof DefaultZNodeTree)
                ? (DefaultZNodeTree) storage : null;
            DefaultSessionManager sessionMgr = ctx.getBean(DefaultSessionManager.class);
            return new AdminApiServer(
                props.adminPort, nodeId,
                ctx.getBean(RaftNode.class),
                sessionMgr,
                tree);
        }));
    }
}
