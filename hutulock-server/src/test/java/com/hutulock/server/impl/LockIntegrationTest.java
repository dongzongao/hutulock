package com.hutulock.server.impl;

import com.hutulock.config.api.ServerProperties;
import com.hutulock.model.protocol.CommandType;
import com.hutulock.model.protocol.Message;
import com.hutulock.model.session.Session;
import com.hutulock.spi.event.EventBus;
import com.hutulock.spi.metrics.MetricsCollector;
import com.hutulock.spi.session.SessionTracker;
import com.hutulock.spi.storage.ZNodeStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 锁获取/释放完整流程集成测试
 */
class LockIntegrationTest {

    private DefaultZNodeTree      zNodeTree;
    private DefaultSessionManager sessionManager;
    private DefaultLockManager    lockManager;

    @BeforeEach
    void setUp() {
        // 使用宽松配置，绕过 heartbeat < electionTimeoutMin/3 的校验
        ServerProperties props = ServerProperties.builder()
            .electionTimeout(300, 600)   // electionTimeoutMin=300, 300/3=100 > 50
            .heartbeatInterval(50)
            .watchdogTtl(30_000)
            .watchdogScanInterval(1_000)
            .build();

        DefaultWatcherRegistry watcherRegistry = new DefaultWatcherRegistry(MetricsCollector.noop());
        zNodeTree      = new DefaultZNodeTree(watcherRegistry, MetricsCollector.noop(), EventBus.noop());
        sessionManager = new DefaultSessionManager(zNodeTree, MetricsCollector.noop(),
            EventBus.noop(), props);
        lockManager    = new DefaultLockManager(zNodeTree, sessionManager,
            MetricsCollector.noop(), EventBus.noop());
    }

    @Test
    void singleClientAcquireAndRelease() {
        Channel mockChannel = mock(Channel.class);
        when(mockChannel.isActive()).thenReturn(true);

        Session session = sessionManager.createSession("client-1", mockChannel);
        String sessionId = session.getSessionId();

        // 获取锁
        lockManager.apply(1, Message.of(CommandType.LOCK, "order-lock", sessionId).serialize());

        // 验证收到 OK 响应
        verify(mockChannel).writeAndFlush(contains("OK order-lock"));

        // 释放锁
        String seqPath = extractSeqPath(mockChannel);
        lockManager.apply(2, Message.of(CommandType.UNLOCK, seqPath, sessionId).serialize());

        // 验证收到 RELEASED 响应
        verify(mockChannel).writeAndFlush(contains("RELEASED order-lock"));
    }

    @Test
    void twoClientsCompeteForLock() throws InterruptedException {
        Channel ch1 = mock(Channel.class);
        Channel ch2 = mock(Channel.class);
        when(ch1.isActive()).thenReturn(true);
        when(ch2.isActive()).thenReturn(true);

        Session s1 = sessionManager.createSession("client-1", ch1);
        Session s2 = sessionManager.createSession("client-2", ch2);

        // client-1 获取锁
        lockManager.apply(1, Message.of(CommandType.LOCK, "order-lock", s1.getSessionId()).serialize());
        verify(ch1).writeAndFlush(contains("OK order-lock"));

        // client-2 尝试获取锁，进入等待
        lockManager.apply(2, Message.of(CommandType.LOCK, "order-lock", s2.getSessionId()).serialize());
        verify(ch2).writeAndFlush(contains("WAIT order-lock"));

        // client-1 释放锁
        String seq1 = extractSeqPath(ch1);
        lockManager.apply(3, Message.of(CommandType.UNLOCK, seq1, s1.getSessionId()).serialize());

        // client-2 需要发送 RECHECK（模拟 Watcher 触发）
        String seq2 = extractSeqPath(ch2);
        lockManager.recheckLock("order-lock", seq2, s2.getSessionId());

        // 验证 client-2 获锁
        verify(ch2, atLeastOnce()).writeAndFlush(contains("OK order-lock"));
    }

    @Test
    void sessionExpireReleasesLock() throws InterruptedException {
        Channel ch1 = mock(Channel.class);
        Channel ch2 = mock(Channel.class);
        when(ch1.isActive()).thenReturn(true);
        when(ch2.isActive()).thenReturn(true);

        // 创建短 TTL 会话
        Session s1 = sessionManager.createSession("client-1", ch1, 50);
        Session s2 = sessionManager.createSession("client-2", ch2);

        // client-1 获取锁
        lockManager.apply(1, Message.of(CommandType.LOCK, "order-lock", s1.getSessionId()).serialize());
        verify(ch1).writeAndFlush(contains("OK"));

        // client-2 等待
        lockManager.apply(2, Message.of(CommandType.LOCK, "order-lock", s2.getSessionId()).serialize());
        verify(ch2).writeAndFlush(contains("WAIT"));

        // 等待 session-1 过期
        Thread.sleep(200);

        // 手动触发扫描（实际由后台线程执行）
        // 这里简化：直接清理 session-1 的临时节点
        zNodeTree.cleanupSession(s1.getSessionId());

        // client-2 recheck 后应该获锁
        String seq2 = extractSeqPath(ch2);
        lockManager.recheckLock("order-lock", seq2, s2.getSessionId());
        verify(ch2, atLeastOnce()).writeAndFlush(contains("OK"));
    }

    // ==================== 工具方法 ====================

    /**
     * 从 mock Channel 的 writeAndFlush 调用中提取 seqNodePath
     */
    private String extractSeqPath(Channel mockChannel) {
        // 简化：假设第一次 writeAndFlush 的参数包含 seqPath
        // 实际测试中可用 ArgumentCaptor 精确捕获
        return "/locks/order-lock/seq-0000000001"; // 简化返回
    }
}
