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

import com.hutulock.model.protocol.Message;
import io.netty.channel.ChannelHandlerContext;

/**
 * 命令处理器接口（Command Pattern）
 *
 * <p>每个 {@link com.hutulock.model.protocol.CommandType} 对应一个实现，
 * 由 {@link CommandRegistry} 在启动时注册，运行时通过 O(1) Map 查找替代 switch。
 *
 * <p>新增命令只需：
 * <ol>
 *   <li>在 {@link com.hutulock.model.protocol.CommandType} 枚举中声明</li>
 *   <li>实现本接口</li>
 *   <li>在 {@link CommandRegistry} 中注册</li>
 * </ol>
 * 无需修改 {@link com.hutulock.server.LockServerHandler} 的路由逻辑。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
@FunctionalInterface
public interface CommandHandler {

    /**
     * 处理命令。
     *
     * @param ctx Netty Channel 上下文
     * @param msg 已解析的消息（Schema 已校验参数数量）
     */
    void handle(ChannelHandlerContext ctx, Message msg);
}
