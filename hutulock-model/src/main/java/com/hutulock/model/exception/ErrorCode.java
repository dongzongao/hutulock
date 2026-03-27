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
package com.hutulock.model.exception;

/**
 * HutuLock 错误码枚举
 *
 * <p>错误码分段：
 * <ul>
 *   <li>1xxx — 协议/命令错误</li>
 *   <li>2xxx — 锁操作错误</li>
 *   <li>3xxx — 会话错误</li>
 *   <li>4xxx — Raft 共识错误</li>
 *   <li>5xxx — 存储错误</li>
 *   <li>9xxx — 系统/未知错误</li>
 * </ul>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public enum ErrorCode {

    INVALID_COMMAND(1001, "Invalid command format"),
    UNKNOWN_COMMAND(1002, "Unknown command type"),
    MISSING_ARGUMENT(1003, "Required argument is missing"),

    LOCK_TIMEOUT(2001, "Lock acquisition timed out"),
    LOCK_NOT_HELD(2002, "Lock is not held by this session"),

    SESSION_EXPIRED(3001, "Session has expired"),
    SESSION_NOT_FOUND(3002, "Session not found"),

    NOT_LEADER(4001, "Current node is not the leader"),
    LEADER_CHANGED(4002, "Leader has changed during operation"),
    PROPOSE_TIMEOUT(4003, "Raft propose timed out"),
    SERVER_UNAVAILABLE(4004, "No leader available in the cluster"),

    NODE_NOT_FOUND(5001, "ZNode does not exist"),
    NODE_ALREADY_EXISTS(5002, "ZNode already exists"),
    PARENT_NOT_FOUND(5003, "Parent ZNode does not exist"),
    VERSION_MISMATCH(5004, "ZNode version mismatch"),

    INVALID_STATE(8001, "Illegal state transition"),

    CONNECTION_FAILED(9001, "Failed to connect to server"),
    UNKNOWN(9999, "Unknown error");

    private final int    code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code           = code;
        this.defaultMessage = defaultMessage;
    }

    public int    getCode()           { return code;           }
    public String getDefaultMessage() { return defaultMessage; }

    @Override
    public String toString() { return name() + "(" + code + ")"; }
}
