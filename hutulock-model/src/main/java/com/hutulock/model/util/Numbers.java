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
 * 数字相关常量工具包
 *
 * <p>收录项目中散落的魔法数字，按功能域分组。
 * 使用 {@code final class} + 私有构造，防止实例化。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class Numbers {

    private Numbers() {}

    // ==================== 对象池 ====================

    /** Thread-Local 本地池最大容量，超出后批量归还全局池 */
    public static final int POOL_LOCAL_MAX      = 32;
    /** 批量转移大小（补货/归还时每次转移的对象数） */
    public static final int POOL_BATCH          = 16;
    /** LockToken 对象池容量（建议 = 预期并发锁数 × 2） */
    public static final int LOCK_TOKEN_POOL_SIZE = 1024;

    // ==================== ZNodePath 缓存 ====================

    /** ZNodePath 缓存最大条目数，超出后降级为直接 new */
    public static final int PATH_CACHE_MAX_SIZE      = 8192;
    /** 预计算的 10 位补零序号字符串上限（覆盖 0 ~ 99999） */
    public static final int PATH_CACHE_PRECOMPUTED   = 100_000;
    /** 顺序节点序号格式宽度（10 位补零） */
    public static final int SEQ_FORMAT_WIDTH         = 10;

    // ==================== Raft ====================

    /** 连续选举失败上限，超过后强制冷却一个完整超时周期 */
    public static final int RAFT_MAX_CONSECUTIVE_ELECTIONS = 10;
    /** 日志条数超过此阈值时触发快照（仅 Leader） */
    public static final int RAFT_SNAPSHOT_LOG_THRESHOLD    = 1000;
    /** 定期快照检查间隔（秒） */
    public static final int RAFT_SNAPSHOT_INTERVAL_SEC     = 30;
    /** 超时清理任务调度间隔（秒） */
    public static final int RAFT_CLEANUP_INTERVAL_SEC      = 1;

    // ==================== 令牌桶限流 ====================

    /**
     * 令牌数内部放大倍数（乘以 1000 存储，避免浮点精度问题）。
     * 1000 代表 1 个令牌。
     */
    public static final int RATE_LIMITER_TOKEN_SCALE = 1000;

    // ==================== 消息/协议 ====================

    /** StringBuilder 初始容量：短消息（VOTE_RESP 等） */
    public static final int MSG_BUILDER_SMALL  = 32;
    /** StringBuilder 初始容量：中等消息（VOTE_REQ 等） */
    public static final int MSG_BUILDER_MEDIUM = 64;
    /** StringBuilder 初始容量：大消息（APPEND_REQ 含日志条目） */
    public static final int MSG_BUILDER_LARGE  = 128;
}
