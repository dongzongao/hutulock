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
package com.hutulock.cli;

/**
 * CLI 命令枚举
 *
 * <p>每个命令包含名称、参数说明和描述，用于帮助信息展示。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public enum CliCommand {

    CONNECT("connect", "<host:port> [host:port ...]",
        "连接到 HutuLock 集群节点\n" +
        "  示例: connect 127.0.0.1:8881 127.0.0.1:8882 127.0.0.1:8883"),

    LOCK("lock", "<lockName> [timeoutSeconds]",
        "获取分布式锁（默认超时 30s）\n" +
        "  示例: lock order-lock\n" +
        "  示例: lock order-lock 60"),

    UNLOCK("unlock", "<lockName>",
        "释放分布式锁\n" +
        "  示例: unlock order-lock"),

    RENEW("renew", "<lockName>",
        "手动续期（重置服务端看门狗计时器）\n" +
        "  示例: renew order-lock"),

    STATUS("status", "",
        "显示当前连接状态和持有的锁列表"),

    DISCONNECT("disconnect", "",
        "断开与集群的连接"),

    HELP("help", "[command]",
        "显示帮助信息\n" +
        "  示例: help lock"),

    EXIT("exit", "",
        "退出 CLI");

    private final String name;
    private final String args;
    private final String description;

    CliCommand(String name, String args, String description) {
        this.name        = name;
        this.args        = args;
        this.description = description;
    }

    public String getName()        { return name;        }
    public String getArgs()        { return args;        }
    public String getDescription() { return description; }

    /**
     * 格式化为帮助行，如 {@code "  lock <lockName> [timeoutSeconds]"}
     */
    public String toHelpLine() {
        String usage = args.isEmpty() ? name : name + " " + args;
        return String.format("  %-40s %s", usage, description.split("\n")[0]);
    }

    /**
     * 根据名称查找命令（大小写不敏感）。
     *
     * @param name 命令名称
     * @return 匹配的命令，未找到返回 null
     */
    public static CliCommand of(String name) {
        for (CliCommand cmd : values()) {
            if (cmd.name.equalsIgnoreCase(name)) return cmd;
        }
        return null;
    }
}
