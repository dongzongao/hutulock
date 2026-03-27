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
package com.hutulock.server.raft;

import com.hutulock.config.api.ServerProperties;
import com.hutulock.model.util.Numbers;
import com.hutulock.model.util.RaftMessageType;
import com.hutulock.model.util.Strings;
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
    private final RaftState       state;
    private final RaftElection    election;
    private final RaftReplication replication;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, Strings.THREAD_RAFT_SCHEDULER);
        t.setDaemon(true);
        return t;
    });

    private final EventLoopGroup raftGroup = new NioEventLoopGroup();

    /**
     * 构造 Raft 节点（内存模式，测试用）。
     */
    public RaftNode(String nodeId, int raftPort, RaftStateMachine stateMachine,
                    ServerProperties props, MetricsCollector metrics, EventBus eventBus) {
        this(nodeId, raftPort, stateMachine, props, metrics, eventBus, null);
    }

    /**
     * 构造 Raft 节点（WAL 持久化模式）。
     *
     * @param dataDir WAL 和元数据存储目录，null 表示内存模式
     */
    public RaftNode(String nodeId, int raftPort, RaftStateMachine stateMachine,
                    ServerProperties props, MetricsCollector metrics, EventBus eventBus,
                    String dataDir) {
        this.nodeId   = nodeId;
        this.raftPort = raftPort;
        this.props    = props;

        this.state = (dataDir != null) ? new RaftState(dataDir) : new RaftState();

        this.replication = new RaftReplication(nodeId, state, stateMachine, props, metrics, eventBus, scheduler);
        this.election    = new RaftElection(nodeId, state, props, metrics, eventBus, scheduler, replication);
        this.replication.setElection(election);

        if (dataDir != null) {
            log.info("Raft node [{}] using persistent storage at {}", nodeId, dataDir);
            log.info("Recovered state: term={}, votedFor={}, lastLogIndex={}",
                state.currentTerm, state.votedFor, state.raftLog.lastIndex());
        }
    }

    /** 添加集群节点并立即发起连接。 */
    public void addPeer(String peerId, String host, int port) {
        RaftPeer peer = new RaftPeer(peerId, host, port, this, raftGroup);
        state.peers.add(peer);
        peer.connect();
    }

    /**
     * 注入快照管理器（在 start() 之前调用）。
     * 同时将 ZNodeTree 引用传给 replication，用于定期触发快照。
     */
    public void setSnapshotManager(com.hutulock.server.persistence.SnapshotManager snapMgr,
                                    com.hutulock.server.impl.DefaultZNodeTree tree) {
        replication.setSnapshotManager(snapMgr);
        this.snapshotTree = tree;
        log.info("SnapshotManager registered for node [{}]", nodeId);
    }

    private com.hutulock.server.impl.DefaultZNodeTree snapshotTree;

    /**
     * 启动 Raft 节点：
     * 1. 若有快照，加载快照恢复状态机
     * 2. 重放快照点之后的 WAL 日志
     * 3. 绑定 Raft 端口
     * 4. 启动选举计时器和超时清理任务
     */
    public void start() throws InterruptedException {
        replayOnRestart();
        startRaftServer();
        election.resetElectionTimer();
        scheduler.scheduleAtFixedRate(replication::cleanupTimedOutProposes,
            Numbers.RAFT_CLEANUP_INTERVAL_SEC, Numbers.RAFT_CLEANUP_INTERVAL_SEC, TimeUnit.SECONDS);
        // 定期快照：每 30s 检查一次，日志超过 1000 条时触发
        scheduler.scheduleAtFixedRate(this::maybeSnapshot,
            Numbers.RAFT_SNAPSHOT_INTERVAL_SEC, Numbers.RAFT_SNAPSHOT_INTERVAL_SEC, TimeUnit.SECONDS);
        log.info("Raft node [{}] started on port {}, lastApplied={}", nodeId, raftPort, state.lastApplied);
    }

    /**
     * 重启恢复：加载快照 + 重放增量日志。
     *
     * <p>流程：
     * <pre>
     *   1. SnapshotManager.load() → 恢复 ZNode 树到快照点
     *   2. 从 snapshot.lastApplied+1 开始重放 WAL 日志
     *   3. 更新 state.commitIndex / lastApplied
     * </pre>
     *
     * <p>若无快照（首次启动或快照被删除），从日志索引 1 开始全量重放。
     */
    private void replayOnRestart() {
        if (!(replication.getStateMachine() instanceof com.hutulock.server.impl.DefaultZNodeTree)) {
            // 状态机不支持快照恢复（如测试用 mock），跳过
            return;
        }
        com.hutulock.server.impl.DefaultZNodeTree tree =
            (com.hutulock.server.impl.DefaultZNodeTree) replication.getStateMachine();

        // 1. 加载快照
        com.hutulock.server.persistence.SnapshotManager snapMgr = replication.getSnapshotManager();
        int replayFrom = 1;
        if (snapMgr != null) {
            com.hutulock.server.persistence.SnapshotManager.SnapshotMeta meta = snapMgr.load(tree);
            if (!meta.isEmpty()) {
                state.commitIndex = meta.lastApplied;
                state.lastApplied = meta.lastApplied;
                replayFrom = meta.lastApplied + 1;
                log.info("Snapshot loaded, replaying from logIndex={}", replayFrom);
            }
        }

        // 2. 重放 WAL 中快照点之后的日志
        int lastIdx = state.raftLog.lastIndex();
        if (replayFrom > lastIdx) {
            log.info("No incremental log to replay (lastIndex={})", lastIdx);
            return;
        }
        log.info("Replaying log entries [{}, {}]", replayFrom, lastIdx);
        for (int i = replayFrom; i <= lastIdx; i++) {
            RaftLog.Entry entry = state.raftLog.get(i);
            if (entry != null) {
                replication.applyToStateMachine(entry.index, entry.command);
            }
        }
        state.commitIndex = lastIdx;
        state.lastApplied = lastIdx;
        log.info("Replay complete, lastApplied={}", state.lastApplied);
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
        RaftMessageType msgType = RaftMessageType.of(msg);
        try {
            if (msgType == null) {
                log.warn("Unknown peer message type: {}", type);
                return;
            }
            switch (msgType) {
                case VOTE_REQ:    election.handleVoteReq(msg, channel);       break;
                case VOTE_RESP:   election.handleVoteResp(msg);               break;
                case APPEND_REQ:  replication.handleAppendReq(msg, channel);  break;
                case APPEND_RESP: replication.handleAppendResp(msg);          break;
            }
        } catch (Exception e) {
            log.error("Error handling peer message type={}: {}", RaftMessageType.of(msg), e.getMessage(), e);
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

    // ==================== 快照 ====================

    /**
     * 检查是否需要触发快照（日志条数超过阈值时）。
     * 只有 Leader 触发，避免多节点同时写快照。
     */
    private static final int SNAPSHOT_LOG_THRESHOLD = Numbers.RAFT_SNAPSHOT_LOG_THRESHOLD;

    private void maybeSnapshot() {
        if (state.role != Role.LEADER) return;
        com.hutulock.server.persistence.SnapshotManager snapMgr = replication.getSnapshotManager();
        if (snapMgr == null || snapshotTree == null) return;

        int logSize = state.raftLog.lastIndex();
        if (logSize < SNAPSHOT_LOG_THRESHOLD) return;

        try {
            log.info("Triggering snapshot at lastApplied={}, logSize={}", state.lastApplied, logSize);
            snapMgr.save(snapshotTree, state.lastApplied, state.currentTerm);
        } catch (Exception e) {
            log.error("Snapshot failed: {}", e.getMessage(), e);
        }
    }

    // ==================== 生命周期 ====================

    @Override
    public void shutdown() {
        election.cancelHeartbeat();
        scheduler.shutdownNow();
        raftGroup.shutdownGracefully();
        log.info("RaftNode [{}] shutdown", nodeId);
    }
}
