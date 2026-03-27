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
 * 认证结果（值对象）
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class AuthResult {

    private final boolean success;
    private final String  clientId;
    private final String  reason;

    private AuthResult(boolean success, String clientId, String reason) {
        this.success = success; this.clientId = clientId; this.reason = reason;
    }

    public static AuthResult success(String clientId) { return new AuthResult(true, clientId, null); }
    public static AuthResult failure(String reason)   { return new AuthResult(false, null, reason); }

    public boolean isSuccess()  { return success;  }
    public String  getClientId(){ return clientId; }
    public String  getReason()  { return reason;   }

    @Override
    public String toString() {
        return success ? "AuthResult{success, client=" + clientId + "}"
                       : "AuthResult{failure, reason=" + reason + "}";
    }
}
