/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server.security;

import com.hutulock.spi.security.AuditLogger;
import com.hutulock.spi.security.Authenticator;
import com.hutulock.spi.security.Authorizer;
import com.hutulock.spi.security.RateLimiter;

/**
 * 安全上下文（聚合所有安全组件）
 *
 * <p>将认证、授权、审计、限流四个安全组件聚合为一个对象，
 * 通过 Builder 模式构建，注入到 {@link SecurityChannelHandler} 和
 * {@link com.hutulock.server.LockServerHandler} 中。
 *
 * <p>使用示例：
 * <pre>{@code
 *   SecurityContext security = SecurityContext.builder()
 *       .authenticator(new TokenAuthenticator()
 *           .addClient("order-service", "my-secret-token"))
 *       .authorizer(new AclAuthorizer()
 *           .allow("order-service", "order-*", Authorizer.Permission.LOCK)
 *           .allow("order-service", "order-*", Authorizer.Permission.UNLOCK)
 *           .denyAll())
 *       .auditLogger(new Slf4jAuditLogger())
 *       .rateLimiter(new TokenBucketRateLimiter(100, 200))
 *       .build();
 * }</pre>
 *
 * <p>若不配置安全组件，默认使用 NoOp 实现（允许所有操作，不记录审计）。
 * 生产环境必须显式配置认证器和授权器。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class SecurityContext {

    private final Authenticator authenticator;
    private final Authorizer    authorizer;
    private final AuditLogger   auditLogger;
    private final RateLimiter   rateLimiter;
    /** 是否启用安全检查（false 时跳过所有安全逻辑，用于开发环境） */
    private final boolean       enabled;

    private SecurityContext(Builder b) {
        this.authenticator = b.authenticator;
        this.authorizer    = b.authorizer;
        this.auditLogger   = b.auditLogger;
        this.rateLimiter   = b.rateLimiter;
        this.enabled       = b.enabled;
    }

    public Authenticator getAuthenticator() { return authenticator; }
    public Authorizer    getAuthorizer()    { return authorizer;    }
    public AuditLogger   getAuditLogger()   { return auditLogger;   }
    public RateLimiter   getRateLimiter()   { return rateLimiter;   }
    public boolean       isEnabled()        { return enabled;       }

    /**
     * 创建一个禁用所有安全检查的上下文（开发/测试环境）。
     * 生产环境禁止使用此方法。
     *
     * @return 禁用安全的 SecurityContext
     */
    public static SecurityContext disabled() {
        return builder().enabled(false).build();
    }

    public static Builder builder() { return new Builder(); }

    /** SecurityContext 构建器 */
    public static final class Builder {
        private Authenticator authenticator = Authenticator.allowAll();
        private Authorizer    authorizer    = Authorizer.allowAll();
        private AuditLogger   auditLogger   = AuditLogger.noop();
        private RateLimiter   rateLimiter   = RateLimiter.unlimited();
        private boolean       enabled       = true;

        /**
         * 设置认证器。
         * 推荐使用 {@link TokenAuthenticator} 或 {@link HmacAuthenticator}。
         */
        public Builder authenticator(Authenticator a) { this.authenticator = a; return this; }

        /**
         * 设置授权器。
         * 推荐使用 {@link AclAuthorizer} 并调用 {@code denyAll()} 设置默认拒绝。
         */
        public Builder authorizer(Authorizer a)       { this.authorizer    = a; return this; }

        /** 设置审计日志器。推荐使用 {@link Slf4jAuditLogger}。 */
        public Builder auditLogger(AuditLogger a)     { this.auditLogger   = a; return this; }

        /** 设置限流器。推荐使用 {@link TokenBucketRateLimiter}。 */
        public Builder rateLimiter(RateLimiter r)     { this.rateLimiter   = r; return this; }

        /** 是否启用安全检查，默认 true。 */
        public Builder enabled(boolean e)             { this.enabled       = e; return this; }

        public SecurityContext build() { return new SecurityContext(this); }
    }
}
