package com.hutulock.server.security;

import com.hutulock.model.protocol.CommandType;
import com.hutulock.model.protocol.Message;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 限流过滤器（责任链第一节点）
 *
 * <p>在认证之前执行，防止未认证请求耗尽令牌桶资源。
 */
public final class RateLimitFilter implements SecurityFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private final SecurityContext security;

    public RateLimitFilter(SecurityContext security) { this.security = security; }

    @Override
    public boolean doFilter(ChannelHandlerContext ctx, Message msg, String raw, SecurityFilterChain chain) {
        String clientId = ctx.channel().attr(SecurityChannelHandler.CLIENT_ID_KEY).get();
        String limitKey = clientId != null ? clientId : ctx.channel().remoteAddress().toString();
        if (!security.getRateLimiter().tryAcquire(limitKey)) {
            log.warn("Rate limit exceeded: client={}, addr={}", limitKey, ctx.channel().remoteAddress());
            ctx.writeAndFlush(Message.of(CommandType.ERROR, "rate limit exceeded").serialize() + "\n");
            return false;
        }
        return chain.next(ctx, msg, raw);
    }
}
