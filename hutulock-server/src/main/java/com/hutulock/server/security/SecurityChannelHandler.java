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

import com.hutulock.model.protocol.CommandType;
import com.hutulock.model.protocol.Message;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty 安全拦截器
 *
 * <p>职责已拆分到责任链的三个过滤器：
 * <ol>
 *   <li>{@link RateLimitFilter} — 限流</li>
 *   <li>{@link AuthFilter}      — 认证</li>
 *   <li>{@link AuthzFilter}     — 授权</li>
 * </ol>
 *
 * <p>本类只负责：解析消息 + 驱动责任链 + 透传通过的请求。
 * 新增安全检查只需实现 {@link SecurityFilter} 并在构造函数中 {@code .add()}，
 * 无需修改本类（开闭原则）。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
@ChannelHandler.Sharable
public class SecurityChannelHandler extends SimpleChannelInboundHandler<String> {

    private static final Logger log = LoggerFactory.getLogger(SecurityChannelHandler.class);

    /** Channel 属性 Key：认证后的 clientId（供过滤器和业务 Handler 共享） */
    public static final AttributeKey<String> CLIENT_ID_KEY =
        AttributeKey.valueOf("hutulock.security.clientId");

    private final SecurityContext     security;
    private final SecurityFilterChain filterChain;

    public SecurityChannelHandler(SecurityContext security) {
        this.security = security;
        // 责任链：限流 → 认证 → 授权
        // 新增安全检查只需在此处 .add() 一个新过滤器
        this.filterChain = SecurityFilterChain.builder()
            .add(new RateLimitFilter(security))
            .add(new AuthFilter(security))
            .add(new AuthzFilter(security))
            .build();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String raw) {
        if (!security.isEnabled()) {
            ctx.fireChannelRead(raw);
            return;
        }

        Message msg;
        try {
            msg = Message.parse(raw);
        } catch (Exception e) {
            log.warn("Failed to parse message from {}: {}", ctx.channel().remoteAddress(), e.getMessage());
            ctx.writeAndFlush(Message.of(CommandType.ERROR, "invalid message format").serialize() + "\n");
            ctx.close();
            return;
        }

        // 责任链驱动：全部通过后透传给业务 Handler
        if (filterChain.execute(ctx, msg, raw)) {
            ctx.fireChannelRead(raw);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        String clientId = ctx.channel().attr(CLIENT_ID_KEY).get();
        if (clientId != null) {
            log.debug("Authenticated client disconnected: clientId={}", clientId);
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Security handler error: {}", cause.getMessage());
        ctx.close();
    }
}
