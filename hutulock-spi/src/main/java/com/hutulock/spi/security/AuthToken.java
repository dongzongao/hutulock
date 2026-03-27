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
package com.hutulock.spi.security;

/**
 * 认证令牌（值对象）
 *
 * <p>支持 TOKEN（静态令牌）和 HMAC（签名）两种认证方式。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class AuthToken {

    public enum Type { TOKEN, HMAC }

    private final Type   type;
    private final String clientId;
    private final String credential;
    private final long   timestamp;

    private AuthToken(Type type, String clientId, String credential, long timestamp) {
        this.type = type; this.clientId = clientId;
        this.credential = credential; this.timestamp = timestamp;
    }

    public static AuthToken ofToken(String clientId, String token) {
        return new AuthToken(Type.TOKEN, clientId, token, 0);
    }

    public static AuthToken ofHmac(String clientId, String signature, long timestamp) {
        return new AuthToken(Type.HMAC, clientId, signature, timestamp);
    }

    public static AuthToken parse(String clientId, String raw) {
        if (raw == null || raw.isBlank()) return null;
        if (raw.startsWith("TOKEN:")) return ofToken(clientId, raw.substring(6));
        if (raw.startsWith("HMAC:")) {
            String[] p = raw.substring(5).split(":", 2);
            if (p.length == 2) {
                try { return ofHmac(clientId, p[1], Long.parseLong(p[0])); }
                catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    public Type   getType()       { return type;       }
    public String getClientId()   { return clientId;   }
    public String getCredential() { return credential; }
    public long   getTimestamp()  { return timestamp;  }

    @Override
    public String toString() {
        return String.format("AuthToken{type=%s, client=%s}", type, clientId);
    }
}
