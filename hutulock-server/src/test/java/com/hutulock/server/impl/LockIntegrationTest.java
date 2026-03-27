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
package com.hutulock.server.impl;

import com.hutulock.config.api.ServerProperties;
import com.hutulock.model.protocol.CommandType;
import com.hutulock.model.protocol.Message;
import com.hutulock.model.session.Session;
import com.hutulock.spi.event.EventBus;
import com.hutulock.spi.metrics.MetricsCollector;
import com.hutulock.spi.session.SessionTracker;
import com.hutulock.spi.storage.ZNodeStorage;
import io.netty.channel.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
        io.netty.channel.ChannelId cid = mock(io.netty.channel.ChannelId.class);
        when(cid.asShortText()).thenReturn("ch-single");
        when(mockChannel.id()).thenReturn(cid);
        when(mockChannel.isActive()).thenReturn(true);
        when(mockChannel.writeAndFlush(any())).thenReturn(mock(io.netty.channel.ChannelFuture.class));

        Session session = sessionManager.createSession("client-1", mockChannel);
        String sessionId = session.getSessionId();

        // 获取锁
        lockManager.apply(1, Message.of(CommandType.LOCK, "order-lock", sessionId).serialize());

        // 验证收到 OK 响应
        verify(mockChannel, atLeastOnce()).writeAndFlush(argThat(arg ->
            arg.toString().contains("OK")));

        // 释放锁（使用实际创建的 seqPath）
        String seqPath = "/locks/order-lock/seq-0000000001";
        lockManager.apply(2, Message.of(CommandType.UNLOCK, seqPath, sessionId).serialize());

        // 验证收到 RELEASED 响应
        verify(mockChannel, atLeastOnce()).writeAndFlush(argThat(arg ->
            arg.toString().contains("RELEASED")));
    }

    @Test
    void twoClientsCompeteForLock() throws InterruptedException {
        Channel ch1 = mock(Channel.class);
        Channel ch2 = mock(Channel.class);
        io.netty.channel.ChannelId cid1 = mock(io.netty.channel.ChannelId.class);
        io.netty.channel.ChannelId cid2 = mock(io.netty.channel.ChannelId.class);
        when(cid1.asShortText()).thenReturn("ch-1");
        when(cid2.asShortText()).thenReturn("ch-2");
        when(ch1.id()).thenReturn(cid1);
        when(ch2.id()).thenReturn(cid2);
        when(ch1.isActive()).thenReturn(true);
        when(ch2.isActive()).thenReturn(true);
        when(ch1.writeAndFlush(any())).thenReturn(mock(io.netty.channel.ChannelFuture.class));
        when(ch2.writeAndFlush(any())).thenReturn(mock(io.netty.channel.ChannelFuture.class));

        Session s1 = sessionManager.createSession("client-1", ch1);
        Session s2 = sessionManager.createSession("client-2", ch2);

        // client-1 获取锁
        lockManager.apply(1, Message.of(CommandType.LOCK, "order-lock", s1.getSessionId()).serialize());
        verify(ch1, atLeastOnce()).writeAndFlush(argThat(arg -> arg.toString().contains("OK")));

        // client-2 尝试获取锁，进入等待
        lockManager.apply(2, Message.of(CommandType.LOCK, "order-lock", s2.getSessionId()).serialize());
        verify(ch2, atLeastOnce()).writeAndFlush(argThat(arg -> arg.toString().contains("WAIT")));

        // client-1 释放锁
        lockManager.apply(3, Message.of(CommandType.UNLOCK,
            "/locks/order-lock/seq-0000000001", s1.getSessionId()).serialize());

        // client-2 recheck
        lockManager.recheckLock("order-lock", "/locks/order-lock/seq-0000000002", s2.getSessionId());

        // 验证 client-2 获锁
        verify(ch2, atLeastOnce()).writeAndFlush(argThat(arg -> arg.toString().contains("OK")));
    }

    @Test
    void sessionExpireReleasesLock() throws InterruptedException {
        Channel ch1 = mock(Channel.class);
        Channel ch2 = mock(Channel.class);
        io.netty.channel.ChannelId cid1 = mock(io.netty.channel.ChannelId.class);
        io.netty.channel.ChannelId cid2 = mock(io.netty.channel.ChannelId.class);
        when(cid1.asShortText()).thenReturn("ch-expire-1");
        when(cid2.asShortText()).thenReturn("ch-expire-2");
        when(ch1.id()).thenReturn(cid1);
        when(ch2.id()).thenReturn(cid2);
        when(ch1.isActive()).thenReturn(true);
        when(ch2.isActive()).thenReturn(true);
        when(ch1.writeAndFlush(any())).thenReturn(mock(io.netty.channel.ChannelFuture.class));
        when(ch2.writeAndFlush(any())).thenReturn(mock(io.netty.channel.ChannelFuture.class));

        Session s1 = sessionManager.createSession("client-1", ch1, 50);
        Session s2 = sessionManager.createSession("client-2", ch2);

        lockManager.apply(1, Message.of(CommandType.LOCK, "order-lock", s1.getSessionId()).serialize());
        verify(ch1, atLeastOnce()).writeAndFlush(argThat(arg -> arg.toString().contains("OK")));

        lockManager.apply(2, Message.of(CommandType.LOCK, "order-lock", s2.getSessionId()).serialize());
        verify(ch2, atLeastOnce()).writeAndFlush(argThat(arg -> arg.toString().contains("WAIT")));

        Thread.sleep(200);
        zNodeTree.cleanupSession(s1.getSessionId());

        lockManager.recheckLock("order-lock", "/locks/order-lock/seq-0000000002", s2.getSessionId());
        verify(ch2, atLeastOnce()).writeAndFlush(argThat(arg -> arg.toString().contains("OK")));
    }

}
