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
import com.hutulock.spi.security.AuthResult;
import com.hutulock.spi.security.AuthToken;
import com.hutulock.spi.security.Authorizer.Permission;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty 安全拦截器
 *
 * <p>在 Netty Pipeline 中位于 {@link com.hutulock.server.LockServerHandler} 之前，
 * 负责：
 * <ol>
 *   <li>认证（Authentication）— 解析 CONNECT 命令中的 AuthToken，验证身份</li>
 *   <li>限流（Rate Limiting）— 按 clientId 限制请求频率</li>
 *   <li>授权（Authorization）— 在 LOCK/UNLOCK 命令前检查 ACL 权限</li>
 *   <li>审计（Audit）— 记录所有安全相关事件</li>
 * </ol>
 *
 * <p>Pipeline 顺序：
 * <pre>
 *   LineBasedFrameDecoder
 *   StringDecoder / StringEncoder
 *   SecurityChannelHandler   ← 安全拦截（本类）
 *   LockServerHandler        ← 业务逻辑
 * </pre>
 *
 * <p>认证流程：
 * <ul>
 *   <li>未认证的连接只允许发送 CONNECT 命令</li>
 *   <li>CONNECT 认证成功后，将 clientId 写入 Channel 属性</li>
 *   <li>认证失败立即关闭连接</li>
 * </ul>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
@ChannelHandler.Sharable
public class SecurityChannelHandler extends SimpleChannelInboundHandler<String> {

    private static final Logger log = LoggerFactory.getLogger(SecurityChannelHandler.class);

    /** Channel 属性 Key：认证后的 clientId */
    public static final AttributeKey<String> CLIENT_ID_KEY =
        AttributeKey.valueOf("hutulock.security.clientId");

    /** Channel 属性 Key：是否已通过认证 */
    private static final AttributeKey<Boolean> AUTHENTICATED_KEY =
        AttributeKey.valueOf("hutulock.security.authenticated");

    private final SecurityContext security;

    public SecurityChannelHandler(SecurityContext security) {
        this.security = security;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String raw) {
        // 安全未启用，直接透传
        if (!security.isEnabled()) {
            ctx.fireChannelRead(raw);
            return;
        }

        Message msg;
        try {
            msg = Message.parse(raw);
        } catch (Exception e) {
            // 解析失败，拒绝请求（不透传，防止绕过认证检查）
            log.warn("Failed to parse message from {}: {}", ctx.channel().remoteAddress(), e.getMessage());
            ctx.writeAndFlush(
                Message.of(CommandType.ERROR, "invalid message format").serialize() + "\n");
            ctx.close();
            return;
        }

        // 1. 限流检查（在认证之前，防止未认证请求耗尽资源）
        String clientId = ctx.channel().attr(CLIENT_ID_KEY).get();
        String limitKey = clientId != null ? clientId : ctx.channel().remoteAddress().toString();
        if (!security.getRateLimiter().tryAcquire(limitKey)) {
            log.warn("Rate limit exceeded: client={}, addr={}",
                limitKey, ctx.channel().remoteAddress());
            ctx.writeAndFlush(
                Message.of(CommandType.ERROR, "rate limit exceeded").serialize() + "\n");
            return; // 丢弃请求，不关闭连接
        }

        // 2. 认证检查
        Boolean authenticated = ctx.channel().attr(AUTHENTICATED_KEY).get();
        if (!Boolean.TRUE.equals(authenticated)) {
            if (msg.getType() != CommandType.CONNECT) {
                // 未认证，只允许 CONNECT
                log.warn("Unauthenticated request: type={}, addr={}",
                    msg.getType(), ctx.channel().remoteAddress());
                ctx.writeAndFlush(
                    Message.of(CommandType.ERROR, "authentication required").serialize() + "\n");
                ctx.close();
                return;
            }
            // 处理 CONNECT 认证
            handleConnect(ctx, msg, raw);
            return;
        }

        // 3. 授权检查（LOCK / UNLOCK 命令）
        if (msg.getType() == CommandType.LOCK || msg.getType() == CommandType.UNLOCK) {
            if (!checkAuthorization(ctx, msg, clientId)) {
                return; // 授权失败，已发送错误响应
            }
        }

        // 4. 通过所有安全检查，透传给业务 Handler
        ctx.fireChannelRead(raw);
    }

