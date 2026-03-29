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
package com.hutulock.server.security;

import com.hutulock.model.protocol.Message;
import io.netty.channel.ChannelHandlerContext;

/**
 * 安全过滤器接口（责任链模式节点）
 *
 * <p>每个实现负责一个安全关注点（限流 / 认证 / 授权），
 * 通过 {@link SecurityFilterChain} 串联，替代 {@link SecurityChannelHandler}
 * 中的 if-else 判断链。
 *
 * <p>新增安全检查只需实现本接口并注册到 {@link SecurityFilterChain}，
 * 无需修改现有过滤器代码（开闭原则）。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public interface SecurityFilter {

    /**
     * 执行安全检查。
     *
     * @param ctx   Netty Channel 上下文
     * @param msg   已解析的消息
     * @param raw   原始文本（透传给下一个 Handler 用）
     * @param chain 责任链，调用 {@code chain.next()} 继续执行后续过滤器
     * @return true 表示通过，false 表示已拦截（实现内部负责发送错误响应）
     */
    boolean doFilter(ChannelHandlerContext ctx, Message msg, String raw, SecurityFilterChain chain);
}
