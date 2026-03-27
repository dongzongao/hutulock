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
package com.hutulock.model.znode;

/**
 * ZNode 类型，枚举，定义节点的生命周期和序号行为。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public enum ZNodeType {
    /** 持久节点 */
    PERSISTENT,
    /** 临时节点（会话断开后自动删除） */
    EPHEMERAL,
    /** 持久顺序节点 */
    PERSISTENT_SEQ,
    /** 临时顺序节点（分布式锁核心） */
    EPHEMERAL_SEQ
}
