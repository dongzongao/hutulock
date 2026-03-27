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
