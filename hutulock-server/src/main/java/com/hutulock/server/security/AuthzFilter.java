package com.hutulock.server.security;

import com.hutulock.model.protocol.CommandType;
import com.hutulock.model.protocol.Message;
import com.hutulock.spi.security.Authorizer.Permission;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 授权过滤器（责任链第三节点）
 *
 * <p>仅对 LOCK / UNLOCK 命令执行 ACL 检查，其他命令直接透传。
 */
public final class AuthzFilter implements SecurityFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthzFilter.class);
    private final SecurityContext security;

    public AuthzFilter(SecurityContext security) { this.security = security; }

    @Override
    public boolean doFilter(ChannelHandlerContext ctx, Message msg, String raw, SecurityFilterChain chain) {
        if (msg.getType() != CommandType.LOCK && msg.getType() != CommandType.UNLOCK) {
            return chain.next(ctx, msg, raw); // 非锁操作，跳过授权
        }

        String clientId = ctx.channel().attr(SecurityChannelHandler.CLIENT_ID_KEY).get();
        String lockName = msg.arg(0);
        Permission perm = msg.getType() == CommandType.LOCK ? Permission.LOCK : Permission.UNLOCK;

        boolean permitted = security.getAuthorizer().isPermitted(clientId, lockName, perm);
        security.getAuditLogger().logAuthz(clientId, lockName, perm, permitted);

        if (!permitted) {
            log.warn("Authorization denied: clientId={}, lock={}, perm={}", clientId, lockName, perm);
            ctx.writeAndFlush(Message.of(CommandType.ERROR,
                "permission denied: " + clientId + " cannot " + perm + " on " + lockName)
                .serialize() + "\n");
            return false;
        }
        return chain.next(ctx, msg, raw);
    }
}
