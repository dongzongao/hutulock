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

import java.util.ArrayList;
import java.util.List;

/**
 * 安全过滤器责任链（Chain of Responsibility）
 *
 * <p>替代 {@link SecurityChannelHandler} 中的 if-else 判断链。
 * 过滤器按注册顺序依次执行，任意一个返回 false 则中断链路。
 *
 * <p>内置过滤器（按执行顺序）：
 * <ol>
 *   <li>{@link RateLimitFilter}  — 限流（最先执行，防止未认证请求耗尽资源）</li>
 *   <li>{@link AuthFilter}       — 认证（验证 CONNECT 命令中的 token）</li>
 *   <li>{@link AuthzFilter}      — 授权（LOCK/UNLOCK 命令的 ACL 检查）</li>
 * </ol>
 *
 * <p>使用示例：
 * <pre>{@code
 *   SecurityFilterChain chain = SecurityFilterChain.builder()
 *       .add(new RateLimitFilter(security))
 *       .add(new AuthFilter(security))
 *       .add(new AuthzFilter(security))
 *       .build();
 *
 *   // 替代 if-else 判断链
 *   boolean passed = chain.execute(ctx, msg, raw);
 *   if (passed) ctx.fireChannelRead(raw);
 * }</pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class SecurityFilterChain {

    private final List<SecurityFilter> filters;
    private int cursor = 0;

    private SecurityFilterChain(List<SecurityFilter> filters) {
        this.filters = filters;
    }

    /**
     * 从链头开始执行，返回是否全部通过。
     * 每次调用都从头开始（cursor 重置），支持复用。
     */
    public boolean execute(ChannelHandlerContext ctx, Message msg, String raw) {
        cursor = 0;
        return next(ctx, msg, raw);
    }

    /**
     * 执行下一个过滤器（由 {@link SecurityFilter#doFilter} 调用）。
     */
    public boolean next(ChannelHandlerContext ctx, Message msg, String raw) {
        if (cursor >= filters.size()) return true; // 全部通过
        SecurityFilter filter = filters.get(cursor++);
        return filter.doFilter(ctx, msg, raw, this);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final List<SecurityFilter> filters = new ArrayList<>();

        public Builder add(SecurityFilter filter) { filters.add(filter); return this; }

        public SecurityFilterChain build() {
            return new SecurityFilterChain(new ArrayList<>(filters));
        }
    }
}
