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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Raft 集群成员配置（不可变值对象）
 *
 * <p>支持两种状态（Raft §6 Joint Consensus）：
 * <ul>
 *   <li><b>NORMAL</b>：稳定配置，只有 {@code oldMembers}，{@code newMembers} 为空。
 *       多数派 = floor(|old|/2) + 1。</li>
 *   <li><b>JOINT</b>：过渡配置（C_old,new），同时持有 {@code oldMembers} 和 {@code newMembers}。
 *       多数派 = old 多数派 AND new 多数派（两个多数派都必须同意）。</li>
 * </ul>
 *
 * <p>成员变更流程（两阶段）：
 * <pre>
 *   1. Leader propose C_old,new（JOINT）→ 复制到多数派 → apply
 *   2. Leader propose C_new（NORMAL）→ 复制到多数派 → apply
 *   3. 不在 C_new 中的节点（含旧 Leader）自动下线
 * </pre>
 *
 * <p>安全性保证：
 * <ul>
 *   <li>JOINT 阶段任何决策（选举/提交）都需要 C_old 和 C_new 各自的多数派同意，
 *       不存在两个独立多数派同时做出不同决策的可能。</li>
 *   <li>配置变更本身作为 Raft 日志条目复制，保证所有节点按相同顺序看到配置变更。</li>
 * </ul>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class ClusterConfig {

    public enum Phase { NORMAL, JOINT }

    /** 当前（旧）成员集合，NORMAL 阶段即为全部成员 */
    public final Set<String> oldMembers;
    /** 新成员集合，JOINT 阶段非空，NORMAL 阶段为空 */
    public final Set<String> newMembers;
    /** 当前配置阶段 */
    public final Phase phase;

    private ClusterConfig(Set<String> oldMembers, Set<String> newMembers, Phase phase) {
        this.oldMembers = Collections.unmodifiableSet(new LinkedHashSet<>(oldMembers));
        this.newMembers = Collections.unmodifiableSet(new LinkedHashSet<>(newMembers));
        this.phase      = phase;
    }

    /** 创建稳定配置（NORMAL）。 */
    public static ClusterConfig normal(Set<String> members) {
        return new ClusterConfig(members, Collections.emptySet(), Phase.NORMAL);
    }

    /** 创建联合配置（JOINT），用于过渡阶段。 */
    public static ClusterConfig joint(Set<String> oldMembers, Set<String> newMembers) {
        return new ClusterConfig(oldMembers, newMembers, Phase.JOINT);
    }

    /** 是否处于联合共识过渡阶段。 */
    public boolean isJoint() { return phase == Phase.JOINT; }

    /**
     * 判断给定的 matchIndex 集合是否满足多数派要求。
     *
     * <p>NORMAL 阶段：只需 oldMembers 多数派。
     * <p>JOINT 阶段：需要 oldMembers 多数派 AND newMembers 多数派（两个都满足）。
     *
     * @param selfId      本节点 ID（自身算一票）
     * @param matchCounts 各节点 ID → matchIndex 的映射
     * @param threshold   需要达到的 matchIndex 阈值
     */
    public boolean hasQuorum(String selfId, java.util.Map<String, Integer> matchCounts, int threshold) {
        boolean oldQuorum = countQuorum(selfId, oldMembers, matchCounts, threshold);
        if (!oldQuorum) return false;
        if (phase == Phase.NORMAL) return true;
        return countQuorum(selfId, newMembers, matchCounts, threshold);
    }

    private boolean countQuorum(String selfId, Set<String> members,
                                 java.util.Map<String, Integer> matchCounts, int threshold) {
        if (members.isEmpty()) return true;
        int count = 0;
        for (String id : members) {
            int match = id.equals(selfId) ? Integer.MAX_VALUE
                : matchCounts.getOrDefault(id, 0);
            if (match >= threshold) count++;
        }
        return count > members.size() / 2;
    }

    /**
     * 所有参与投票的成员（JOINT 阶段为 old ∪ new）。
     */
    public Set<String> allVoters() {
        if (phase == Phase.NORMAL) return oldMembers;
        Set<String> all = new LinkedHashSet<>(oldMembers);
        all.addAll(newMembers);
        return Collections.unmodifiableSet(all);
    }

    /**
     * 判断选票是否满足多数派（用于选举）。
     *
     * <p>JOINT 阶段：需要 C_old 和 C_new 各自的多数派都投票给同一候选人。
     *
     * @param voters 已投票给本候选人的节点集合（含自身）
     */
    public boolean hasElectionQuorum(String selfId, Set<String> voters) {
        boolean oldQ = countElectionQuorum(selfId, oldMembers, voters);
        if (!oldQ) return false;
        if (phase == Phase.NORMAL) return true;
        return countElectionQuorum(selfId, newMembers, voters);
    }

    private boolean countElectionQuorum(String selfId, Set<String> members, Set<String> voters) {
        if (members.isEmpty()) return true;
        int count = 0;
        for (String id : members) {
            if (voters.contains(id) || id.equals(selfId)) count++;
        }
        return count > members.size() / 2;
    }

    @Override
    public String toString() {
        if (phase == Phase.NORMAL) return "ClusterConfig{NORMAL, members=" + oldMembers + "}";
        return "ClusterConfig{JOINT, old=" + oldMembers + ", new=" + newMembers + "}";
    }
}
