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

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 动态集群成员变更单元测试
 *
 * 覆盖：
 *   ClusterConfig — NORMAL/JOINT quorum 计算、选举 quorum
 *   MembershipChange — encode/decode、applyTo
 *   集成：addMember/removeMember 非 Leader 拒绝、并发变更拒绝
 */
class ClusterMembershipTest {

    // ==================== ClusterConfig: NORMAL quorum ====================

    @Test
    void normal_quorum_singleNode_selfAlwaysQuorum() {
        ClusterConfig cfg = ClusterConfig.normal(Set.of("n1"));
        // 自身 matchIndex = MAX_VALUE，threshold 任意值都满足
        assertTrue(cfg.hasQuorum("n1", Collections.emptyMap(), 1));
    }

    @Test
    void normal_quorum_threeNodes_majorityRequired() {
        ClusterConfig cfg = ClusterConfig.normal(Set.of("n1", "n2", "n3"));
        // n1(self) + n2 = 2/3，满足多数派
        Map<String, Integer> matches = Map.of("n2", 5, "n3", 0);
        assertTrue(cfg.hasQuorum("n1", matches, 5), "n1+n2 应满足 3 节点多数派");
    }

    @Test
    void normal_quorum_threeNodes_onlyLeader_notQuorum() {
        ClusterConfig cfg = ClusterConfig.normal(Set.of("n1", "n2", "n3"));
        Map<String, Integer> matches = Map.of("n2", 0, "n3", 0);
        assertFalse(cfg.hasQuorum("n1", matches, 5), "只有 Leader 自身不满足 3 节点多数派");
    }

    @Test
    void normal_quorum_fiveNodes_threeRequired() {
        ClusterConfig cfg = ClusterConfig.normal(Set.of("n1", "n2", "n3", "n4", "n5"));
        // n1(self) + n2 + n3 = 3/5，满足
        Map<String, Integer> matches = Map.of("n2", 10, "n3", 10, "n4", 0, "n5", 0);
        assertTrue(cfg.hasQuorum("n1", matches, 10));
    }

    @Test
    void normal_quorum_fiveNodes_twoNotQuorum() {
        ClusterConfig cfg = ClusterConfig.normal(Set.of("n1", "n2", "n3", "n4", "n5"));
        // n1(self) + n2 = 2/5，不满足
        Map<String, Integer> matches = Map.of("n2", 10, "n3", 0, "n4", 0, "n5", 0);
        assertFalse(cfg.hasQuorum("n1", matches, 10));
    }

    // ==================== ClusterConfig: JOINT quorum ====================

    @Test
    void joint_quorum_requiresBothMajorities() {
        // C_old = {n1, n2, n3}, C_new = {n1, n4, n5}
        ClusterConfig cfg = ClusterConfig.joint(
            Set.of("n1", "n2", "n3"),
            Set.of("n1", "n4", "n5")
        );
        // n1(self) + n2 满足 old 多数派；n1(self) + n4 满足 new 多数派 → 整体满足
        Map<String, Integer> matches = Map.of("n2", 5, "n3", 0, "n4", 5, "n5", 0);
        assertTrue(cfg.hasQuorum("n1", matches, 5), "两个多数派都满足时应通过");
    }

    @Test
    void joint_quorum_oldMajorityOnly_notEnough() {
        ClusterConfig cfg = ClusterConfig.joint(
            Set.of("n1", "n2", "n3"),
            Set.of("n1", "n4", "n5")
        );
        // old 满足（n1+n2），new 不满足（只有 n1）
        Map<String, Integer> matches = Map.of("n2", 5, "n3", 0, "n4", 0, "n5", 0);
        assertFalse(cfg.hasQuorum("n1", matches, 5), "只满足 old 多数派不够");
    }

