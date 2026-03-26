/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server.security;

import com.hutulock.spi.security.Authorizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * ACL 授权器（{@link Authorizer} 的规则列表实现）
 *
 * <p>基于有序规则列表，按顺序匹配第一条符合的规则。
 * 支持精确匹配和通配符（{@code *}）。
 *
 * <p>规则格式：{@code clientId:lockPattern:permission}
 * <ul>
 *   <li>{@code clientId}    — 精确匹配或 {@code *}（匹配所有客户端）</li>
 *   <li>{@code lockPattern} — 精确匹配或 {@code prefix*}（前缀匹配）或 {@code *}（匹配所有锁）</li>
 *   <li>{@code permission}  — {@code LOCK}、{@code UNLOCK}、{@code ADMIN} 或 {@code *}（所有权限）</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 *   Authorizer authz = new AclAuthorizer()
 *       // order-service 可以操作所有 order-* 锁
 *       .allow("order-service", "order-*", Permission.LOCK)
 *       .allow("order-service", "order-*", Permission.UNLOCK)
 *       // admin 可以做任何操作
 *       .allow("admin", "*", Permission.ADMIN)
 *       // 默认拒绝所有
 *       .denyAll();
 * }</pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class AclAuthorizer implements Authorizer {

    private static final Logger log = LoggerFactory.getLogger(AclAuthorizer.class);

    private final List<AclRule> rules = new ArrayList<>();
    /** 无匹配规则时的默认行为，默认拒绝 */
    private boolean defaultAllow = false;

    /**
     * 添加允许规则。
     *
     * @param clientPattern 客户端 ID 模式（精确或 {@code *}）
     * @param lockPattern   锁名称模式（精确、前缀 {@code prefix*} 或 {@code *}）
     * @param permission    权限类型
     * @return this（链式调用）
     */
    public AclAuthorizer allow(String clientPattern, String lockPattern, Permission permission) {
        rules.add(new AclRule(clientPattern, lockPattern, permission, true));
        return this;
    }

    /**
     * 添加拒绝规则。
     *
     * @param clientPattern 客户端 ID 模式
     * @param lockPattern   锁名称模式
     * @param permission    权限类型
     * @return this（链式调用）
     */
    public AclAuthorizer deny(String clientPattern, String lockPattern, Permission permission) {
        rules.add(new AclRule(clientPattern, lockPattern, permission, false));
        return this;
    }

    /**
     * 设置默认拒绝所有（无匹配规则时）。这是推荐的安全默认值。
     *
     * @return this（链式调用）
     */
    public AclAuthorizer denyAll() {
        this.defaultAllow = false;
        return this;
    }

    /**
     * 设置默认允许所有（无匹配规则时）。仅用于宽松模式。
     *
     * @return this（链式调用）
     */
    public AclAuthorizer allowAll() {
        this.defaultAllow = true;
        return this;
    }

    @Override
    public boolean isPermitted(String clientId, String lockName, Permission permission) {
        for (AclRule rule : rules) {
            if (rule.matches(clientId, lockName, permission)) {
                log.debug("ACL rule matched: client={}, lock={}, perm={}, allow={}",
                    clientId, lockName, permission, rule.allow);
                return rule.allow;
            }
        }
        log.debug("No ACL rule matched: client={}, lock={}, perm={}, default={}",
            clientId, lockName, permission, defaultAllow);
        return defaultAllow;
    }

    // ==================== 内部规则类 ====================

    private static final class AclRule {
        final String     clientPattern;
        final String     lockPattern;
        final Permission permission;
        final boolean    allow;

        AclRule(String clientPattern, String lockPattern, Permission permission, boolean allow) {
            this.clientPattern = clientPattern;
            this.lockPattern   = lockPattern;
            this.permission    = permission;
            this.allow         = allow;
        }

        boolean matches(String clientId, String lockName, Permission perm) {
            return matchesPattern(clientPattern, clientId)
                && matchesPattern(lockPattern, lockName)
                && (permission == null || permission == perm);
        }

        /**
         * 模式匹配：支持精确匹配、前缀通配符（{@code prefix*}）和全通配符（{@code *}）。
         */
        private static boolean matchesPattern(String pattern, String value) {
            if ("*".equals(pattern)) return true;
            if (pattern.endsWith("*")) {
                return value.startsWith(pattern.substring(0, pattern.length() - 1));
            }
            return pattern.equals(value);
        }
    }
}
