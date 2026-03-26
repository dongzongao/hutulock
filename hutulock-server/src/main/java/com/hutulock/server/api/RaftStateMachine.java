/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server.api;

/**
 * Raft 状态机接口（边界契约）
 *
 * <p>Raft 日志条目被多数派确认（commit）后，由状态机执行。
 * 状态机保证所有节点按相同顺序执行相同命令，从而达到一致性。
 *
 * <p>在 HutuLock 中，状态机的实现是 {@link com.hutulock.server.impl.DefaultLockManager}，
 * 负责执行 LOCK / UNLOCK / RENEW 命令并更新 ZNode 树。
 *
 * <p>实现要求：
 * <ul>
 *   <li>幂等性：相同 index 的命令重复执行结果相同</li>
 *   <li>确定性：相同命令在所有节点产生相同结果</li>
 *   <li>顺序性：按 index 升序执行，不得跳过</li>
 * </ul>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public interface RaftStateMachine {

    /**
     * 应用一条已提交的日志命令。
     *
     * @param index   日志索引（从 1 开始，单调递增）
     * @param command 命令字符串，格式与 {@link com.hutulock.model.protocol.Message} 一致
     */
    void apply(int index, String command);
}
