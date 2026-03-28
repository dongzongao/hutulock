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
 * <p>文本协议格式（行分隔，UTF-8）：{@code {TYPE} {arg1} {arg2} ...}
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public enum CommandType {
    // 客户端请求
    CONNECT, LOCK, UNLOCK, RECHECK, RENEW, GET_DATA, SET_DATA,
    // 服务端响应
    CONNECTED, OK, WAIT, RELEASED, RENEWED, REDIRECT, WATCH_EVENT, ERROR, DATA, VERSION_MISMATCH
}
