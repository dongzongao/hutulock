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
import com.hutulock.server.impl.DefaultZNodeTree;
import com.hutulock.server.ioc.ApplicationContext;
import com.hutulock.server.ioc.BeanDefinition;
import com.hutulock.server.ioc.BeanModule;
import com.hutulock.server.persistence.SnapshotManager;
import com.hutulock.server.raft.RaftNode;
import com.hutulock.spi.event.EventBus;
import com.hutulock.spi.metrics.MetricsCollector;
import com.hutulock.spi.storage.ZNodeStorage;

import java.io.File;

/**
 * Raft 共识层模块
 *
 * <p>负责注册 Raft 节点，并在存储层支持快照时注入快照管理器。
 *
 * <p>依赖：BusinessModule（lockManager）、InfraModule（metrics、eventBus、serverProperties）
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class RaftModule implements BeanModule {

    private final String nodeId;
    private final int    raftPort;

    public RaftModule(String nodeId, int raftPort) {
        this.nodeId    = nodeId;
        this.raftPort  = raftPort;
    }

    @Override
    public void register(ApplicationContext ctx) {
        ctx.register(BeanDefinition.of("raftNode", RaftNode.class, () -> {
            String dataDir = System.getProperty("hutulock.dataDir",
                    "data" + File.separator + nodeId);

            RaftNode node = new RaftNode(
                    nodeId, raftPort,
                    ctx.getBean(DefaultLockManager.class),
                    ctx.getBean(ServerProperties.class),
                    ctx.getBean(MetricsCollector.class),
                    ctx.getBean(EventBus.class),
                    dataDir);

            // 注入快照管理器（仅当存储层是 DefaultZNodeTree 时支持）
            ZNodeStorage storage = ctx.getBean(ZNodeStorage.class);
            if (storage instanceof DefaultZNodeTree) {
                SnapshotManager snapMgr = new SnapshotManager(dataDir);
                node.setSnapshotManager(snapMgr, (DefaultZNodeTree) storage);
            }
            return node;
        }));
    }
}
