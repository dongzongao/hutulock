/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
