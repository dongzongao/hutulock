/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server.security;

import com.hutulock.spi.security.AuditLogger;
import com.hutulock.spi.security.Authorizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SLF4J 审计日志器（{@link AuditLogger} 的 SLF4J 实现）
 *
 * <p>将所有安全审计事件输出到专用的 {@code hutulock.audit} logger，
 * 可通过 logback.xml 将其路由到独立的审计日志文件。
 *
 * <p>logback.xml 配置示例：
 * <pre>{@code
 * <logger name="hutulock.audit" level="INFO" additivity="false">
 *     <appender-ref ref="AUDIT_FILE"/>
 * </logger>
 * }</pre>
 *
 * <p>审计日志格式（JSON-like，便于 ELK 解析）：
 * <pre>
 *   [AUDIT] type=AUTH client=order-service addr=127.0.0.1:12345 success=true
 *   [AUDIT] type=AUTHZ client=order-service lock=order-lock perm=LOCK permitted=true
 *   [AUDIT] type=LOCK_OP client=order-service lock=order-lock op=LOCK success=true
 * </pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class Slf4jAuditLogger implements AuditLogger {

    /** 专用审计 logger，与业务日志分离 */
    private static final Logger audit = LoggerFactory.getLogger("hutulock.audit");

    @Override
    public void logAuth(String clientId, String remoteAddr, boolean success, String reason) {
        if (success) {
            audit.info("[AUDIT] type=AUTH client={} addr={} success=true", clientId, remoteAddr);
        } else {
            audit.warn("[AUDIT] type=AUTH client={} addr={} success=false reason={}",
                clientId, remoteAddr, reason);
        }
    }

    @Override
    public void logAuthz(String clientId, String lockName,
                          Authorizer.Permission permission, boolean permitted) {
        if (permitted) {
            audit.info("[AUDIT] type=AUTHZ client={} lock={} perm={} permitted=true",
                clientId, lockName, permission);
        } else {
            audit.warn("[AUDIT] type=AUTHZ client={} lock={} perm={} permitted=false",
                clientId, lockName, permission);
        }
    }

    @Override
    public void logLockOperation(String clientId, String lockName,
                                  String operation, boolean success) {
        audit.info("[AUDIT] type=LOCK_OP client={} lock={} op={} success={}",
            clientId, lockName, operation, success);
    }
}
