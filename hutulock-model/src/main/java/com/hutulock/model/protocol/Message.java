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

import java.util.Optional;

/**
 * 客户端 ↔ 服务端 消息（不可变值对象）
 *
 * <p>封装文本协议的解析与序列化，格式：{@code {TYPE} {arg0} {arg1} ...}
 *
 * <p>解析时根据 {@link CommandType} 携带的 Schema（minArgs / maxArgs）
 * 统一校验参数数量，消除各 Handler 中散落的防御性 argCount 检查。
 *
 * <p>取参方式：
 * <ul>
 *   <li>{@link #arg(int)} — 必选参数，越界抛 {@link HutuLockException}</li>
 *   <li>{@link #optArg(int)} — 可选参数，越界返回 {@link Optional#empty()}</li>
 * </ul>
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

    /**
     * 解析文本行为 Message，并根据 {@link CommandType} Schema 校验参数数量。
     *
     * @throws HutuLockException INVALID_COMMAND / UNKNOWN_COMMAND / MISSING_ARGUMENT
     */
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

        // Schema 校验：统一在此处检查，各 Handler 无需重复防御
        if (args.length < type.minArgs) {
            throw new HutuLockException(ErrorCode.MISSING_ARGUMENT,
                type + " requires at least " + type.minArgs + " args, got " + args.length);
        }
        if (args.length > type.maxArgs) {
            throw new HutuLockException(ErrorCode.INVALID_COMMAND,
                type + " allows at most " + type.maxArgs + " args, got " + args.length);
        }

        return new Message(type, args);
    }

    public String serialize() {
        if (args.length == 0) return type.name();
        return type.name() + " " + String.join(" ", args);
    }

    public CommandType getType() { return type; }

    /**
     * 获取必选参数，越界时抛出 {@link HutuLockException}。
     * 适用于 Schema 已保证参数存在的场景。
     */
    public String arg(int index) {
        if (index >= args.length) {
            throw new HutuLockException(ErrorCode.MISSING_ARGUMENT,
                "arg[" + index + "] missing in: " + serialize());
        }
        return args[index];
    }

    /**
     * 获取可选参数，越界时返回 {@link Optional#empty()}。
     * 适用于 Schema 中 minArgs < maxArgs 的可选参数场景。
     */
    public Optional<String> optArg(int index) {
        return index < args.length ? Optional.of(args[index]) : Optional.empty();
    }

    public int argCount() { return args.length; }

    @Override
    public String toString() { return serialize(); }
}
