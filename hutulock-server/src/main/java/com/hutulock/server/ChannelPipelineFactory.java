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
package com.hutulock.server;

import com.hutulock.server.security.SecurityChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;

/**
 * Channel Pipeline 工厂
 *
 * <p>将 Pipeline 构建逻辑从 {@link HutuLockServer#start()} 中分离，
 * 使 Pipeline 结构可以独立测试和替换。
 *
 * <p>Pipeline 结构（从入站到出站）：
 * <pre>
 *   [TLS Handler]          — 可选，tlsEnabled=true 时插入
 *   LineBasedFrameDecoder  — 按换行符分帧，防止粘包
 *   StringDecoder          — bytes → String (UTF-8)
 *   StringEncoder          — String → bytes (UTF-8)
 *   SecurityChannelHandler — 认证、授权、限流
 *   LockServerHandler      — 业务逻辑
 * </pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class ChannelPipelineFactory extends ChannelInitializer<SocketChannel> {

    private final NettyServerConfig      config;
    private final SecurityChannelHandler securityHandler;
    private final LockServerHandler      lockHandler;

    public ChannelPipelineFactory(NettyServerConfig config,
                                   SecurityChannelHandler securityHandler,
                                   LockServerHandler lockHandler) {
        this.config          = config;
        this.securityHandler = securityHandler;
        this.lockHandler     = lockHandler;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline p = ch.pipeline();
        if (config.isTlsEnabled()) {
            p.addLast(config.sslContext.newHandler(ch.alloc()));
        }
        p.addLast(new LineBasedFrameDecoder(config.maxFrameLength))
         .addLast(new StringDecoder(CharsetUtil.UTF_8))
         .addLast(new StringEncoder(CharsetUtil.UTF_8))
         .addLast(securityHandler)
         .addLast(lockHandler);
    }
}
