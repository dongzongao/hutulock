/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.model.znode;

/**
 * ZNode 类型，对标 ZooKeeper 的 CreateMode。
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
