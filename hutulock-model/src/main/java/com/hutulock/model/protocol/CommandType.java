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
package com.hutulock.model.protocol;

/**
 * 客户端 ↔ 服务端 协议命令类型
 *
 * <p>每个命令携带 Schema（最少参数数 / 最多参数数），
 * 由 {@link Message#parse(String)} 在解析时统一校验，
 * 消除各 Handler 中散落的防御性 argCount 检查。
 *
 * <p>文本协议格式（行分隔，UTF-8）：{@code {TYPE} {arg1} {arg2} ...}
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public enum CommandType {

    // ---- 客户端请求 ----
    /** CONNECT [sessionId] [TOKEN:xxx | HMAC:ts:sig] */
    CONNECT(0, 3),
    /** LOCK <lockName> <sessionId> */
    LOCK(2, 2),
    /** UNLOCK <seqNodePath> <sessionId> */
    UNLOCK(2, 2),
    /** RECHECK <lockName> <seqNodePath> <sessionId> */
    RECHECK(3, 3),
    /** RENEW <lockName> <sessionId> */
    RENEW(2, 2),
    /** GET_DATA <path> <sessionId> */
    GET_DATA(2, 2),
    /** SET_DATA <path> <expectedVersion> <base64data> <sessionId> */
    SET_DATA(4, 4),
    /** IS_LOCK_AVAILABLE <lockName> <sessionId> */
    IS_LOCK_AVAILABLE(2, 2),

    // ---- 服务端响应 ----
    /** CONNECTED <sessionId> */
    CONNECTED(1, 1),
    /** OK <lockName> [seqNodePath] */
    OK(1, 2),
    /** WAIT <lockName> <seqNodePath> [watchNodePath] */
    WAIT(2, 3),
    /** RELEASED <seqNodePath> */
    RELEASED(1, 1),
    /** RENEWED <lockName> */
    RENEWED(1, 1),
    /** REDIRECT <leaderId> */
    REDIRECT(1, 1),
    /** WATCH_EVENT — 由 WatchEvent 单独解析，不走 Message.parse */
    WATCH_EVENT(0, Integer.MAX_VALUE),
    /** ERROR <message> */
    ERROR(1, Integer.MAX_VALUE),
    /** DATA <path> <version> <base64data> */
    DATA(3, 3),
    /** VERSION_MISMATCH <path> */
    VERSION_MISMATCH(1, 1);

    /** 该命令要求的最少参数数量 */
    public final int minArgs;
    /** 该命令允许的最多参数数量 */
    public final int maxArgs;

    CommandType(int minArgs, int maxArgs) {
        this.minArgs = minArgs;
        this.maxArgs = maxArgs;
    }
}
