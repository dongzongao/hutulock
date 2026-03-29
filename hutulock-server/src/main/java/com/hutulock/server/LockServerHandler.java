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
package com.hutulock.server;

import com.hutulock.model.exception.HutuLockException;
import com.hutulock.model.protocol.CommandType;
import com.hutulock.model.protocol.Message;
import com.hutulock.model.session.Session;
import com.hutulock.model.znode.ZNode;
import com.hutulock.model.znode.ZNodePath;
import com.hutulock.model.znode.ZNodeType;
import com.hutulock.spi.session.SessionTracker;
import com.hutulock.config.api.ServerProperties;
import com.hutulock.model.util.Strings;
import com.hutulock.server.handler.CommandRegistry;
import com.hutulock.server.impl.DefaultLockManager;
import com.hutulock.server.impl.DefaultZNodeTree;
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
    static final AttributeKey<String> SESSION_ID_KEY = AttributeKey.valueOf(Strings.ATTR_SESSION_ID);

    private final DefaultLockManager lockManager;
    private final SessionTracker     sessionTracker;
    private final RaftNode           raftNode;
    private final DefaultZNodeTree   zNodeTree;
    private final int                proposeRetry;
    private final long               proposeRetryDelayMs;
    /** 命令路由表，替代 switch(msg.getType()) */
    private final CommandRegistry    commandRegistry;

    public LockServerHandler(DefaultLockManager lockManager,
                              SessionTracker sessionTracker,
                              RaftNode raftNode,
                              ServerProperties props) {
        this(lockManager, sessionTracker, raftNode, null, props);
    }

    public LockServerHandler(DefaultLockManager lockManager,
                              SessionTracker sessionTracker,
                              RaftNode raftNode,
                              DefaultZNodeTree zNodeTree,
                              ServerProperties props) {
        this.lockManager         = lockManager;
        this.sessionTracker      = sessionTracker;
        this.raftNode            = raftNode;
        this.zNodeTree           = zNodeTree;
        this.proposeRetry        = props.proposeRetryCount;
        this.proposeRetryDelayMs = props.proposeRetryDelayMs;

        // 命令模式：注册每个 CommandType 对应的处理器，替代 switch
        this.commandRegistry = new CommandRegistry(
                (ctx, msg) -> ctx.writeAndFlush(
                    Message.of(CommandType.ERROR, "unsupported: " + msg.getType()).serialize() + "\n"))
            .register(CommandType.CONNECT,  this::handleConnect)
            .register(CommandType.LOCK,     this::handleWrite)
            .register(CommandType.UNLOCK,   this::handleWrite)
            .register(CommandType.RECHECK,  this::handleRecheck)
            .register(CommandType.RENEW,    this::handleRenew)
            .register(CommandType.GET_DATA, this::handleGetData)
            .register(CommandType.SET_DATA, this::handleSetData);
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

        // 命令模式：O(1) 路由，替代 switch(msg.getType())
        commandRegistry.dispatch(ctx, msg);
    }

    // ==================== 会话建立 ====================

    private void handleConnect(ChannelHandlerContext ctx, Message msg) {
        // CONNECT [sessionId] — optArg(0) 处理可选的 sessionId，无需 argCount 检查
        String existingSessionId = msg.optArg(0).filter(s -> !s.isEmpty()).orElse(null);

        if (existingSessionId != null) {
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
                    Message.of(CommandType.REDIRECT, leader != null ? leader : Strings.UNKNOWN_LEADER).serialize() + Strings.MSG_LINE_END);
            log.debug("Redirecting to leader: {}", leader);
            return;
        }
        proposeWithRetry(ctx, msg.serialize(), proposeRetry);
    }

    // ==================== 读操作（本地处理） ====================

    private void handleRecheck(ChannelHandlerContext ctx, Message msg) {
        // 验证 sessionId 归属：消息中的 sessionId 必须与当前连接一致
        String connectedSessionId = ctx.channel().attr(SESSION_ID_KEY).get();
        String requestedSessionId = msg.arg(2);
        if (connectedSessionId == null || !connectedSessionId.equals(requestedSessionId)) {
            log.warn("RECHECK sessionId mismatch: connected={}, requested={}, addr={}",
                connectedSessionId, requestedSessionId, ctx.channel().remoteAddress());
            ctx.writeAndFlush(Message.of(CommandType.ERROR, "session mismatch").serialize() + "\n");
            return;
        }
        lockManager.recheckLock(msg.arg(0), msg.arg(1), msg.arg(2));
    }

    private void handleRenew(ChannelHandlerContext ctx, Message msg) {
        // 验证 sessionId 归属
        String connectedSessionId = ctx.channel().attr(SESSION_ID_KEY).get();
        String requestedSessionId = msg.arg(1);
        if (connectedSessionId == null || !connectedSessionId.equals(requestedSessionId)) {
            log.warn("RENEW sessionId mismatch: connected={}, requested={}, addr={}",
                connectedSessionId, requestedSessionId, ctx.channel().remoteAddress());
            ctx.writeAndFlush(Message.of(CommandType.ERROR, "session mismatch").serialize() + "\n");
            return;
        }
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

    // ==================== Optimistic locking (GET_DATA / SET_DATA) ====================

    /**
     * GET_DATA <path> <sessionId>
     * Response: DATA <path> <version> <base64-data>
     */
    private void handleGetData(ChannelHandlerContext ctx, Message msg) {
        String path = msg.arg(0);
        try {
            ZNodePath zpath = ZNodePath.of(path);
            ZNode node = zNodeTree != null ? zNodeTree.get(zpath) : null;
            if (node == null) {
                ctx.writeAndFlush(Message.of(CommandType.ERROR, "node not found: " + path).serialize() + "\n");
                return;
            }
            byte[] data = node.getData() != null ? node.getData() : new byte[0];
            String encoded = java.util.Base64.getEncoder().encodeToString(data);
            ctx.writeAndFlush(
                Message.of(CommandType.DATA, path, String.valueOf(node.getVersion()), encoded)
                       .serialize() + "\n");
        } catch (Exception e) {
            ctx.writeAndFlush(Message.of(CommandType.ERROR, e.getMessage()).serialize() + "\n");
        }
    }

    /**
     * SET_DATA <path> <expectedVersion> <base64-data> <sessionId>
     * Response: OK (success) or VERSION_MISMATCH (conflict, caller should retry)
     */
    private void handleSetData(ChannelHandlerContext ctx, Message msg) {
        if (!raftNode.isLeader()) {
            String leader = raftNode.getLeaderId();
            ctx.writeAndFlush(
                Message.of(CommandType.REDIRECT, leader != null ? leader : Strings.UNKNOWN_LEADER)
                       .serialize() + "\n");
            return;
        }
        String path            = msg.arg(0);
        int    expectedVersion;
        byte[] data;
        try {
            expectedVersion = Integer.parseInt(msg.arg(1));
            data            = java.util.Base64.getDecoder().decode(msg.arg(2));
        } catch (IllegalArgumentException e) {
            ctx.writeAndFlush(Message.of(CommandType.ERROR, "invalid SET_DATA args: " + e.getMessage()).serialize() + "\n");
            return;
        }

        try {
            ZNodePath zpath = ZNodePath.of(path);
            if (zNodeTree == null) {
                ctx.writeAndFlush(Message.of(CommandType.ERROR, "storage unavailable").serialize() + "\n");
                return;
            }
            // setData with version check — throws VERSION_MISMATCH if stale
            zNodeTree.setData(zpath, data, expectedVersion);
            ctx.writeAndFlush(Message.of(CommandType.OK, path).serialize() + "\n");
        } catch (com.hutulock.model.exception.HutuLockException e) {
            if (e.getCode() == com.hutulock.model.exception.ErrorCode.VERSION_MISMATCH) {
                ctx.writeAndFlush(Message.of(CommandType.VERSION_MISMATCH, path).serialize() + "\n");
            } else {
                ctx.writeAndFlush(Message.of(CommandType.ERROR, e.getMessage()).serialize() + "\n");
            }
        }
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
