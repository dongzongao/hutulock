/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.spi.security;

/**
 * 授权器接口（SPI 边界契约）
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public interface Authorizer {

    boolean isPermitted(String clientId, String lockName, Permission permission);

    static Authorizer allowAll() { return (c, l, p) -> true; }

    enum Permission { LOCK, UNLOCK, ADMIN }
}
