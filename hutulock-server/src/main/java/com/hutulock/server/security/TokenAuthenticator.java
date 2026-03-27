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

import com.hutulock.spi.security.AuthResult;
import com.hutulock.spi.security.AuthToken;
import com.hutulock.spi.security.Authenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 静态 Token 认证器（{@link Authenticator} 的 Token 实现）
 *
 * <p>维护一个 {@code clientId → token} 的映射表，
 * 客户端提供的 token 与预配置的 token 完全匹配时认证通过。
 *
 * <p>适用场景：内部微服务间调用，token 通过配置文件或环境变量注入。
 *
 * <p>使用示例：
 * <pre>{@code
 *   Authenticator auth = new TokenAuthenticator()
 *       .addClient("order-service", "secret-token-abc")
 *       .addClient("payment-service", "secret-token-xyz");
 * }</pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class TokenAuthenticator implements Authenticator {

    private static final Logger log = LoggerFactory.getLogger(TokenAuthenticator.class);

    /** clientId → 预配置的 token */
    private final Map<String, String> tokenStore = new ConcurrentHashMap<>();

    /**
     * 注册客户端及其 token。
     *
     * @param clientId 客户端 ID
     * @param token    预共享 token（建议使用随机生成的高熵字符串）
     * @return this（支持链式调用）
     */
    public TokenAuthenticator addClient(String clientId, String token) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientId cannot be blank");
        }
        if (token == null || token.length() < 16) {
            throw new IllegalArgumentException("token must be at least 16 characters");
        }
        tokenStore.put(clientId, token);
        return this;
    }

    @Override
    public AuthResult authenticate(AuthToken authToken) {
        if (authToken == null) {
            return AuthResult.failure("no auth token provided");
        }
        if (authToken.getType() != AuthToken.Type.TOKEN) {
            return AuthResult.failure("expected TOKEN type, got " + authToken.getType());
        }

        String clientId      = authToken.getClientId();
        String expectedToken = tokenStore.get(clientId);

        if (expectedToken == null) {
            log.warn("Auth failed: unknown clientId={}", clientId);
            return AuthResult.failure("unknown client: " + clientId);
        }

        // 使用常量时间比较，防止时序攻击
        if (!constantTimeEquals(expectedToken, authToken.getCredential())) {
            log.warn("Auth failed: invalid token for clientId={}", clientId);
            return AuthResult.failure("invalid token");
        }

        log.debug("Auth success: clientId={}", clientId);
        return AuthResult.success(clientId);
    }

    /**
     * 常量时间字符串比较，防止时序攻击（Timing Attack）。
     * 无论字符串在哪个位置不匹配，比较时间都相同。
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