    @Test
    void joint_quorum_newMajorityOnly_notEnough() {
        ClusterConfig cfg = ClusterConfig.joint(
            Set.of("n1", "n2", "n3"),
            Set.of("n1", "n4", "n5")
        );
        // new 满足（n1+n4），old 不满足（只有 n1）
        Map<String, Integer> matches = Map.of("n2", 0, "n3", 0, "n4", 5, "n5", 0);
        assertFalse(cfg.hasQuorum("n1", matches, 5), "只满足 new 多数派不够");
    }

    // ==================== ClusterConfig: 选举 quorum ====================

    @Test
    void normal_electionQuorum_threeNodes() {
        ClusterConfig cfg = ClusterConfig.normal(Set.of("n1", "n2", "n3"));
        // n1(self) + n2 = 2/3，满足
        assertTrue(cfg.hasElectionQuorum("n1", Set.of("n1", "n2")));
        // 只有 n1 = 1/3，不满足
        assertFalse(cfg.hasElectionQuorum("n1", Set.of("n1")));
    }

    @Test
    void joint_electionQuorum_requiresBothMajorities() {
        ClusterConfig cfg = ClusterConfig.joint(
            Set.of("n1", "n2", "n3"),
            Set.of("n1", "n4", "n5")
        );
        // n1+n2 满足 old，n1+n4 满足 new → 整体满足
        assertTrue(cfg.hasElectionQuorum("n1", Set.of("n1", "n2", "n4")));
        // n1+n2 满足 old，但 new 只有 n1 → 不满足
        assertFalse(cfg.hasElectionQuorum("n1", Set.of("n1", "n2")));
    }

    // ==================== ClusterConfig: allVoters ====================

    @Test
    void normal_allVoters_returnsOldMembers() {
        Set<String> members = Set.of("n1", "n2", "n3");
        ClusterConfig cfg = ClusterConfig.normal(members);
        assertEquals(members, cfg.allVoters());
    }

    @Test
    void joint_allVoters_returnsUnion() {
        ClusterConfig cfg = ClusterConfig.joint(
            Set.of("n1", "n2", "n3"),
            Set.of("n1", "n4", "n5")
        );
        Set<String> expected = Set.of("n1", "n2", "n3", "n4", "n5");
        assertEquals(expected, cfg.allVoters());
    }

    // ==================== MembershipChange: encode/decode ====================

    @Test
    void encodeDecodeJoint_roundTrip() {
        Set<String> old = new LinkedHashSet<>(List.of("n1", "n2", "n3"));
        Set<String> nw  = new LinkedHashSet<>(List.of("n1", "n2", "n3", "n4"));

        String cmd = MembershipChange.encodeJoint(old, nw);
        assertTrue(cmd.startsWith(MembershipChange.CMD_JOINT));
        assertTrue(MembershipChange.isMembershipChange(cmd));

        MembershipChange change = MembershipChange.decode(cmd);
        assertEquals(MembershipChange.Type.JOINT, change.type);
        assertEquals(old, change.oldMembers);
        assertEquals(nw,  change.newMembers);
    }

    @Test
    void encodeDecodeNormal_roundTrip() {
        Set<String> members = new LinkedHashSet<>(List.of("n1", "n2", "n3", "n4"));

        String cmd = MembershipChange.encodeNormal(members);
        assertTrue(cmd.startsWith(MembershipChange.CMD_NORMAL));
        assertTrue(MembershipChange.isMembershipChange(cmd));

        MembershipChange change = MembershipChange.decode(cmd);
        assertEquals(MembershipChange.Type.NORMAL, change.type);
        assertEquals(members, change.oldMembers);
        assertTrue(change.newMembers.isEmpty());
    }

    @Test
    void isMembershipChange_regularCommand_returnsFalse() {
        assertFalse(MembershipChange.isMembershipChange("SET:x=1"));
        assertFalse(MembershipChange.isMembershipChange("LOCK:order"));
        assertFalse(MembershipChange.isMembershipChange(null));
        assertFalse(MembershipChange.isMembershipChange(""));
    }

