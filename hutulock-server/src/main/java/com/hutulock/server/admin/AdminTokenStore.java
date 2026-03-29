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
package com.hutulock.server.admin;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Admin 控制台 Session Token 存储
 *
 * <p>凭证（username / password）通过构造函数注入，来源于 {@link com.hutulock.config.api.ServerProperties}，
 * 最终由 {@code hutulock.yml} 的 {@code server.admin.username/password} 字段提供。
 * 不再从 {@code System.getProperty} 读取，消除硬编码的根因。
 *
 * <p>Token 生命周期：
 * <ul>
 *   <li>登录成功 → 生成 32 字节 SecureRandom token，存入内存 Map</li>
 *   <li>有效期由 {@code tokenTtlMs} 控制（来自配置，默认 8 小时）</li>
 *   <li>定期调用 {@link #evictExpired()} 清理过期 token</li>
 * </ul>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class AdminTokenStore {

    private static final SecureRandom RNG = new SecureRandom();

    private final String username;
    private final String password;
    private final long   tokenTtlMs;

    /** token → 过期时间戳 */
    private final Map<String, Long> tokens = new ConcurrentHashMap<>();

    /**
     * @param username   admin 用户名（来自 ServerProperties.adminUsername）
     * @param password   admin 密码（来自 ServerProperties.adminPassword）
     * @param tokenTtlMs token 有效期毫秒（来自 ServerProperties.adminTokenTtlMs）
     */
    public AdminTokenStore(String username, String password, long tokenTtlMs) {
        this.username   = username;
        this.password   = password;
        this.tokenTtlMs = tokenTtlMs;
    }

    /** 获取当前配置的用户名（仅用于日志展示，不暴露密码）。 */
    public String getUsername() { return username; }

    /**
     * 验证用户名密码，成功返回新 token，失败返回 null。
     * 使用常量时间比较防止 timing attack。
     */
    public String login(String inputUsername, String inputPassword) {
        if (inputUsername == null || inputPassword == null) return null;
        if (!constantTimeEquals(username, inputUsername)
                || !constantTimeEquals(password, inputPassword)) return null;
        byte[] bytes = new byte[32];
        RNG.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.put(token, System.currentTimeMillis() + tokenTtlMs);
        return token;
    }

    /** 验证 token 是否有效（存在且未过期）。 */
    public boolean validate(String token) {
        if (token == null) return false;
        Long expiry = tokens.get(token);
        if (expiry == null) return false;
        if (System.currentTimeMillis() > expiry) { tokens.remove(token); return false; }
        return true;
    }

    /** 主动注销 token。 */
    public void logout(String token) { tokens.remove(token); }

    /** 清理所有过期 token（定期调用）。 */
    public void evictExpired() {
        long now = System.currentTimeMillis();
        tokens.entrySet().removeIf(e -> e.getValue() < now);
    }

    /** 常量时间字符串比较，防止 timing attack。 */
    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
            a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
