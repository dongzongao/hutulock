package com.hutulock.server.impl;

import com.hutulock.model.exception.HutuLockException;
import com.hutulock.model.watcher.WatchEvent;
import com.hutulock.model.znode.ZNode;
import com.hutulock.model.znode.ZNodePath;
import com.hutulock.model.znode.ZNodeType;
import com.hutulock.spi.event.EventBus;
import com.hutulock.spi.metrics.MetricsCollector;
import com.hutulock.spi.storage.WatcherRegistry;
import io.netty.channel.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DefaultZNodeTree 单元测试
 */
class DefaultZNodeTreeTest {

    private DefaultZNodeTree tree;
    private WatcherRegistry  mockRegistry;

    @BeforeEach
    void setUp() {
        mockRegistry = mock(WatcherRegistry.class);
        tree = new DefaultZNodeTree(mockRegistry, MetricsCollector.noop(), EventBus.noop());
    }

    @Test
    void rootExists() {
        assertTrue(tree.exists(ZNodePath.ROOT));
        assertEquals(1, tree.size()); // 只有根节点
    }

    @Test
    void createPersistentNode() {
        ZNodePath path = ZNodePath.of("/locks");
        ZNodePath actual = tree.create(path, ZNodeType.PERSISTENT, new byte[0], null);
        assertEquals(path, actual);
        assertTrue(tree.exists(path));
    }

    @Test
    void createEphemeralSeqNode() {
        tree.create(ZNodePath.of("/locks"), ZNodeType.PERSISTENT, new byte[0], null);

        ZNodePath prefix = ZNodePath.of("/locks/seq-");
        ZNodePath seq1 = tree.create(prefix, ZNodeType.EPHEMERAL_SEQ, new byte[0], "session-1");
        ZNodePath seq2 = tree.create(prefix, ZNodeType.EPHEMERAL_SEQ, new byte[0], "session-2");

        assertTrue(seq1.value().endsWith("0000000001"));
        assertTrue(seq2.value().endsWith("0000000002"));
    }

    @Test
    void getChildrenSortedBySeq() {
        tree.create(ZNodePath.of("/locks"), ZNodeType.PERSISTENT, new byte[0], null);
        ZNodePath prefix = ZNodePath.of("/locks/seq-");

        ZNodePath seq1 = tree.create(prefix, ZNodeType.EPHEMERAL_SEQ, new byte[0], "s1");
        ZNodePath seq2 = tree.create(prefix, ZNodeType.EPHEMERAL_SEQ, new byte[0], "s2");
        ZNodePath seq3 = tree.create(prefix, ZNodeType.EPHEMERAL_SEQ, new byte[0], "s3");

        List<ZNodePath> children = tree.getChildren(ZNodePath.of("/locks"));
        assertEquals(seq1, children.get(0));
        assertEquals(seq2, children.get(1));
        assertEquals(seq3, children.get(2));
    }

    @Test
    void deleteNodeFiresWatcher() {
        tree.create(ZNodePath.of("/locks"), ZNodeType.PERSISTENT, new byte[0], null);
        ZNodePath node = tree.create(ZNodePath.of("/locks/seq-"),
            ZNodeType.EPHEMERAL_SEQ, new byte[0], "s1");

        tree.delete(node);

        assertFalse(tree.exists(node));
        verify(mockRegistry).fire(node, WatchEvent.Type.NODE_DELETED);
    }

    @Test
    void createNodeFiresWatcher() {
        tree.create(ZNodePath.of("/locks"), ZNodeType.PERSISTENT, new byte[0], null);
        ZNodePath node = ZNodePath.of("/locks/order-lock");
        tree.create(node, ZNodeType.PERSISTENT, new byte[0], null);

        verify(mockRegistry).fire(node, WatchEvent.Type.NODE_CREATED);
    }

    @Test
    void parentNotFoundThrows() {
        assertThrows(HutuLockException.class, () ->
            tree.create(ZNodePath.of("/nonexistent/child"),
                ZNodeType.PERSISTENT, new byte[0], null));
    }

    @Test
    void cleanupSessionDeletesEphemeralNodes() {
        tree.create(ZNodePath.of("/locks"), ZNodeType.PERSISTENT, new byte[0], null);
        tree.create(ZNodePath.of("/locks/seq-"), ZNodeType.EPHEMERAL_SEQ, new byte[0], "session-1");
        tree.create(ZNodePath.of("/locks/seq-"), ZNodeType.EPHEMERAL_SEQ, new byte[0], "session-1");
        tree.create(ZNodePath.of("/locks/seq-"), ZNodeType.EPHEMERAL_SEQ, new byte[0], "session-2");

        List<ZNodePath> deleted = tree.cleanupSession("session-1");
        assertEquals(2, deleted.size());
        assertEquals(1, tree.getChildren(ZNodePath.of("/locks")).size()); // session-2 的节点还在
    }

    @Test
    void setDataVersionMismatchThrows() {
        tree.create(ZNodePath.of("/locks"), ZNodeType.PERSISTENT, "data".getBytes(), null);
        ZNodePath path = ZNodePath.of("/locks");

        assertThrows(HutuLockException.class, () ->
            tree.setData(path, "new".getBytes(), 99)); // 版本不匹配
    }

    @Test
    void setDataVersionMinusOneSkipsCheck() {
        tree.create(ZNodePath.of("/locks"), ZNodeType.PERSISTENT, "data".getBytes(), null);
        ZNodePath path = ZNodePath.of("/locks");
        assertDoesNotThrow(() -> tree.setData(path, "new".getBytes(), -1));
        assertArrayEquals("new".getBytes(), tree.get(path).getData());
    }
}
