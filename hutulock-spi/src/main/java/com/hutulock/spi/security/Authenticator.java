/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.spi.security;

/**
 * 认证器接口（SPI 边界契约）
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public interface Authenticator {

    AuthResult authenticate(AuthToken token);

    /** 允许所有连接（开发/测试环境）。 */
    static Authenticator allowAll() {
        return token -> AuthResult.success(token != null ? token.getClientId() : "anonymous");
    }
}