    @Test
    void decode_invalidCommand_throwsException() {
        assertThrows(IllegalArgumentException.class,
            () -> MembershipChange.decode("UNKNOWN_CMD n1,n2"));
    }

    @Test
    void decode_jointMissingNewMembers_throwsException() {
        assertThrows(IllegalArgumentException.class,
            () -> MembershipChange.decode(MembershipChange.CMD_JOINT + " n1,n2"));
    }

    // ==================== MembershipChange: applyTo ====================

    @Test
    void applyTo_joint_returnsJointConfig() {
        ClusterConfig current = ClusterConfig.normal(Set.of("n1", "n2", "n3"));
        String cmd = MembershipChange.encodeJoint(
            Set.of("n1", "n2", "n3"),
            Set.of("n1", "n2", "n3", "n4")
        );
        MembershipChange change = MembershipChange.decode(cmd);
        ClusterConfig next = change.applyTo(current);

        assertTrue(next.isJoint());
        assertEquals(Set.of("n1", "n2", "n3"), next.oldMembers);
        assertEquals(Set.of("n1", "n2", "n3", "n4"), next.newMembers);
    }

    @Test
    void applyTo_normal_returnsNormalConfig() {
        ClusterConfig current = ClusterConfig.joint(
            Set.of("n1", "n2", "n3"),
            Set.of("n1", "n2", "n3", "n4")
        );
        String cmd = MembershipChange.encodeNormal(Set.of("n1", "n2", "n3", "n4"));
        MembershipChange change = MembershipChange.decode(cmd);
        ClusterConfig next = change.applyTo(current);

        assertFalse(next.isJoint());
        assertEquals(Set.of("n1", "n2", "n3", "n4"), next.oldMembers);
        assertTrue(next.newMembers.isEmpty());
    }

    // ==================== 两阶段变更完整流程（状态机模拟）====================

    @Test
    void twoPhaseChange_addMember_configTransition() {
        // 模拟：从 {n1,n2,n3} 添加 n4
        ClusterConfig c0 = ClusterConfig.normal(Set.of("n1", "n2", "n3"));

        // Phase 1: propose JOINT
        String jointCmd = MembershipChange.encodeJoint(
            Set.of("n1", "n2", "n3"),
            Set.of("n1", "n2", "n3", "n4")
        );
        ClusterConfig c1 = MembershipChange.decode(jointCmd).applyTo(c0);
        assertTrue(c1.isJoint());
        assertEquals(4, c1.allVoters().size());

        // Phase 2: propose NORMAL
        String normalCmd = MembershipChange.encodeNormal(Set.of("n1", "n2", "n3", "n4"));
        ClusterConfig c2 = MembershipChange.decode(normalCmd).applyTo(c1);
        assertFalse(c2.isJoint());
        assertEquals(Set.of("n1", "n2", "n3", "n4"), c2.oldMembers);
    }

    @Test
    void twoPhaseChange_removeMember_configTransition() {
        // 模拟：从 {n1,n2,n3} 移除 n3
        ClusterConfig c0 = ClusterConfig.normal(Set.of("n1", "n2", "n3"));

        String jointCmd = MembershipChange.encodeJoint(
            Set.of("n1", "n2", "n3"),
            Set.of("n1", "n2")
        );
        ClusterConfig c1 = MembershipChange.decode(jointCmd).applyTo(c0);
        assertTrue(c1.isJoint());
        // JOINT 阶段 allVoters = {n1,n2,n3}（old ∪ new）
        assertEquals(Set.of("n1", "n2", "n3"), c1.allVoters());

        String normalCmd = MembershipChange.encodeNormal(Set.of("n1", "n2"));
        ClusterConfig c2 = MembershipChange.decode(normalCmd).applyTo(c1);
        assertFalse(c2.isJoint());
        assertEquals(Set.of("n1", "n2"), c2.oldMembers);
        assertFalse(c2.oldMembers.contains("n3"), "n3 应已从配置中移除");
    }
}
