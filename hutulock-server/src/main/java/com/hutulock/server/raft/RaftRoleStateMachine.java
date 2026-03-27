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
package com.hutulock.server.raft;

import com.hutulock.model.util.StateMachine;

/**
 * Raft 节点角色状态机
 *
 * <p>合法转换：
 * <pre>
 *   FOLLOWER  → CANDIDATE          （选举超时）
 *   CANDIDATE → LEADER             （赢得多数票）
 *   CANDIDATE → FOLLOWER           （收到更高 term 或选举失败）
 *   LEADER    → FOLLOWER           （收到更高 term，降级）
 * </pre>
 *
 * <p>注意：LEADER → CANDIDATE 不合法，Leader 降级必须先回到 FOLLOWER。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class RaftRoleStateMachine implements StateMachine<RaftNode.Role> {

    public static final RaftRoleStateMachine INSTANCE = new RaftRoleStateMachine();

    private RaftRoleStateMachine() {}

    @Override
    public boolean canTransit(RaftNode.Role from, RaftNode.Role to) {
        switch (from) {
            case FOLLOWER:  return to == RaftNode.Role.CANDIDATE;
            case CANDIDATE: return to == RaftNode.Role.LEADER
                                || to == RaftNode.Role.FOLLOWER;
            case LEADER:    return to == RaftNode.Role.FOLLOWER;
            default:        return false;
        }
    }

    /**
     * 执行角色转换，记录日志但不抛异常（Raft 中非法转换通常是并发竞态，应静默忽略）。
     *
     * @return 转换是否成功
     */
    public boolean tryTransit(RaftState state, RaftNode.Role to) {
        if (!canTransit(state.role, to)) {
            return false;
        }
        state.role = to;
        return true;
    }
}
