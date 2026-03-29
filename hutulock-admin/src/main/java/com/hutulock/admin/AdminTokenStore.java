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
package com.hutulock.admin;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Admin 控制台 Session Token 存储（hutulock-admin 模块版本）
 *
 * <p>凭证通过构造函数注入，来源于 {@link com.hutulock.config.api.ServerProperties}，
 * 最终由 {@code hutulock.yml} 的 {@code server.admin.username/password} 字段提供。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class AdminTokenStore {

    private static final SecureRandom RNG = new SecureRandom();

    private final String username;
    private final String password;
    private final long   tokenTtlMs;

    private final Map<String, Long> tokens = new ConcurrentHashMap<>();

    public AdminTokenStore(String username, String password, long tokenTtlMs) {
        this.username   = username;
        this.password   = password;
        this.tokenTtlMs = tokenTtlMs;
    }

    /** 获取当前配置的用户名（仅用于日志展示）。 */
    public String getUsername() { return username; }

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

    public boolean validate(String token) {
        if (token == null) return false;
        Long expiry = tokens.get(token);
        if (expiry == null) return false;
        if (System.currentTimeMillis() > expiry) { tokens.remove(token); return false; }
        return true;
    }

    public void logout(String token) { tokens.remove(token); }

    public void evictExpired() {
        long now = System.currentTimeMillis();
        tokens.entrySet().removeIf(e -> e.getValue() < now);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
            a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
