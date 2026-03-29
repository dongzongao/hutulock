package com.hutulock.server.security;

import com.hutulock.model.protocol.CommandType;
import com.hutulock.model.protocol.Message;
import com.hutulock.spi.security.AuthResult;
import com.hutulock.spi.security.AuthToken;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 认证过滤器（责任链第二节点）
 *
 * <p>未认证连接只允许 CONNECT 命令，认证成功后将 clientId 写入 Channel 属性。
 */
public final class AuthFilter implements SecurityFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);
    private static final AttributeKey<Boolean> AUTHENTICATED_KEY =
        AttributeKey.valueOf("hutulock.security.authenticated");

    private final SecurityContext security;

    public AuthFilter(SecurityContext security) { this.security = security; }

    @Override
    public boolean doFilter(ChannelHandlerContext ctx, Message msg, String raw, SecurityFilterChain chain) {
        Boolean authenticated = ctx.channel().attr(AUTHENTICATED_KEY).get();
        if (Boolean.TRUE.equals(authenticated)) {
            return chain.next(ctx, msg, raw); // 已认证，继续
        }

        if (msg.getType() != CommandType.CONNECT) {
            log.warn("Unauthenticated request: type={}, addr={}", msg.getType(), ctx.channel().remoteAddress());
            ctx.writeAndFlush(Message.of(CommandType.ERROR, "authentication required").serialize() + "\n");
            ctx.close();
            return false;
        }

        // 处理 CONNECT 认证
        String remoteAddr = ctx.channel().remoteAddress().toString();
        AuthToken token = extractAuthToken(msg);
        AuthResult result = security.getAuthenticator().authenticate(token);
        security.getAuditLogger().logAuth(
            token != null ? token.getClientId() : "unknown", remoteAddr,
            result.isSuccess(), result.getReason());

        if (!result.isSuccess()) {
            log.warn("Authentication failed: addr={}, reason={}", remoteAddr, result.getReason());
            ctx.writeAndFlush(Message.of(CommandType.ERROR, "auth failed: " + result.getReason()).serialize() + "\n");
            ctx.close();
            return false;
        }

        ctx.channel().attr(SecurityChannelHandler.CLIENT_ID_KEY).set(result.getClientId());
        ctx.channel().attr(AUTHENTICATED_KEY).set(Boolean.TRUE);
        log.info("Client authenticated: clientId={}, addr={}", result.getClientId(), remoteAddr);
        return chain.next(ctx, msg, raw);
    }

    private static AuthToken extractAuthToken(Message msg) {
        for (int i = 0; i < msg.argCount(); i++) {
            String arg = msg.arg(i);
            if (arg.startsWith("TOKEN:") || arg.startsWith("HMAC:")) {
                return AuthToken.parse("unknown", arg);
            }
        }
        return null;
    }
}
