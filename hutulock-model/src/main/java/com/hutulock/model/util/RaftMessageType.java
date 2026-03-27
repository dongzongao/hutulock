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
package com.hutulock.model.util;

/**
 * Raft 节点间消息类型枚举
 *
 * <p>替代 {@link com.hutulock.server.raft.RaftNode#handlePeerMessage} 等处的字符串字面量，
 * 提供类型安全的消息类型标识。
 *
 * <p>消息格式约定：{@code <TYPE> <field1> <field2> ...}，
 * 每条消息以 {@link Strings#MSG_LINE_END} 结尾（LineBasedFrameDecoder 分帧）。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public enum RaftMessageType {

    /** 投票请求：{@code VOTE_REQ {term} {candidateId} {lastLogIndex} {lastLogTerm}} */
    VOTE_REQ,

    /** 投票响应：{@code VOTE_RESP {term} {granted}} */
    VOTE_RESP,

    /** 日志复制请求：{@code APPEND_REQ {term} {leaderId} {prevLogIndex} {prevLogTerm} {leaderCommit} {entries}} */
    APPEND_REQ,

    /** 日志复制响应：{@code APPEND_RESP {term} {success} {matchIndex} {nodeId} {conflictTerm} {conflictIndex}} */
    APPEND_RESP;

    /**
     * 从消息字符串中提取类型前缀并匹配枚举。
     *
     * @param msg 原始消息字符串
     * @return 对应枚举，无法识别时返回 {@code null}
     */
    public static RaftMessageType of(String msg) {
        if (msg == null || msg.isEmpty()) return null;
        int spaceIdx = msg.indexOf(' ');
        String type = spaceIdx > 0 ? msg.substring(0, spaceIdx) : msg;
        try {
            return valueOf(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
