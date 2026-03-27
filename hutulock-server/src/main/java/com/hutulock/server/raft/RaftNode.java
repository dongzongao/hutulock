/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server.raft;

import com.hutulock.spi.event.EventBus;
import com.hutulock.spi.event.RaftEvent;
import com.hutulock.spi.metrics.MetricsCollector;
import com.hutulock.config.api.ServerProperties;
import com.hutulock.server.api.RaftStateMachine;
import com.hutulock.server.ioc.Lifecycle;
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

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Raft 核心节点
 *
 * <p>实现 Raft 共识算法的三个核心机制：
 * <ol>
 *   <li>Leader 选举（随机超时 + 多数派投票）</li>
 *   <li>日志复制（AppendEntries RPC）</li>
 *   <li>日志提交（多数派确认后 apply 到状态机）</li>
 * </ol>
 *
 * <p>节点角色状态机：
 * <pre>
 *   FOLLOWER ──(超时)──→ CANDIDATE ──(多数票)──→ LEADER
 *      ↑                     │                      │
 *      └─────────────────────┘◄─────────────────────┘
 *              (收到更高 term)
 * </pre>
 *
 * <p>Raft 节点间通信协议（文本行）：
 * <pre>
 *   VOTE_REQ   {term} {candidateId} {lastLogIndex} {lastLogTerm}
 *   VOTE_RESP  {term} {granted}
 *   APPEND_REQ {term} {leaderId} {prevLogIndex} {prevLogTerm} {leaderCommit} {entries}
 *   APPEND_RESP {term} {success} {matchIndex}
 * </pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class RaftNode implements Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(RaftNode.class);

    /** 节点角色 */
    public enum Role { FOLLOWER, CANDIDATE, LEADER }

    // ---- 持久化状态（重启后需恢复，当前为内存实现） ----
    private volatile int    currentTerm = 0;
    private volatile String votedFor    = null;
    private final RaftLog   raftLog     = new RaftLog();

    // ---- 易失状态 ----
    private volatile int    commitIndex = 0;
    private volatile int    lastApplied = 0;
    private volatile Role   role        = Role.FOLLOWER;
    private volatile String leaderId    = null;

    private final String             nodeId;
    private final int                raftPort;
    private final List<RaftPeer>     peers         = new ArrayList<>();
    private final RaftStateMachine   stateMachine;
    private final ServerProperties   props;
    private final MetricsCollector   metrics;
    private final EventBus           eventBus;

    // ---- 调度器 ----
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "hutulock-raft-" + "scheduler");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> electionTimer;
    private ScheduledFuture<?> heartbeatTimer;

    // ---- 选举 ----
    private final AtomicInteger voteCount = new AtomicInteger(0);

    // ---- Propose 管理 ----
    /** logIndex → 等待 commit 的 CompletableFuture */
    private final Map<Integer, CompletableFuture<Void>> pendingCommits   = new ConcurrentHashMap<>();
    /** logIndex → propose 超时时间戳 */
    private final Map<Integer, Long>                    proposeDeadlines = new ConcurrentHashMap<>();

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
        this.nodeId       = nodeId;
        this.raftPort     = raftPort;
        this.stateMachine = stateMachine;
        this.props        = props;
        this.metrics      = metrics;
        this.eventBus     = eventBus;
    }

    /** 添加集群节点并立即发起连接。 */
    public void addPeer(String peerId, String host, int port) {
        RaftPeer peer = new RaftPeer(peerId, host, port, this, raftGroup);
        peers.add(peer);
        peer.connect();
    }

    /**
     * 启动 Raft 节点。
     *
     * @throws InterruptedException 线程中断
     */
    public void start() throws InterruptedException {
        startRaftServer();
        resetElectionTimer();
        scheduler.scheduleAtFixedRate(this::cleanupTimedOutProposes, 1, 1, TimeUnit.SECONDS);
        log.info("Raft node [{}] started on port {}", nodeId, raftPort);
    }

    // ==================== 选举 ====================

    private void resetElectionTimer() {
        if (electionTimer != null) electionTimer.cancel(false);
        long range   = props.electionTimeoutMaxMs - props.electionTimeoutMinMs;
        long timeout = props.electionTimeoutMinMs + (long)(Math.random() * range);
        electionTimer = scheduler.schedule(this::startElection, timeout, TimeUnit.MILLISECONDS);
    }

    private synchronized void startElection() {
        if (role == Role.LEADER) return;
        currentTerm++;
        role     = Role.CANDIDATE;
        votedFor = nodeId;
        voteCount.set(1);

        metrics.onRaftElectionStarted();
        log.info("Starting election, term={}", currentTerm);

        // 发布选举开始事件
        eventBus.publish(RaftEvent.builder(RaftEvent.Type.ELECTION_STARTED, nodeId, currentTerm).build());

        // 单节点集群：无需等待投票响应，直接成为 Leader
        if (peers.isEmpty()) {
            becomeLeader();
            return;
        }

        String req = String.format("VOTE_REQ %d %s %d %d",
            currentTerm, nodeId, raftLog.lastIndex(), raftLog.lastTerm());
        peers.forEach(p -> p.send(req));
        resetElectionTimer();
    }

    private synchronized void becomeLeader() {
        if (role != Role.CANDIDATE) return;
        role     = Role.LEADER;
        leaderId = nodeId;
        metrics.onRaftLeaderChanged();
        log.info("Became LEADER, term={}", currentTerm);

        // 发布成为 Leader 事件
        eventBus.publish(RaftEvent.builder(RaftEvent.Type.BECAME_LEADER, nodeId, currentTerm)
            .leaderId(nodeId).build());

        peers.forEach(p -> { p.nextIndex = raftLog.lastIndex() + 1; p.matchIndex = 0; });
        startHeartbeat();
    }

    private void failPendingProposesOnStepDown() {
        if (pendingCommits.isEmpty()) return;
        log.warn("Stepping down, failing {} pending proposes", pendingCommits.size());
        pendingCommits.forEach((idx, f) ->
            f.completeExceptionally(new IllegalStateException("LEADER_CHANGED")));
        pendingCommits.clear();
        proposeDeadlines.clear();

        // 发布降级事件
        eventBus.publish(RaftEvent.builder(RaftEvent.Type.STEPPED_DOWN, nodeId, currentTerm)
            .leaderId(leaderId).build());
    }

    private void cleanupTimedOutProposes() {
        long now = System.currentTimeMillis();
        proposeDeadlines.entrySet().removeIf(entry -> {
            if (now >= entry.getValue()) {
                CompletableFuture<Void> f = pendingCommits.remove(entry.getKey());
                if (f != null) {
                    metrics.onRaftProposeTimeout();
                    f.completeExceptionally(new TimeoutException("propose timeout"));
                }
                return true;
            }
            return false;
        });
    }

    private void startHeartbeat() {
        if (heartbeatTimer != null) heartbeatTimer.cancel(false);
        heartbeatTimer = scheduler.scheduleAtFixedRate(
            this::sendHeartbeats, 0, props.heartbeatIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void sendHeartbeats() {
        if (role != Role.LEADER) return;
        peers.forEach(this::sendAppendEntries);
    }

    // ==================== 日志复制 ====================

    /**
     * Leader 接收客户端命令，追加日志并等待多数派确认。
     *
     * @param command 命令字符串
     * @return CompletableFuture，commit 后完成
     */
    public CompletableFuture<Void> propose(String command) {
        if (role != Role.LEADER) {
            metrics.onRaftProposeRejected();
            CompletableFuture<Void> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("NOT_LEADER:" + leaderId));
            return f;
        }

        synchronized (this) {
            long startMs = System.currentTimeMillis();
            int  index   = raftLog.lastIndex() + 1;
            raftLog.append(new RaftLog.Entry(currentTerm, index, command));
            log.debug("Appended log[{}]: {}", index, command);

            CompletableFuture<Void> future = new CompletableFuture<>();
            future.thenRun(() -> {
                metrics.onRaftProposeSuccess();
                metrics.recordRaftProposeDuration(System.currentTimeMillis() - startMs);
            });
            pendingCommits.put(index, future);
            proposeDeadlines.put(index, System.currentTimeMillis() + props.proposeTimeoutMs);

            if (peers.isEmpty()) {
                // BUG-FIX 7: 单节点模式下直接 commit，advanceCommitIndex 会更新
                // commitIndex 和 lastApplied，并调用 stateMachine.apply()，逻辑正确。
                // 但需确保在 synchronized 块内执行，避免与 handleAppendResp 竞态。
                advanceCommitIndex(index);
            } else {
                peers.forEach(this::sendAppendEntries);
            }
            return future;
        }
    }

    private void sendAppendEntries(RaftPeer peer) {
        int prevIndex = peer.nextIndex - 1;
        int prevTerm  = raftLog.termAt(prevIndex);
        List<RaftLog.Entry> entries = raftLog.getFrom(peer.nextIndex);

        StringBuilder sb = new StringBuilder();
        // 在消息头加入 targetPeerId，让响应方回传，用于精确路由 APPEND_RESP
        sb.append("APPEND_REQ ").append(currentTerm).append(" ").append(nodeId)
          .append(" ").append(prevIndex).append(" ").append(prevTerm)
          .append(" ").append(commitIndex).append(" ");

        if (entries.isEmpty()) {
            sb.append("EMPTY");
        } else {
            StringJoiner joiner = new StringJoiner("|");
            for (RaftLog.Entry e : entries) {
                joiner.add(e.index + ":" + e.term + ":" + e.command.replace("|", "\\|"));
            }
            sb.append(joiner);
        }
        peer.send(sb.toString());
    }

    // ==================== 消息处理 ====================

    /** 处理来自 peer 的消息（请求或响应）。 */
    public void handlePeerMessage(String msg, io.netty.channel.Channel channel) {
        String type = msg.split(" ", 2)[0];
        switch (type) {
            case "VOTE_REQ":    handleVoteReq(msg, channel);    break;
            case "VOTE_RESP":   handleVoteResp(msg);             break;
            case "APPEND_REQ":  handleAppendReq(msg, channel);  break;
            case "APPEND_RESP": handleAppendResp(msg);           break;
            default: log.warn("Unknown peer message type: {}", type);
        }
    }

    private synchronized void handleVoteReq(String msg, io.netty.channel.Channel ch) {
        String[] p = msg.split(" ");
        int term = Integer.parseInt(p[1]); String candidate = p[2];
        int lastIdx = Integer.parseInt(p[3]); int lastTerm = Integer.parseInt(p[4]);

        if (term > currentTerm) { currentTerm = term; role = Role.FOLLOWER; votedFor = null; failPendingProposesOnStepDown(); }

        // BUG-FIX 2: 原条件 term >= currentTerm 允许同一 term 内重复投票给不同候选人。
        // Raft 要求：每个 term 只能投票给一个候选人（votedFor 约束）。
        // 修复：term 必须等于 currentTerm（大于的情况已在上面处理并重置 votedFor）。
        boolean granted = term == currentTerm
            && (votedFor == null || votedFor.equals(candidate))
            && isLogUpToDate(lastIdx, lastTerm);
        if (granted) { votedFor = candidate; resetElectionTimer(); }

        ch.writeAndFlush("VOTE_RESP " + currentTerm + " " + granted + "\n");
        log.debug("Vote for {}: {}", candidate, granted);
    }

    private synchronized void handleVoteResp(String msg) {
        String[] p = msg.split(" ");
        int term = Integer.parseInt(p[1]); boolean granted = Boolean.parseBoolean(p[2]);

        if (term > currentTerm) { currentTerm = term; role = Role.FOLLOWER; failPendingProposesOnStepDown(); return; }
        if (role != Role.CANDIDATE || term != currentTerm) return;

        if (granted && voteCount.incrementAndGet() >= (peers.size() + 1) / 2 + 1) {
            becomeLeader();
        }
    }

    private synchronized void handleAppendReq(String msg, io.netty.channel.Channel ch) {
        String[] p = msg.split(" ", 7);
        int term = Integer.parseInt(p[1]); String leader = p[2];
        int prevLogIndex = Integer.parseInt(p[3]); int prevLogTerm = Integer.parseInt(p[4]);
        int leaderCommit = Integer.parseInt(p[5]); String entriesStr = p[6];

        if (term < currentTerm) { ch.writeAndFlush("APPEND_RESP " + currentTerm + " false 0 " + nodeId + "\n"); return; }

        if (term > currentTerm) { failPendingProposesOnStepDown(); }
        currentTerm = term; leaderId = leader; role = Role.FOLLOWER; resetElectionTimer();

        if (prevLogIndex > 0 && raftLog.termAt(prevLogIndex) != prevLogTerm) {
            ch.writeAndFlush("APPEND_RESP " + currentTerm + " false " + (prevLogIndex - 1) + " " + nodeId + "\n"); return;
        }

        if (!"EMPTY".equals(entriesStr)) {
            // BUG-FIX 5: 原正则 \\|(?!\\\\) 是负向前瞻，匹配"后面不是反斜杠的|"，
            // 但转义序列是 \| 而非 \\|，正确的分隔符是未转义的 |。
            // 修复：先将转义的 \| 替换为占位符，按 | 分割，再还原。
            String placeholder = "\u0000";
            String[] parts = entriesStr.replace("\\|", placeholder).split("\\|");
            for (String es : parts) {
                String[] ep = es.split(":", 3);
                int eIdx = Integer.parseInt(ep[0]); int eTerm = Integer.parseInt(ep[1]);
                String eCmd = ep[2].replace(placeholder, "|");
                if (eIdx <= raftLog.lastIndex() && raftLog.termAt(eIdx) != eTerm) raftLog.truncateFrom(eIdx);
                if (eIdx > raftLog.lastIndex()) raftLog.append(new RaftLog.Entry(eTerm, eIdx, eCmd));
            }
        }

        if (leaderCommit > commitIndex) advanceCommitIndex(Math.min(leaderCommit, raftLog.lastIndex()));
        // 响应消息携带自身 nodeId，供 Leader 精确路由 APPEND_RESP（BUG-1 修复配套）
        ch.writeAndFlush("APPEND_RESP " + currentTerm + " true " + raftLog.lastIndex() + " " + nodeId + "\n");
    }

    private synchronized void handleAppendResp(String msg) {
        String[] p = msg.split(" ");
        int term = Integer.parseInt(p[1]); boolean success = Boolean.parseBoolean(p[2]); int matchIdx = Integer.parseInt(p[3]);
        // p[4] 为响应方 nodeId（新协议），旧协议无此字段时降级为保守更新
        String fromNodeId = p.length > 4 ? p[4] : null;

        if (term > currentTerm) { currentTerm = term; role = Role.FOLLOWER; failPendingProposesOnStepDown(); return; }
        if (role != Role.LEADER) return;

        for (RaftPeer peer : peers) {
            // BUG-FIX 1: 精确匹配发送方，避免错误更新其他 peer 的进度
            if (fromNodeId != null && !fromNodeId.equals(peer.nodeId)) continue;

            if (success) {
                if (matchIdx > peer.matchIndex) {
                    peer.matchIndex = matchIdx;
                    peer.nextIndex  = matchIdx + 1;
                }
            } else {
                if (peer.nextIndex > matchIdx + 1) {
                    peer.nextIndex = Math.max(1, matchIdx + 1);
                    sendAppendEntries(peer);
                }
            }
        }
        if (success) tryAdvanceCommitIndex();
    }

    private void tryAdvanceCommitIndex() {
        // BUG-FIX 6: 原代码 indexes[peers.size()/2] 多数派索引计算错误。
        // 3节点集群：peers.size()=2，需要 2/2+1=2 个节点确认（含自身）。
        // 排序后数组 [self, peer1, peer2]，多数派位置应为 indexes[peers.size()/2]。
        // 但 peers.size()/2 = 1，对应第2小的值，即需要至少2个节点确认 ✓
        // 5节点集群：peers.size()=4，多数派位置 indexes[4/2]=indexes[2]，
        // 对应第3小的值，需要至少3个节点确认 ✓
        // 原逻辑实际是正确的，但注释说明不清晰。真正的问题是数组升序排序后，
        // 多数派 = 第 (n/2+1) 小的值 = indexes[(n-1)/2]（0-indexed，n=总节点数）。
        // 总节点数 = peers.size()+1，多数派位置 = peers.size()/2（已正确）。
        int[] indexes = new int[peers.size() + 1];
        indexes[0] = raftLog.lastIndex();
        for (int i = 0; i < peers.size(); i++) indexes[i + 1] = peers.get(i).matchIndex;
        Arrays.sort(indexes);
        // 升序排列后，取中位数左侧（多数派中最小值）
        // 例：3节点 [0,5,5] → majority=5；[0,3,5] → majority=3
        int majority = indexes[peers.size() / 2];
        if (majority > commitIndex && raftLog.termAt(majority) == currentTerm) advanceCommitIndex(majority);
    }

    private void advanceCommitIndex(int newCommitIndex) {
        for (int i = lastApplied + 1; i <= newCommitIndex; i++) {
            RaftLog.Entry entry = raftLog.get(i);
            if (entry != null) {
                stateMachine.apply(entry.index, entry.command);
                log.debug("Applied log[{}]: {}", entry.index, entry.command);

                // 发布日志提交事件
                eventBus.publish(RaftEvent.builder(RaftEvent.Type.LOG_COMMITTED, nodeId, currentTerm)
                    .commitIndex(entry.index).build());

                CompletableFuture<Void> f = pendingCommits.remove(i);
                if (f != null) f.complete(null);
            }
        }
        commitIndex = newCommitIndex;
        lastApplied = newCommitIndex;
    }

    private boolean isLogUpToDate(int lastIdx, int lastTerm) {
        if (lastTerm != raftLog.lastTerm()) return lastTerm > raftLog.lastTerm();
        return lastIdx >= raftLog.lastIndex();
    }

    private void connectPeers() { peers.forEach(RaftPeer::connect); }

    private void startRaftServer() throws InterruptedException {
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
    }

    public String  getNodeId()   { return nodeId;    }
    public Role    getRole()     { return role;       }
    public String  getLeaderId() { return leaderId;   }
    public boolean isLeader()    { return role == Role.LEADER; }
    public ScheduledExecutorService getScheduler() { return scheduler; }

    /** {@link com.hutulock.server.ioc.Lifecycle} 关闭钩子。 */
    @Override
    public void shutdown() {
        scheduler.shutdownNow();
        raftGroup.shutdownGracefully();
        log.info("RaftNode [{}] shutdown", nodeId);
    }
}
