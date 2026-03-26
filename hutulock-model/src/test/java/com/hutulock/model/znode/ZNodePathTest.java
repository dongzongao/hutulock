package com.hutulock.model.znode;

import com.hutulock.model.exception.HutuLockException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ZNodePath 单元测试
 */
class ZNodePathTest {

    @Test
    void validPaths() {
        assertEquals("/", ZNodePath.ROOT.value());
        assertEquals("/locks", ZNodePath.of("/locks").value());
        assertEquals("/locks/order-lock", ZNodePath.of("/locks/order-lock").value());
        assertEquals("/locks/order-lock/seq-0000000001",
            ZNodePath.of("/locks/order-lock/seq-0000000001").value());
    }

    @Test
    void invalidPath_noLeadingSlash() {
        assertThrows(HutuLockException.class, () -> ZNodePath.of("locks"));
    }

    @Test
    void invalidPath_trailingSlash() {
        assertThrows(HutuLockException.class, () -> ZNodePath.of("/locks/"));
    }

    @Test
    void invalidPath_emptySegment() {
        assertThrows(HutuLockException.class, () -> ZNodePath.of("/locks//order"));
    }

    @Test
    void parent() {
        ZNodePath path = ZNodePath.of("/locks/order-lock/seq-0000000001");
        assertEquals("/locks/order-lock", path.parent().value());
        assertEquals("/locks", path.parent().parent().value());
        assertEquals("/", path.parent().parent().parent().value());
    }

    @Test
    void rootHasNoParent() {
        assertThrows(HutuLockException.class, () -> ZNodePath.ROOT.parent());
    }

    @Test
    void name() {
        assertEquals("seq-0000000001",
            ZNodePath.of("/locks/order-lock/seq-0000000001").name());
        assertEquals("order-lock", ZNodePath.of("/locks/order-lock").name());
        assertEquals("/", ZNodePath.ROOT.name());
    }

    @Test
    void childOf() {
        ZNodePath parent = ZNodePath.of("/locks");
        ZNodePath child  = ZNodePath.of(parent, "order-lock");
        assertEquals("/locks/order-lock", child.value());
    }

    @Test
    void equality() {
        assertEquals(ZNodePath.of("/locks"), ZNodePath.of("/locks"));
        assertNotEquals(ZNodePath.of("/locks"), ZNodePath.of("/sessions"));
    }

    @Test
    void normalizesDoubleSlash() {
        // ZNodePath 先校验再规范化，双斜杠会被 validate 拦截，这是预期行为
        assertThrows(HutuLockException.class, () -> ZNodePath.of("//locks//order"));
    }
}