    // ==================== 认证处理 ====================

    private void handleConnect(ChannelHandlerContext ctx, Message msg, String raw) {
        String remoteAddr = ctx.channel().remoteAddress().toString();

        // 解析 AuthToken：CONNECT [sessionId] [TOKEN:xxx 或 HMAC:ts:sig]
        AuthToken token = extractAuthToken(msg);

        AuthResult result = security.getAuthenticator().authenticate(token);
        security.getAuditLogger().logAuth(
            token != null ? token.getClientId() : "unknown",
            remoteAddr,
            result.isSuccess(),
            result.getReason()
        );

        if (!result.isSuccess()) {
            log.warn("Authentication failed: addr={}, reason={}", remoteAddr, result.getReason());
            ctx.writeAndFlush(
                Message.of(CommandType.ERROR, "auth failed: " + result.getReason()).serialize() + "\n");
            ctx.close();
            return;
        }

        // 认证成功，记录 clientId 到 Channel 属性
        String authenticatedClientId = result.getClientId();
        ctx.channel().attr(CLIENT_ID_KEY).set(authenticatedClientId);
        ctx.channel().attr(AUTHENTICATED_KEY).set(Boolean.TRUE);
        log.info("Client authenticated: clientId={}, addr={}", authenticatedClientId, remoteAddr);

        // 透传 CONNECT 命令给业务 Handler（由业务 Handler 创建 Session）
        ctx.fireChannelRead(raw);
    }

    // ==================== 授权检查 ====================

    private boolean checkAuthorization(ChannelHandlerContext ctx, Message msg, String clientId) {
        if (msg.argCount() < 1) return true; // 参数不足，交给业务 Handler 处理

        String lockName = msg.arg(0);
        Permission perm = msg.getType() == CommandType.LOCK ? Permission.LOCK : Permission.UNLOCK;

        boolean permitted = security.getAuthorizer().isPermitted(clientId, lockName, perm);
        security.getAuditLogger().logAuthz(clientId, lockName, perm, permitted);

        if (!permitted) {
            log.warn("Authorization denied: clientId={}, lock={}, perm={}", clientId, lockName, perm);
            ctx.writeAndFlush(
                Message.of(CommandType.ERROR,
                    "permission denied: " + clientId + " cannot " + perm + " on " + lockName)
                    .serialize() + "\n");
        }
        return permitted;
    }

    // ==================== 工具方法 ====================

    /**
     * 从 CONNECT 命令中提取 AuthToken。
     *
     * <p>CONNECT 命令格式：
     * <pre>
     *   CONNECT                              — 无认证（开发模式）
     *   CONNECT TOKEN:my-secret              — Token 认证（无 sessionId）
     *   CONNECT old-session-id TOKEN:secret  — Token 认证（带 sessionId）
     *   CONNECT HMAC:1700000000:signature    — HMAC 认证
     * </pre>
     */
    private static AuthToken extractAuthToken(Message msg) {
        // 遍历参数，找到以 TOKEN: 或 HMAC: 开头的参数
        for (int i = 0; i < msg.argCount(); i++) {
            String arg = msg.arg(i);
            if (arg.startsWith("TOKEN:") || arg.startsWith("HMAC:")) {
                // clientId 暂时用 "unknown"，由 TokenAuthenticator 内部解析
                return AuthToken.parse("unknown", arg);
            }
        }
        return null; // 无 token，由 Authenticator 决定是否允许
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
