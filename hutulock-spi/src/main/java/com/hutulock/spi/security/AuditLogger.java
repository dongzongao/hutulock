/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.spi.security;

/**
 * 安全审计日志接口（SPI 边界契约）
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public interface AuditLogger {

    void logAuth(String clientId, String remoteAddr, boolean success, String reason);
    void logAuthz(String clientId, String lockName, Authorizer.Permission permission, boolean permitted);
    void logLockOperation(String clientId, String lockName, String operation, boolean success);

    static AuditLogger noop() { return NoOpAuditLogger.INSTANCE; }
}
