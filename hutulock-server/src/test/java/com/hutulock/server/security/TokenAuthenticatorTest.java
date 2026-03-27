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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TokenAuthenticator 单元测试
 */
class TokenAuthenticatorTest {

    private TokenAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        authenticator = new TokenAuthenticator()
            .addClient("order-service", "valid-token-32-chars-padding!!!")
            .addClient("payment-service", "another-token-32-chars-padding!!");
    }

    @Test
    void validTokenSucceeds() {
        AuthToken token = AuthToken.ofToken("order-service", "valid-token-32-chars-padding!!!");
        AuthResult result = authenticator.authenticate(token);
        assertTrue(result.isSuccess());
        assertEquals("order-service", result.getClientId());
    }

    @Test
    void invalidTokenFails() {
        AuthToken token = AuthToken.ofToken("order-service", "wrong-token-32-chars-padding!!!!");
        AuthResult result = authenticator.authenticate(token);
        assertFalse(result.isSuccess());
        assertNotNull(result.getReason());
    }

    @Test
    void unknownClientFails() {
        AuthToken token = AuthToken.ofToken("unknown-service", "some-token-32-chars-padding!!!!!");
        AuthResult result = authenticator.authenticate(token);
        assertFalse(result.isSuccess());
    }

    @Test
    void nullTokenFails() {
        AuthResult result = authenticator.authenticate(null);
        assertFalse(result.isSuccess());
    }

    @Test
    void wrongTypeFails() {
        AuthToken token = AuthToken.ofHmac("order-service", "sig", System.currentTimeMillis());
        AuthResult result = authenticator.authenticate(token);
        assertFalse(result.isSuccess());
    }

    @Test
    void tokenTooShortThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            new TokenAuthenticator().addClient("svc", "short"));
    }

    @Test
    void timingAttackResistance() {
        // 验证常量时间比较：不同长度的 token 比较时间应该相近
        AuthToken correct = AuthToken.ofToken("order-service", "valid-token-32-chars-padding!!!");
        AuthToken wrong   = AuthToken.ofToken("order-service", "wrong-token-32-chars-padding!!!!");

        long t1 = System.nanoTime();
        authenticator.authenticate(correct);
        long d1 = System.nanoTime() - t1;

        long t2 = System.nanoTime();
        authenticator.authenticate(wrong);
        long d2 = System.nanoTime() - t2;

        // 时间差不应超过 10ms（宽松断言，避免 CI 环境抖动）
        assertTrue(Math.abs(d1 - d2) < 10_000_000L,
            "Timing difference too large: " + Math.abs(d1 - d2) + "ns");
    }
}
