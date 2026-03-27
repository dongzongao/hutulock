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
package com.hutulock.spi.storage;

import com.hutulock.model.znode.ZNode;
import com.hutulock.model.znode.ZNodePath;
import com.hutulock.model.znode.ZNodeType;
import io.netty.channel.Channel;

import java.util.List;

/**
 * ZNode 存储接口（SPI 边界契约）
 *
 * <p>定义 ZNode 树形存储的操作语义。
 * 所有写操作执行后触发对应的 Watcher 事件。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public interface ZNodeStorage {

    /**
     * 创建 ZNode。顺序节点路径末尾自动追加 10 位序号。
     *
     * @return 实际创建的路径（顺序节点含序号后缀）
     */
    ZNodePath create(ZNodePath path, ZNodeType type, byte[] data, String sessionId);

    /** 删除 ZNode，触发 NODE_DELETED 事件。 */
    void delete(ZNodePath path);

    /** 更新数据，version=-1 表示不校验版本。 */
    void setData(ZNodePath path, byte[] data, int version);

    ZNode get(ZNodePath path);
    boolean exists(ZNodePath path);

    /** 获取子节点列表，按顺序节点序号升序排列。 */
    List<ZNodePath> getChildren(ZNodePath path);

    /** 注册 Watcher（One-shot）。 */
    void watch(ZNodePath path, Channel channel);

    /** 会话过期时清理临时节点，返回被删除的路径列表。 */
    List<ZNodePath> cleanupSession(String sessionId);

    int size();
}
