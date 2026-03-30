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

import com.hutulock.config.api.ServerProperties;
import com.hutulock.server.security.TlsContextFactory;
import io.netty.handler.ssl.SslContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Netty 服务端配置（从 {@link ServerProperties} 派生）
 *
 * <p>将 TLS 初始化、线程数计算等启动前准备工作从 {@link HutuLockServer#start()} 中分离，
 * 使启动方法只负责绑定端口和等待关闭。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class NettyServerConfig {

    private static final Logger log = LoggerFactory.getLogger(NettyServerConfig.class);

    public final int        soBacklog;
    public final int        maxFrameLength;
    public final int        bossThreads;
    public final int        workerThreads;
    public final SslContext sslContext;

    private NettyServerConfig(int soBacklog, int maxFrameLength,
                               int bossThreads, int workerThreads,
                               SslContext sslContext) {
        this.soBacklog      = soBacklog;
        this.maxFrameLength = maxFrameLength;
        this.bossThreads    = bossThreads;
        this.workerThreads  = workerThreads;
        this.sslContext     = sslContext;
    }

    /**
     * 从 {@link ServerProperties} 构建 Netty 配置，包含 TLS 初始化。
     *
     * @throws RuntimeException TLS 初始化失败时抛出
     */
    public static NettyServerConfig from(ServerProperties props) {
        SslContext ssl = null;
        if (props.tlsEnabled) {
            try {
                ssl = (props.tlsSelfSigned || props.tlsCertFile == null)
                    ? TlsContextFactory.serverContextSelfSigned()
                    : TlsContextFactory.serverContext(
                        new File(props.tlsCertFile), new File(props.tlsKeyFile));
                log.info("TLS enabled, selfSigned={}", props.tlsSelfSigned || props.tlsCertFile == null);
            } catch (Exception e) {
                throw new RuntimeException("Failed to initialize TLS context", e);
            }
        }
        // bossThreads 固定 1（accept 线程），workerThreads 默认 0 = Netty 自动选择（CPU × 2）
        return new NettyServerConfig(props.soBacklog, props.maxFrameLength, 1, 0, ssl);
    }

    public boolean isTlsEnabled() {
        return sslContext != null;
    }
}
