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

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 成员变更命令（通过 Raft 日志传播）
 *
 * <p>成员变更作为特殊的 Raft 日志条目，格式：
 * <pre>
 *   __CONF_JOINT__ {oldMembers} {newMembers}
 *   __CONF_NORMAL__ {members}
 * </pre>
 * 成员列表用逗号分隔，如 {@code n1,n2,n3}。
 *
 * <p>两阶段流程：
 * <ol>
 *   <li>Leader propose {@code __CONF_JOINT__}，进入联合共识阶段</li>
 *   <li>JOINT 日志 commit 后，Leader 自动 propose {@code __CONF_NORMAL__}，完成变更</li>
 * </ol>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class MembershipChange {

    /** 联合配置命令前缀 */
    public static final String CMD_JOINT  = "__CONF_JOINT__";
    /** 稳定配置命令前缀 */
    public static final String CMD_NORMAL = "__CONF_NORMAL__";

    public enum Type { JOINT, NORMAL }

    public final Type       type;
    public final Set<String> oldMembers;
    public final Set<String> newMembers;

    private MembershipChange(Type type, Set<String> oldMembers, Set<String> newMembers) {
        this.type       = type;
        this.oldMembers = Collections.unmodifiableSet(oldMembers);
        this.newMembers = Collections.unmodifiableSet(newMembers);
    }

    /** 构建联合配置命令字符串。 */
    public static String encodeJoint(Set<String> oldMembers, Set<String> newMembers) {
        return CMD_JOINT + " " + String.join(",", oldMembers)
            + " " + String.join(",", newMembers);
    }

    /** 构建稳定配置命令字符串。 */
    public static String encodeNormal(Set<String> members) {
        return CMD_NORMAL + " " + String.join(",", members);
    }

    /** 判断命令字符串是否为成员变更命令。 */
    public static boolean isMembershipChange(String command) {
        return command != null
            && (command.startsWith(CMD_JOINT) || command.startsWith(CMD_NORMAL));
    }

    /**
     * 解析成员变更命令。
     *
     * @throws IllegalArgumentException 格式不合法时
     */
    public static MembershipChange decode(String command) {
        if (command.startsWith(CMD_JOINT)) {
            String body = command.substring(CMD_JOINT.length()).trim();
            String[] parts = body.split(" ", 2);
            if (parts.length < 2) {
                throw new IllegalArgumentException("Invalid JOINT command: " + command);
            }
            return new MembershipChange(Type.JOINT,
                parseMembers(parts[0]), parseMembers(parts[1]));
        }
        if (command.startsWith(CMD_NORMAL)) {
            String body = command.substring(CMD_NORMAL.length()).trim();
            return new MembershipChange(Type.NORMAL,
                parseMembers(body), Collections.emptySet());
        }
        throw new IllegalArgumentException("Not a membership change command: " + command);
    }

    private static Set<String> parseMembers(String csv) {
        if (csv == null || csv.isBlank()) return Collections.emptySet();
        return new LinkedHashSet<>(Arrays.asList(csv.split(",")));
    }

    /** 将此变更应用到当前配置，返回新配置。 */
    public ClusterConfig applyTo(ClusterConfig current) {
        if (type == Type.JOINT) {
            return ClusterConfig.joint(oldMembers, newMembers);
        }
        return ClusterConfig.normal(oldMembers); // NORMAL 时 oldMembers 即为新成员集合
    }

    @Override
    public String toString() {
        if (type == Type.JOINT) return "MembershipChange{JOINT, old=" + oldMembers + ", new=" + newMembers + "}";
        return "MembershipChange{NORMAL, members=" + oldMembers + "}";
    }
}
