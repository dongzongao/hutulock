/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.client;

import com.hutulock.config.api.ClientProperties;
import com.hutulock.model.protocol.CommandType;
import com.hutulock.model.protocol.Message;
import com.hutulock.model.watcher.WatchEvent;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * HutuLock 分布式锁客户端
 *
 * <p>核心设计（ZooKeeper 风格）：
 * <ol>
 *   <li>Session 会话：独立于 TCP 连接，断线重连后可恢复</li>
 *   <li>Watcher 驱动：收到 NODE_DELETED 事件后重新检查锁，而不是服务端主动推送</li>
 *   <li>顺序临时节点：公平锁，每个等待者只监听前一个节点，避免羊群效应</li>
 *   <li>看门狗：持锁期间定时发送 RENEW 心跳，防止服务端 TTL 过期</li>
 * </ol>
 *
 * <p>简单用法：
 * <pre>{@code
 *   HutuLockClient client = HutuLockClient.builder()
 *       .addNode("127.0.0.1", 8881)
 *       .addNode("127.0.0.1", 8882)
 *       .build();
 *   client.connect();
 *
 *   boolean held = client.lock("order-lock");
 *   try { ... } finally { client.unlock("order-lock"); }
 * }</pre>
 *
 * <p>带看门狗的完整用法：
 * <pre>{@code
 *   LockContext ctx = LockContext.builder("order-lock", client.getSessionId())
 *       .ttl(30, TimeUnit.SECONDS)
 *       .watchdogInterval(10, TimeUnit.SECONDS)
 *       .onExpired(name -> abortWork())
 *       .build();
 *   client.lock(ctx);
 *   try { ... } finally { client.unlock(ctx); }
 * }</pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class HutuLockClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HutuLockClient.class);

    private final ClientProperties config;
    private final List<String[]>   nodes = new ArrayList<>();

    private volatile Channel           channel;
    private volatile LockClientHandler handler;
    private volatile String            sessionId;

    private final EventLoopGroup group = new NioEventLoopGroup();

    /** lockName → LockContext（持有的锁） */
    private final Map<String, LockContext> heldContexts = new ConcurrentHashMap<>();

    /** 共享看门狗调度器，所有 LockContext 复用 */
    private final ScheduledExecutorService watchdogScheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hutulock-watchdog");
            t.setDaemon(true);
            return t;
        });

    private HutuLockClient(Builder b) {
        this.config = b.config;
        this.nodes.addAll(b.nodes);
    }

    public String getSessionId() { return sessionId; }

    // ==================== 连接与会话 ====================

    /**
     * 连接到集群并建立会话。
     *
     * @throws Exception 所有节点均不可达
     */
    public void connect() throws Exception {
        connectToAny(new ArrayList<>(nodes));
        this.sessionId = establishSession(null);
        log.info("Connected, sessionId={}", sessionId);
    }

    private String establishSession(String existingSessionId) throws Exception {
        String arg = existingSessionId != null ? existingSessionId : "";
        Message req = arg.isEmpty()
            ? Message.of(CommandType.CONNECT)
            : Message.of(CommandType.CONNECT, arg);

        CompletableFuture<Message> future = handler.registerRequest("CONNECT");
        channel.writeAndFlush(req.serialize() + "\n");

        Message resp = future.get(config.connectTimeoutMs, TimeUnit.MILLISECONDS);
        if (resp.getType() != CommandType.CONNECTED) {
            throw new RuntimeException("Failed to establish session: " + resp);
        }
        return resp.arg(0);
    }

    private void connectToAny(List<String[]> candidates) throws Exception {
        Exception last = null;
        for (String[] node : candidates) {
            try {
                doConnect(node[0], Integer.parseInt(node[1]));
                return;
            } catch (Exception e) {
                last = e;
                log.warn("Failed to connect to {}:{}", node[0], node[1]);
            }
        }
        throw new RuntimeException("All nodes unreachable", last);
    }

    private void doConnect(String host, int port) throws InterruptedException {
        LockClientHandler newHandler = new LockClientHandler();
        newHandler.setRedirectListener(this::handleRedirect);

        channel = new Bootstrap()
            .group(group)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.connectTimeoutMs)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline()
                      .addLast(new LineBasedFrameDecoder(config.maxFrameLength))
                      .addLast(new StringDecoder(CharsetUtil.UTF_8))
                      .addLast(new StringEncoder(CharsetUtil.UTF_8))
                      .addLast(newHandler);
                }
            })
            .connect(host, port).sync().channel();
        handler = newHandler;
        log.info("Connected to {}:{}", host, port);
    }

    // ==================== 简单 API ====================

    /** 使用默认配置获取锁（自动创建 LockContext）。 */
    public boolean lock(String lockName) throws Exception {
        LockContext ctx = LockContext.builder(lockName, sessionId)
            .scheduler(watchdogScheduler)
            .build();
        return lock(ctx);
    }

    /** 使用默认配置释放锁。 */
    public void unlock(String lockName) throws Exception {
        LockContext ctx = heldContexts.get(lockName);
        if (ctx == null) throw new IllegalStateException("lock [" + lockName + "] not held");
        unlock(ctx);
    }

    // ==================== Context API ====================

    /**
     * 使用 LockContext 获取锁（ZooKeeper 顺序节点 + Watcher 驱动）。
     *
     * <p>流程：
     * <ol>
     *   <li>发送 LOCK → 服务端创建 EPHEMERAL_SEQ 节点</li>
     *   <li>收到 OK → 获锁成功，启动看门狗</li>
     *   <li>收到 WAIT seqNodePath → 等待 NODE_DELETED Watcher 事件</li>
     *   <li>收到 WATCH_EVENT NODE_DELETED → 发送 RECHECK → 重新检查</li>
     * </ol>
     */
    public boolean lock(LockContext ctx) throws Exception {
        return lock(ctx, config.lockTimeoutS, TimeUnit.SECONDS);
    }

    public boolean lock(LockContext ctx, long timeout, TimeUnit unit) throws Exception {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);

        while (System.currentTimeMillis() < deadline) {
            CompletableFuture<Message> future = handler.registerRequest("LOCK:" + ctx.getLockName());
            channel.writeAndFlush(
                Message.of(CommandType.LOCK, ctx.getLockName(), sessionId).serialize() + "\n");

            long remaining = deadline - System.currentTimeMillis();
            Message resp = future.get(Math.max(remaining, 1000), TimeUnit.MILLISECONDS);

            if (resp.getType() == CommandType.REDIRECT) {
                handleRedirect(resp.arg(0));
                continue;
            }

            if (resp.getType() == CommandType.OK) {
                ctx.setSeqNodePath(resp.arg(1));
                ctx.startWatchdog(channel);
                heldContexts.put(ctx.getLockName(), ctx);
                log.info("Lock acquired: lock={}, seq={}", ctx.getLockName(), ctx.getSeqNodePath());
                return true;
            }

            if (resp.getType() == CommandType.WAIT) {
                ctx.setSeqNodePath(resp.arg(1));
                if (waitForLock(ctx, deadline)) return true;
                break;
            }
        }
        return false;
    }

    /**
     * 等待 Watcher 事件，收到后重新检查锁（ZooKeeper 核心模式）。
     */
    private boolean waitForLock(LockContext ctx, long deadline) throws Exception {
        CompletableFuture<WatchEvent> watchFuture = new CompletableFuture<>();
        handler.registerWatcher(ctx.getSeqNodePath(), watchFuture::complete);

        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) return false;

        WatchEvent event = watchFuture.get(remaining, TimeUnit.MILLISECONDS);
        if (event.getType() == WatchEvent.Type.SESSION_EXPIRED) {
            ctx.markExpired();
            return false;
        }

        return recheckLock(ctx, deadline);
    }

    /**
     * 重新检查锁（Watcher 触发后重新检查锁）。
     */
    private boolean recheckLock(LockContext ctx, long deadline) throws Exception {
        CompletableFuture<Message> future =
            handler.registerRequest("RECHECK:" + ctx.getSeqNodePath());
        channel.writeAndFlush(
            Message.of(CommandType.RECHECK, ctx.getLockName(), ctx.getSeqNodePath(), sessionId)
                   .serialize() + "\n");

        long remaining = deadline - System.currentTimeMillis();
        Message resp = future.get(Math.max(remaining, 1000), TimeUnit.MILLISECONDS);

        if (resp.getType() == CommandType.OK) {
            ctx.startWatchdog(channel);
            heldContexts.put(ctx.getLockName(), ctx);
            log.info("Lock acquired after recheck: {}", ctx.getLockName());
            return true;
        }

        if (resp.getType() == CommandType.WAIT) {
            return waitForLock(ctx, deadline);
        }
        return false;
    }

    /**
     * 释放锁，停止看门狗。
     */
    public void unlock(LockContext ctx) throws Exception {
        ctx.stopWatchdog();
        heldContexts.remove(ctx.getLockName());

        CompletableFuture<Message> future =
            handler.registerRequest("UNLOCK:" + ctx.getLockName());
        channel.writeAndFlush(
            Message.of(CommandType.UNLOCK, ctx.getSeqNodePath(), sessionId).serialize() + "\n");

        try {
            future.get(10, TimeUnit.SECONDS);
            ctx.markReleased();
            log.info("Lock released: {}", ctx.getLockName());
        } catch (TimeoutException e) {
            log.warn("Unlock timeout for [{}], session expiry will clean up", ctx.getLockName());
        }
    }

    // ==================== 重连 ====================

    private void handleRedirect(String leaderId) {
        log.info("Redirected to leader: {}, reconnecting...", leaderId);
        try {
            if (channel != null) channel.close().sync();
            List<String[]> shuffled = new ArrayList<>(nodes);
            Collections.shuffle(shuffled);
            connectToAny(shuffled);
            this.sessionId = establishSession(this.sessionId);
        } catch (Exception e) {
            log.error("Reconnect failed", e);
        }
    }

    // ==================== 关闭 ====================

    @Override
    public void close() {
        heldContexts.values().forEach(LockContext::stopWatchdog);
        watchdogScheduler.shutdownNow();
        if (channel != null) channel.close();
        group.shutdownGracefully();
    }

    // ==================== Builder ====================

    public static Builder builder() { return new Builder(); }

    /** HutuLockClient 构建器 */
    public static final class Builder {
        private ClientProperties config = ClientProperties.defaults();
        private final List<String[]> nodes = new ArrayList<>();

        public Builder config(ClientProperties config) { this.config = config; return this; }

        public Builder addNode(String host, int port) {
            nodes.add(new String[]{host, String.valueOf(port)});
            return this;
        }

        public HutuLockClient build() {
            if (nodes.isEmpty()) throw new IllegalStateException("at least one node required");
            return new HutuLockClient(this);
        }
    }
}
