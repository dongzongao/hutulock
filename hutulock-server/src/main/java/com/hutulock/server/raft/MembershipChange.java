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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 成员变更命令（通过 Raft 日志传播）
 *
 * <p>成员变更作为特殊的 Raft 日志条目，格式：
 * <pre>
 *   __CONF_JOINT__ {oldMembers} {newMembers} [{memberSpecs}]
 *   __CONF_NORMAL__ {members} [{memberSpecs}]
 * </pre>
 * 成员列表用逗号分隔，如 {@code n1,n2,n3}。
 * 成员地址可选，格式为 {@code nodeId@host:port}。
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

    /** 成员地址，用于在 apply 成员变更时同步 peer 连接。 */
    public static final class MemberEndpoint {
        public final String host;
        public final int    port;

        public MemberEndpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }

        String encode(String nodeId) {
            return nodeId + "@" + host + ":" + port;
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }
    }

    public final Type type;
    public final Set<String> oldMembers;
    public final Set<String> newMembers;
    public final Map<String, MemberEndpoint> memberEndpoints;

    private MembershipChange(Type type, Set<String> oldMembers, Set<String> newMembers,
                             Map<String, MemberEndpoint> memberEndpoints) {
        this.type = type;
        this.oldMembers = Collections.unmodifiableSet(new LinkedHashSet<>(oldMembers));
        this.newMembers = Collections.unmodifiableSet(new LinkedHashSet<>(newMembers));
        this.memberEndpoints = Collections.unmodifiableMap(new LinkedHashMap<>(memberEndpoints));
    }

    /** 构建联合配置命令字符串。 */
    public static String encodeJoint(Set<String> oldMembers, Set<String> newMembers) {
        return encodeJoint(oldMembers, newMembers, Collections.emptyMap());
    }

    /** 构建带成员地址的联合配置命令字符串。 */
    public static String encodeJoint(Set<String> oldMembers, Set<String> newMembers,
                                     Map<String, MemberEndpoint> memberEndpoints) {
        StringBuilder sb = new StringBuilder()
            .append(CMD_JOINT).append(" ")
            .append(String.join(",", oldMembers))
            .append(" ")
            .append(String.join(",", newMembers));
        appendEndpointSpecs(sb, memberEndpoints);
        return sb.toString();
    }

    /** 构建稳定配置命令字符串。 */
    public static String encodeNormal(Set<String> members) {
        return encodeNormal(members, Collections.emptyMap());
    }

    /** 构建带成员地址的稳定配置命令字符串。 */
    public static String encodeNormal(Set<String> members, Map<String, MemberEndpoint> memberEndpoints) {
        StringBuilder sb = new StringBuilder()
            .append(CMD_NORMAL).append(" ")
            .append(String.join(",", members));
        appendEndpointSpecs(sb, memberEndpoints);
        return sb.toString();
    }

    private static void appendEndpointSpecs(StringBuilder sb, Map<String, MemberEndpoint> memberEndpoints) {
        String specs = encodeEndpoints(memberEndpoints);
        if (!specs.isEmpty()) {
            sb.append(" ").append(specs);
        }
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
            String[] parts = body.split(" ", 3);
            if (parts.length < 2) {
                throw new IllegalArgumentException("Invalid JOINT command: " + command);
            }
            return new MembershipChange(
                Type.JOINT,
                parseMembers(parts[0]),
                parseMembers(parts[1]),
                parts.length >= 3 ? parseEndpoints(parts[2]) : Collections.emptyMap()
            );
        }
        if (command.startsWith(CMD_NORMAL)) {
            String body = command.substring(CMD_NORMAL.length()).trim();
            String[] parts = body.split(" ", 2);
            return new MembershipChange(
                Type.NORMAL,
                parseMembers(parts[0]),
                Collections.emptySet(),
                parts.length >= 2 ? parseEndpoints(parts[1]) : Collections.emptyMap()
            );
        }
        throw new IllegalArgumentException("Not a membership change command: " + command);
    }

    private static Set<String> parseMembers(String csv) {
        if (csv == null || csv.isBlank()) return Collections.emptySet();
        return new LinkedHashSet<>(Arrays.asList(csv.split(",")));
    }

    private static String encodeEndpoints(Map<String, MemberEndpoint> memberEndpoints) {
        if (memberEndpoints == null || memberEndpoints.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, MemberEndpoint> entry : memberEndpoints.entrySet()) {
            if (entry.getValue() == null) continue;
            if (sb.length() > 0) sb.append(",");
            sb.append(entry.getValue().encode(entry.getKey()));
        }
        return sb.toString();
    }

    private static Map<String, MemberEndpoint> parseEndpoints(String csv) {
        Map<String, MemberEndpoint> endpoints = new LinkedHashMap<>();
        if (csv == null || csv.isBlank()) return endpoints;

        for (String item : csv.split(",")) {
            String spec = item.trim();
            if (spec.isEmpty()) continue;

            int at = spec.indexOf('@');
            int colon = spec.lastIndexOf(':');
            if (at <= 0 || colon <= at + 1 || colon >= spec.length() - 1) {
                continue; // 兼容旧格式：只有成员 ID，无地址
            }

            String nodeId = spec.substring(0, at);
            String host = spec.substring(at + 1, colon);
            int port = Integer.parseInt(spec.substring(colon + 1));
            endpoints.put(nodeId, new MemberEndpoint(host, port));
        }

        return endpoints;
    }

    /** 将此变更应用到当前配置，返回新配置。 */
    public ClusterConfig applyTo(ClusterConfig current) {
        if (type == Type.JOINT) {
            return ClusterConfig.joint(oldMembers, newMembers);
        }
        return ClusterConfig.normal(oldMembers); // NORMAL 时 oldMembers 即为新成员集合
    }

    public MemberEndpoint endpointOf(String nodeId) {
        return memberEndpoints.get(nodeId);
    }

    @Override
    public String toString() {
        if (type == Type.JOINT) {
            return "MembershipChange{JOINT, old=" + oldMembers
                + ", new=" + newMembers + ", endpoints=" + memberEndpoints + "}";
        }
        return "MembershipChange{NORMAL, members=" + oldMembers
            + ", endpoints=" + memberEndpoints + "}";
    }
}
