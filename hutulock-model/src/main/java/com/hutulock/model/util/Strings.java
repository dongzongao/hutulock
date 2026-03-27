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
 * 字符串相关常量工具包
 *
 * <p>收录项目中散落的路径前缀、格式字符串等字面量。
 * 协议消息类型（有类型安全需求）请使用 {@link RaftMessageType}。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class Strings {

    private Strings() {}

    // ==================== ZNode 路径 ====================

    /** 锁根节点路径 */
    public static final String LOCKS_ROOT       = "/locks";
    /** 顺序节点名称前缀 */
    public static final String SEQ_PREFIX       = "seq-";
    /** 顺序节点路径分隔符 */
    public static final String PATH_SEPARATOR   = "/";
    /** 顺序节点序号格式（10 位补零） */
    public static final String SEQ_FORMAT       = "%010d";

    // ==================== WAL 持久化 ====================

    /** WAL 日志文件名 */
    public static final String WAL_FILE_NAME    = "raft-log.wal";
    /** WAL 行内字段分隔符（TAB） */
    public static final String WAL_FIELD_SEP    = "\t";
    /** WAL 转义：TAB 字符 */
    public static final String WAL_ESCAPE_TAB   = "\\t";
    /** WAL 转义：换行符 */
    public static final String WAL_ESCAPE_LF    = "\\n";

    // ==================== 协议 ====================

    /** AppendEntries 日志条目分隔符 */
    public static final String ENTRY_DELIMITER  = "|";
    /** AppendEntries 空条目标记 */
    public static final String ENTRY_EMPTY      = "EMPTY";
    /** 日志条目内字段分隔符（index:term:command） */
    public static final String ENTRY_FIELD_SEP  = ":";
    /** 消息行结束符 */
    public static final String MSG_LINE_END     = "\n";
    /** 未知 Leader 占位符 */
    public static final String UNKNOWN_LEADER   = "UNKNOWN";

    // ==================== 线程名 ====================

    /** Raft 调度线程名 */
    public static final String THREAD_RAFT_SCHEDULER  = "hutulock-raft-scheduler";
    /** 会话扫描线程名 */
    public static final String THREAD_SESSION_SCANNER = "hutulock-session-scanner";

    // ==================== Channel 属性 ====================

    /** Netty Channel 属性 Key：当前连接的 sessionId */
    public static final String ATTR_SESSION_ID = "hutulock.sessionId";
}
