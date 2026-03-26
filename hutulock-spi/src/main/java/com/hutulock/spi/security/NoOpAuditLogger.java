/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.spi.security;

/**
 * 无操作审计日志器（Null Object 模式）
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
final class NoOpAuditLogger implements AuditLogger {

    static final NoOpAuditLogger INSTANCE = new NoOpAuditLogger();

    private NoOpAuditLogger() {}

    @Override public void logAuth(String c, String r, boolean s, String reason) {}
    @Override public void logAuthz(String c, String l, Authorizer.Permission p, boolean permitted) {}
    @Override public void logLockOperation(String c, String l, String op, boolean s) {}
}
