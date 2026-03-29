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
 * Admin 控制台 Session Token 存储
 *
 * <p>使用 SecureRandom 生成 32 字节随机 token，存储在内存 Map 中。
 * token 有效期 8 小时，过期后需重新登录。
 *
 * <p>默认账户：admin / admin123（可通过系统属性覆盖）
 *   -Dhutulock.admin.username=xxx
 *   -Dhutulock.admin.password=xxx
 */
public final class AdminTokenStore {

    /** 默认用户名（可通过系统属性覆盖） */
    public static final String DEFAULT_USERNAME =
        System.getProperty("hutulock.admin.username", "admin");
    /** 默认密码（可通过系统属性覆盖） */
    public static final String DEFAULT_PASSWORD =
        System.getProperty("hutulock.admin.password", "admin123");

    /** Token 有效期：8 小时 */
    private static final long TOKEN_TTL_MS = 8 * 60 * 60 * 1000L;

    private static final SecureRandom RNG = new SecureRandom();

    /** token → 过期时间戳 */
    private final Map<String, Long> tokens = new ConcurrentHashMap<>();

    /**
     * 验证用户名密码，成功返回新 token，失败返回 null。
     */
    public String login(String username, String password) {
        // 使用 MessageDigest.isEqual 做常量时间比较，防止 timing attack
        if (!constantTimeEquals(DEFAULT_USERNAME, username)
                || !constantTimeEquals(DEFAULT_PASSWORD, password)) {
            return null;
        }
        byte[] bytes = new byte[32];
        RNG.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.put(token, System.currentTimeMillis() + TOKEN_TTL_MS);
        return token;
    }

    /** 常量时间字符串比较，防止 timing attack。 */
    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
            a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 验证 token 是否有效（存在且未过期）。
     */
    public boolean validate(String token) {
        if (token == null) return false;
        Long expiry = tokens.get(token);
        if (expiry == null) return false;
        if (System.currentTimeMillis() > expiry) {
            tokens.remove(token);
            return false;
        }
        return true;
    }

    /** 主动注销 token。 */
    public void logout(String token) {
        tokens.remove(token);
    }

    /** 清理所有过期 token（可定期调用）。 */
    public void evictExpired() {
        long now = System.currentTimeMillis();
        tokens.entrySet().removeIf(e -> e.getValue() < now);
    }
}
