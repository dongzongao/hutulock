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

import com.hutulock.model.exception.ErrorCode;
import com.hutulock.model.exception.HutuLockException;

/**
 * 客户端 ↔ 服务端 消息（不可变值对象）
 *
 * <p>封装文本协议的解析与序列化，格式：{@code {TYPE} {arg0} {arg1} ...}
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class Message {

    private final CommandType type;
    private final String[]    args;

    private Message(CommandType type, String... args) {
        this.type = type;
        this.args = args != null ? args : new String[0];
    }

    public static Message of(CommandType type, String... args) {
        return new Message(type, args);
    }

    public static Message parse(String line) {
        if (line == null || line.isBlank()) {
            throw new HutuLockException(ErrorCode.INVALID_COMMAND, "empty message");
        }
        String[] parts = line.trim().split("\\s+", 2);
        CommandType type;
        try {
            type = CommandType.valueOf(parts[0].toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new HutuLockException(ErrorCode.UNKNOWN_COMMAND, "unknown command: " + parts[0]);
        }
        String[] args = parts.length > 1 ? parts[1].split("\\s+") : new String[0];
        return new Message(type, args);
    }

    public String serialize() {
        if (args.length == 0) return type.name();
        return type.name() + " " + String.join(" ", args);
    }

    public CommandType getType() { return type; }

    public String arg(int index) {
        if (index >= args.length) {
            throw new HutuLockException(ErrorCode.MISSING_ARGUMENT,
                "arg[" + index + "] missing in: " + serialize());
        }
        return args[index];
    }

    public int argCount() { return args.length; }

    @Override
    public String toString() { return serialize(); }
}
