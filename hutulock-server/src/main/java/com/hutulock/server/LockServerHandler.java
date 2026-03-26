/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server;

import com.hutulock.model.exception.HutuLockException;
import com.hutulock.model.protocol.CommandType;
import com.hutulock.model.protocol.Message;
import com.hutulock.model.session.Session;
import com.hutulock.spi.session.SessionTracker;
import com.hutulock.config.api.ServerProperties;
import com.hutulock.server.impl.DefaultLockManager;
import com.hutulock.server.raft.RaftNode;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * 客户端请求处理器（Netty ChannelHandler）
 *
 * <p>职责：
 * <ol>
 *   <li>解析文本协议为 {@link Message}</li>
 *   <li>路由到对应处理逻辑（CONNECT / LOCK / UNLOCK / RECHECK / RENEW）</li>
 *   <li>写操作（LOCK / UNLOCK）通过 Raft propose 保证一致性</li>
 *   <li>读操作（RECHECK / RENEW）直接本地处理，无需共识</li>
 *   <li>非 Leader 节点返回 REDIRECT，客户端自动重连</li>
 * </ol>
 *
 * <p>标注 {@link ChannelHandler.Sharable}，所有 Channel 共享同一实例。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
@ChannelHandler.Sharable
public class LockServerHandler extends SimpleChannelInboundHandler<String> {

    private static final Logger log = LoggerFactory.getLogger(LockServerHandler.class);

    /** Channel 属性 Key，存储当前连接的 sessionId */
    static final AttributeKey<String> SESSION_ID_KEY = AttributeKey.valueOf("hutulock.sessionId");

    private final DefaultLockManager lockManager;
    private final SessionTracker     sessionTracker;
    private final RaftNode           raftNode;
    private final int                proposeRetry;
    private final long               proposeRetryDelayMs;

    public LockServerHandler(DefaultLockManager lockManager,
                              SessionTracker sessionTracker,
                              RaftNode raftNode,
                              ServerProperties props) {
        this.lockManager         = lockManager;
        this.sessionTracker      = sessionTracker;
        this.raftNode            = raftNode;
        this.proposeRetry        = props.proposeRetryCount;
        this.proposeRetryDelayMs = props.proposeRetryDelayMs;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String raw) {
        Message msg;
        try {
            msg = Message.parse(raw);
        } catch (HutuLockException e) {
            ctx.writeAndFlush(Message.of(CommandType.ERROR, e.getMessage()).serialize() + "\n");
            return;
        }

        switch (msg.getType()) {
            case CONNECT: handleConnect(ctx, msg); break;
            case LOCK:    handleWrite(ctx, msg);   break;
            case UNLOCK:  handleWrite(ctx, msg);   break;
            case RECHECK: handleRecheck(ctx, msg); break;
            case RENEW:   handleRenew(ctx, msg);   break;
            default:
                ctx.writeAndFlush(
                    Message.of(CommandType.ERROR, "unsupported: " + msg.getType()).serialize() + "\n");
        }
    }

    // ==================== 会话建立 ====================

    private void handleConnect(ChannelHandlerContext ctx, Message msg) {
        String existingSessionId = msg.argCount() > 0 ? msg.arg(0) : null;

        if (existingSessionId != null && !existingSessionId.isEmpty()) {
            // 尝试恢复会话
            boolean reconnected = sessionTracker.reconnect(existingSessionId, ctx.channel());
            if (reconnected) {
                ctx.channel().attr(SESSION_ID_KEY).set(existingSessionId);
                ctx.writeAndFlush(
                    Message.of(CommandType.CONNECTED, existingSessionId).serialize() + "\n");
                log.info("Session reconnected: {}", existingSessionId);
                return;
            }
            log.warn("Session {} expired, creating new session", existingSessionId);
        }

        // 创建新会话
        Session session = sessionTracker.createSession(
            ctx.channel().remoteAddress().toString(), ctx.channel());
        ctx.channel().attr(SESSION_ID_KEY).set(session.getSessionId());
        ctx.writeAndFlush(
            Message.of(CommandType.CONNECTED, session.getSessionId()).serialize() + "\n");
        log.info("New session created: {}", session.getSessionId());
    }

    // ==================== 写操作（走 Raft） ====================

    private void handleWrite(ChannelHandlerContext ctx, Message msg) {
        if (!raftNode.isLeader()) {
            String leader = raftNode.getLeaderId();
            ctx.writeAndFlush(
                Message.of(CommandType.REDIRECT, leader != null ? leader : "UNKNOWN").serialize() + "\n");
            log.debug("Redirecting to leader: {}", leader);
            return;
        }
        proposeWithRetry(ctx, msg.serialize(), proposeRetry);
    }

    // ==================== 读操作（本地处理） ====================

    private void handleRecheck(ChannelHandlerContext ctx, Message msg) {
        // BUG-FIX 3: RECHECK 是客户端收到 WATCH_EVENT 后重新检查锁顺序的操作。
        // 它只读取 ZNode 树（getChildren），不修改状态，因此不需要走 Raft。
        // 但必须保证与 apply() 的互斥：recheckLock 已加 synchronized，安全。
        // 注意：Follower 节点的 ZNode 树可能落后于 Leader，
        // 客户端应只连接 Leader（通过 REDIRECT 保证），所以这里是安全的。
        lockManager.recheckLock(msg.arg(0), msg.arg(1), msg.arg(2));
    }

    private void handleRenew(ChannelHandlerContext ctx, Message msg) {
        // RENEW lockName sessionId
        lockManager.renew(msg.arg(0), msg.arg(1));
        ctx.writeAndFlush(Message.of(CommandType.RENEWED, msg.arg(0)).serialize() + "\n");
    }

    // ==================== Raft propose with retry ====================

    private void proposeWithRetry(ChannelHandlerContext ctx, String command, int remaining) {
        raftNode.propose(command).whenComplete((v, ex) -> {
            if (ex == null) return; // 成功，状态机已通知客户端

            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            String    errMsg = cause.getMessage();

            if (errMsg != null && errMsg.contains("LEADER_CHANGED") && remaining > 0) {
                log.warn("Propose failed (leader changed), retrying ({} left)", remaining);
                raftNode.getScheduler().schedule(
                    () -> proposeWithRetry(ctx, command, remaining - 1),
                    proposeRetryDelayMs, TimeUnit.MILLISECONDS
                );
            } else {
                log.error("Propose failed permanently: {}", errMsg);
                ctx.writeAndFlush(Message.of(CommandType.ERROR, errMsg).serialize() + "\n");
            }
        });
    }

    // ==================== 连接断开 ====================

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        String sessionId = ctx.channel().attr(SESSION_ID_KEY).get();
        if (sessionId != null) {
            // 会话进入 RECONNECTING 状态，等待超时后自动清理临时节点
            sessionTracker.onChannelDisconnected(ctx.channel());
            log.info("Channel disconnected, session {} in RECONNECTING state", sessionId);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Channel error: {}", cause.getMessage(), cause);
        ctx.close();
    }
}
