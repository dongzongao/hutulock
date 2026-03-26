package com.hutulock.server.security;

import com.hutulock.spi.security.Authorizer.Permission;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AclAuthorizer 单元测试
 */
class AclAuthorizerTest {

    @Test
    void allowExactMatch() {
        AclAuthorizer authz = new AclAuthorizer()
            .allow("order-service", "order-lock", Permission.LOCK)
            .denyAll();

        assertTrue(authz.isPermitted("order-service", "order-lock", Permission.LOCK));
        assertFalse(authz.isPermitted("order-service", "order-lock", Permission.UNLOCK));
        assertFalse(authz.isPermitted("other-service", "order-lock", Permission.LOCK));
    }

    @Test
    void allowWildcardLock() {
        AclAuthorizer authz = new AclAuthorizer()
            .allow("order-service", "order-*", Permission.LOCK)
            .allow("order-service", "order-*", Permission.UNLOCK)
            .denyAll();

        assertTrue(authz.isPermitted("order-service", "order-lock", Permission.LOCK));
        assertTrue(authz.isPermitted("order-service", "order-payment", Permission.UNLOCK));
        assertFalse(authz.isPermitted("order-service", "payment-lock", Permission.LOCK));
    }

    @Test
    void allowAllClients() {
        AclAuthorizer authz = new AclAuthorizer()
            .allow("*", "public-lock", Permission.LOCK)
            .denyAll();

        assertTrue(authz.isPermitted("any-service", "public-lock", Permission.LOCK));
        assertTrue(authz.isPermitted("another-service", "public-lock", Permission.LOCK));
    }

    @Test
    void denyBeforeAllow() {
        AclAuthorizer authz = new AclAuthorizer()
            .deny("blocked-service", "*", Permission.LOCK)
            .allow("*", "*", Permission.LOCK)
            .denyAll();

        assertFalse(authz.isPermitted("blocked-service", "any-lock", Permission.LOCK));
        assertTrue(authz.isPermitted("other-service", "any-lock", Permission.LOCK));
    }

    @Test
    void defaultDenyAll() {
        AclAuthorizer authz = new AclAuthorizer().denyAll();
        assertFalse(authz.isPermitted("any", "any", Permission.LOCK));
    }

    @Test
    void defaultAllowAll() {
        AclAuthorizer authz = new AclAuthorizer().allowAll();
        assertTrue(authz.isPermitted("any", "any", Permission.LOCK));
    }

    @Test
    void adminPermission() {
        AclAuthorizer authz = new AclAuthorizer()
            .allow("admin", "*", Permission.ADMIN)
            .denyAll();

        assertTrue(authz.isPermitted("admin", "any-lock", Permission.ADMIN));
        assertFalse(authz.isPermitted("admin", "any-lock", Permission.LOCK));
    }
}
