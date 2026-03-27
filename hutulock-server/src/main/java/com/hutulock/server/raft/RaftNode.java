/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server.raft;

import com.hutulock.config.api.ServerProperties;
import com.hutulock.server.api.RaftStateMachine;
import com.hutulock.server.ioc.Lifecycle;
import com.hutulock.spi.event.EventBus;
import com.hutulock.spi.metrics.MetricsCollector;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

/**
 * Raft 核心节点（协调器）
 *
 * <p>职责：
 * <ul>
 *   <li>持有 {@link RaftState}（单一数据源）</li>
 *   <li>启动 Netty Raft 服务端，接收 peer 消息并路由</li>
 *   <li>管理调度器生命周期</li>
 *   <li>对外暴露 {@link #propose}、{@link #addPeer} 等公共 API</li>
 * </ul>
 *
 * <p>选举逻辑委托给 {@link RaftElection}，
 * 日志复制逻辑委托给 {@link RaftReplication}。
 *
 * <p>节点角色状态机：
 * <pre>
 *   FOLLOWER ──(超时)──→ CANDIDATE ──(多数票)──→ LEADER
 *      ↑                     │                      │
 *      └─────────────────────┘◄─────────────────────┘
 *              (收到更高 term)
 * </pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class RaftNode implements Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(RaftNode.class);

    /** 节点角色 */
    public enum Role { FOLLOWER, CANDIDATE, LEADER }

    private final String           nodeId;
    private final int              raftPort;
    private final ServerProperties props;

    /** 共享状态（选举 + 复制模块共用） */
    private final RaftState       state       = new RaftState();
    private final RaftElection    election;
    private final RaftReplication replication;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "hutulock-raft-scheduler");
        t.setDaemon(true);
        return t;
    });

    private final EventLoopGroup raftGroup = new NioEventLoopGroup();

    /**
     * 构造 Raft 节点。
     *
     * @param nodeId       节点 ID（集群内唯一）
     * @param raftPort     Raft 节点间通信端口
     * @param stateMachine 状态机实现
     * @param props        服务端配置
     * @param metrics      Metrics 收集器
     * @param eventBus     事件总线
     */
    public RaftNode(String nodeId, int raftPort, RaftStateMachine stateMachine,
                    ServerProperties props, MetricsCollector metrics, EventBus eventBus) {
        this.nodeId   = nodeId;
        this.raftPort = raftPort;
        this.props    = props;

        // 先构造 replication，再构造 election（election 需要 replication 引用）
        this.replication = new RaftReplication(nodeId, state, stateMachine, props, metrics, eventBus);
        this.election    = new RaftElection(nodeId, state, props, metrics, eventBus, scheduler, replication);
        // 反向注入，解除循环依赖
        this.replication.setElection(election);
    }

    /** 添加集群节点并立即发起连接。 */
    public void addPeer(String peerId, String host, int port) {
        RaftPeer peer = new RaftPeer(peerId, host, port, this, raftGroup);
        state.peers.add(peer);
        peer.connect();
    }

    /**
     * 启动 Raft 节点：绑定端口、启动选举计时器、启动超时清理任务。
     *
     * @throws InterruptedException 线程中断
     */
    public void start() throws InterruptedException {
        startRaftServer();
        election.resetElectionTimer();
        scheduler.scheduleAtFixedRate(replication::cleanupTimedOutProposes, 1, 1, TimeUnit.SECONDS);
        log.info("Raft node [{}] started on port {}", nodeId, raftPort);
    }

    // ==================== 公共 API ====================

    /**
     * Leader 接收客户端命令，追加日志并等待多数派确认。
     *
     * @param command 命令字符串
     * @return CompletableFuture，commit 后完成；非 Leader 时立即失败
     */
    public CompletableFuture<Void> propose(String command) {
        return replication.propose(command);
    }

    // ==================== 消息路由 ====================

    /**
     * 路由来自 peer 的消息到对应处理器。
     * 由 {@link RaftPeerHandler} 调用。
     */
    public void handlePeerMessage(String msg, io.netty.channel.Channel channel) {
        if (msg == null || msg.isEmpty()) {
            log.warn("Received empty peer message, ignored");
            return;
        }
        int spaceIdx = msg.indexOf(' ');
        String type = spaceIdx > 0 ? msg.substring(0, spaceIdx) : msg;
        try {
            switch (type) {
                case "VOTE_REQ":    election.handleVoteReq(msg, channel);       break;
                case "VOTE_RESP":   election.handleVoteResp(msg);               break;
                case "APPEND_REQ":  replication.handleAppendReq(msg, channel);  break;
                case "APPEND_RESP": replication.handleAppendResp(msg);          break;
                default: log.warn("Unknown peer message type: {}", type);
            }
        } catch (Exception e) {
            log.error("Error handling peer message type={}: {}", type, e.getMessage(), e);
        }
    }

    // ==================== Netty 服务端 ====================

    private void startRaftServer() throws InterruptedException {
        try {
            new ServerBootstrap()
                .group(new NioEventLoopGroup(1), new NioEventLoopGroup())
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                          .addLast(new LineBasedFrameDecoder(props.maxFrameLength))
                          .addLast(new StringDecoder(CharsetUtil.UTF_8))
                          .addLast(new StringEncoder(CharsetUtil.UTF_8))
                          .addLast(new RaftPeerHandler(RaftNode.this));
                    }
                })
                .bind(raftPort).sync();
        } catch (Exception e) {
            log.error("Raft node [{}] failed to bind port {}: {}", nodeId, raftPort, e.getMessage());
            throw e;
        }
    }

    // ==================== 状态查询 ====================

    public String  getNodeId()   { return nodeId;                      }
    public Role    getRole()     { return state.role;                   }
    public String  getLeaderId() { return state.leaderId;               }
    public boolean isLeader()    { return state.role == Role.LEADER;    }
    public ScheduledExecutorService getScheduler() { return scheduler;  }

    // ==================== 生命周期 ====================

    @Override
    public void shutdown() {
        election.cancelHeartbeat();
        scheduler.shutdownNow();
        raftGroup.shutdownGracefully();
        log.info("RaftNode [{}] shutdown", nodeId);
    }
}
