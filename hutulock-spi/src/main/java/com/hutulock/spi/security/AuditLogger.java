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
