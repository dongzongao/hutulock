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
 * Admin console session token store.
 * Default credentials: admin / admin123
 * Override via -Dhutulock.admin.username and -Dhutulock.admin.password
 */
public final class AdminTokenStore {

    public static final String DEFAULT_USERNAME =
        System.getProperty("hutulock.admin.username", "admin");
    public static final String DEFAULT_PASSWORD =
        System.getProperty("hutulock.admin.password", "admin123");

    private static final long TOKEN_TTL_MS = 8 * 60 * 60 * 1000L;
    private static final SecureRandom RNG = new SecureRandom();

    private final Map<String, Long> tokens = new ConcurrentHashMap<>();

    public String login(String username, String password) {
        if (!DEFAULT_USERNAME.equals(username) || !DEFAULT_PASSWORD.equals(password)) return null;
        byte[] bytes = new byte[32];
        RNG.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.put(token, System.currentTimeMillis() + TOKEN_TTL_MS);
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
}
