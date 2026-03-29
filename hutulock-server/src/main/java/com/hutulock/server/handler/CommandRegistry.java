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
package com.hutulock.server.handler;

import com.hutulock.model.protocol.CommandType;
import com.hutulock.model.protocol.Message;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;

/**
 * 命令注册表（Command Pattern 注册中心）
 *
 * <p>用 {@link EnumMap} 替代 switch，O(1) 查找，开闭原则：
 * 新增命令无需修改路由逻辑，只需注册新的 {@link CommandHandler}。
 *
 * <p>使用示例：
 * <pre>{@code
 *   CommandRegistry registry = new CommandRegistry(fallback);
 *   registry.register(CommandType.LOCK,    (ctx, msg) -> handleLock(ctx, msg));
 *   registry.register(CommandType.UNLOCK,  (ctx, msg) -> handleUnlock(ctx, msg));
 *
 *   // 路由（替代 switch）
 *   registry.dispatch(ctx, msg);
 * }</pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class CommandRegistry {

    private static final Logger log = LoggerFactory.getLogger(CommandRegistry.class);

    private final Map<CommandType, CommandHandler> handlers = new EnumMap<>(CommandType.class);
    private final CommandHandler fallback;

    /**
     * @param fallback 未注册命令的兜底处理器（通常返回 ERROR 响应）
     */
    public CommandRegistry(CommandHandler fallback) {
        this.fallback = fallback;
    }

    /** 注册命令处理器，支持链式调用。 */
    public CommandRegistry register(CommandType type, CommandHandler handler) {
        handlers.put(type, handler);
        return this;
    }

    /**
     * 路由并执行命令，替代 switch(msg.getType())。
     * 未注册的命令交给 fallback 处理。
     */
    public void dispatch(ChannelHandlerContext ctx, Message msg) {
        CommandHandler handler = handlers.getOrDefault(msg.getType(), fallback);
        log.debug("Dispatching command: {}", msg.getType());
        handler.handle(ctx, msg);
    }
}
