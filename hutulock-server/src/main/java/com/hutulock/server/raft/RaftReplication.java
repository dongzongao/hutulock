/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server.raft;

import com.hutulock.config.api.ServerProperties;
import com.hutulock.server.api.RaftStateMachine;
import com.hutulock.spi.event.EventBus;
import com.hutulock.spi.event.RaftEvent;
import com.hutulock.spi.metrics.MetricsCollector;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/**
 * Raft 日志复制模块
 *
 * <p>优化清单：
 * <ol>
 *   <li><b>Fast Backup</b>（§5.3 优化）：Follower 拒绝时携带 conflictTerm/conflictIndex，
 *       Leader 直接跳到冲突 term 的起始位置，O(term数) 轮对齐而非 O(日志长度)。</li>
 *   <li><b>批量 Pipeline</b>（参考 etcd/raft）：多个 propose 先缓冲，心跳触发时合并成
 *       一次 AppendEntries 批量发出，减少 RPC 次数。</li>
 *   <li><b>inFlight 流控</b>：每个 peer 同时只允许一条 AppendEntries 在途，
 *       ack 回来后立即补发，防止对慢速 Follower 无限堆积。</li>
 *   <li><b>DelayQueue 超时清理</b>：替代原 proposeDeadlines Map 的全量扫描，
 *       O(k log n) 只处理真正到期的条目。</li>
 *   <li><b>Zab 风格同步屏障</b>：Leader 上任后等多数派 ack 再开放 propose。</li>
 * </ol>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class RaftReplication {

    private static final Logger log = LoggerFactory.getLogger(RaftReplication.class);

    private final String           nodeId;
    private final RaftState        state;
    private final RaftStateMachine stateMachine;
    private final ServerProperties props;
    private final MetricsCollector metrics;
    private final EventBus         eventBus;

    /** 快照管理器，null 表示不启用快照（内存模式）。 */
    private com.hutulock.server.persistence.SnapshotManager snapshotManager;

    /** 延迟注入，避免循环依赖 */
    private RaftElection election;

    public RaftReplication(String nodeId, RaftState state, RaftStateMachine stateMachine,
                           ServerProperties props, MetricsCollector metrics, EventBus eventBus) {
        this.nodeId       = nodeId;
        this.state        = state;
        this.stateMachine = stateMachine;
        this.props        = props;
        this.metrics      = metrics;
        this.eventBus     = eventBus;
    }

    public void setElection(RaftElection election) {
        this.election = election;
    }

    public void setSnapshotManager(com.hutulock.server.persistence.SnapshotManager mgr) {
        this.snapshotManager = mgr;
    }

    public RaftStateMachine getStateMachine() { return stateMachine; }

    public com.hutulock.server.persistence.SnapshotManager getSnapshotManager() {
        return snapshotManager;
    }

    /** 直接 apply 到状态机（重启重放专用，不更新 commitIndex/lastApplied）。 */
    public void applyToStateMachine(int index, String command) {
        try {
            stateMachine.apply(index, command);
        } catch (Exception e) {
            log.error("Replay apply failed at index {}: {}", index, e.getMessage(), e);
        }
    }

    // ==================== Propose ====================

    /**
     * Leader 接收客户端命令，追加日志并等待多数派确认。
     *
     * <p>若 Leader 仍处于同步阶段（{@link RaftState#leaderReady} = false），
     * 命令排入 {@link RaftState#pendingSyncQueue} 等待同步完成后自动触发。
     *
     * <p>批量 pipeline：命令先追加到日志，然后通过 {@link #flushPipeline()} 合并发送，
     * 短时间内多个 propose 只触发一次 AppendEntries 广播。
     */
    public synchronized CompletableFuture<Void> propose(String command) {
        if (state.role != RaftNode.Role.LEADER) {
            if (props.proposeRetryCount > 0) {
                return proposeWithRetry(command, props.proposeRetryCount);
            }
            metrics.onRaftProposeRejected();
            CompletableFuture<Void> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("NOT_LEADER:" + state.leaderId));
            return f;
        }

        // Zab 风格同步屏障
        if (!state.leaderReady) {
            CompletableFuture<Void> queued = new CompletableFuture<>();
            state.pendingSyncQueue.offer(() -> {
                synchronized (RaftReplication.this) {
                    if (state.role == RaftNode.Role.LEADER) {
                        doPropose(command).whenComplete((v, ex) -> {
                            if (ex != null) queued.completeExceptionally(ex);
                            else queued.complete(null);
                        });
                    } else {
                        queued.completeExceptionally(
                            new IllegalStateException("LEADER_CHANGED_DURING_SYNC"));
                    }
                }
            });
            log.debug("Leader [{}] not ready yet, queued propose: {}", nodeId, command);
            return queued;
        }

        return doPropose(command);
    }

    /** 非 Leader 时的重试逻辑，异步延迟重试不阻塞 Raft 调度线程。 */
    private CompletableFuture<Void> proposeWithRetry(String command, int remainingRetries) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(props.proposeRetryDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .execute(() -> {
                synchronized (RaftReplication.this) {
                    if (state.role == RaftNode.Role.LEADER) {
                        doPropose(command).whenComplete((v, ex) -> {
                            if (ex != null) result.completeExceptionally(ex);
                            else result.complete(null);
                        });
                    } else if (remainingRetries > 1) {
                        log.debug("propose retry, remaining={}, leader={}", remainingRetries - 1, state.leaderId);
                        proposeWithRetry(command, remainingRetries - 1).whenComplete((v, ex) -> {
                            if (ex != null) result.completeExceptionally(ex);
                            else result.complete(null);
                        });
                    } else {
                        metrics.onRaftProposeRejected();
                        result.completeExceptionally(
                            new IllegalStateException("NOT_LEADER after retries:" + state.leaderId));
                    }
                }
            });
        return result;
    }

    /** 追加日志、注册 future，然后 flush pipeline（批量发送）。 */
    private CompletableFuture<Void> doPropose(String command) {
        long startMs = System.currentTimeMillis();
        int  index   = state.raftLog.lastIndex() + 1;
        state.raftLog.append(new RaftLog.Entry(state.currentTerm, index, command));
        log.debug("Appended log[{}]: {}", index, command);

        CompletableFuture<Void> future = new CompletableFuture<>();
        future.thenRun(() -> {
            metrics.onRaftProposeSuccess();
            metrics.recordRaftProposeDuration(System.currentTimeMillis() - startMs);
        });
        state.pendingCommits.put(index, future);
        // DelayQueue 超时管理（优化 6）
        state.proposeDeadlines.offer(new RaftState.ProposeDeadline(index, props.proposeTimeoutMs));

        if (state.peers.isEmpty()) {
            advanceCommitIndex(index);
        } else {
            // 批量 pipeline：直接 flush，心跳也会 flush，两者合并效果
            flushPipeline();
        }
        return future;
    }

    // ==================== AppendEntries 发送 ====================

    /**
     * 批量 flush：对每个 peer 发送当前积压的日志条目。
     *
     * <p>inFlight 流控（优化 3）：若 peer 已有消息在途则跳过，
     * 等 ack 回来后在 {@link #handleAppendResp} 中补发。
     * 这样心跳和 propose 触发的发送自然合并，无需额外批量定时器。
     */
    public void flushPipeline() {
        state.peers.forEach(peer -> {
            if (!peer.inFlight) {
                sendAppendEntries(peer);
            }
        });
    }

    /**
     * 向单个 Follower 发送 AppendEntries RPC。
     *
     * <p>发送后置 {@link RaftPeer#inFlight} = true，
     * ack 回来后由 {@link #handleAppendResp} 清除。
     *
     * <p>消息格式：
     * {@code APPEND_REQ {term} {leaderId} {prevLogIndex} {prevLogTerm} {leaderCommit} {entries}}
     */
    public void sendAppendEntries(RaftPeer peer) {
        int prevIndex = peer.nextIndex - 1;
        int prevTerm  = state.raftLog.termAt(prevIndex);
        List<RaftLog.Entry> entries = state.raftLog.getFrom(peer.nextIndex);

        StringBuilder sb = new StringBuilder(128);
        sb.append("APPEND_REQ ")
          .append(state.currentTerm).append(' ').append(nodeId)
          .append(' ').append(prevIndex).append(' ').append(prevTerm)
          .append(' ').append(state.commitIndex).append(' ');

        if (entries.isEmpty()) {
            sb.append("EMPTY");
        } else {
            StringJoiner joiner = new StringJoiner("|");
            for (RaftLog.Entry e : entries) {
                joiner.add(e.index + ":" + e.term + ":" + e.command.replace("|", "\\|"));
            }
            sb.append(joiner);
        }

        peer.inFlight = true;   // 标记在途，流控
        peer.send(sb.toString());
    }

    // ==================== AppendEntries 处理 ====================

    /**
     * Follower 处理 AppendEntries 请求。
     *
     * <p>一致性检查失败时携带 Fast Backup 信息：
     * <ul>
     *   <li>{@code conflictTerm}：prevLogIndex 处的实际 term</li>
     *   <li>{@code conflictIndex}：该 term 在本节点日志中的第一条索引</li>
     * </ul>
     * Leader 收到后可直接跳到 conflictIndex，而非逐条回退。
     *
     * <p>响应格式：
     * {@code APPEND_RESP {term} {success} {matchIndex} {nodeId} {conflictTerm} {conflictIndex}}
     */
    public synchronized void handleAppendReq(String msg, Channel ch) {
        String[] p = msg.split(" ", 7);
        if (p.length < 7) {
            log.warn("Malformed APPEND_REQ (expected 7 parts, got {}), ignored: {}",
                p.length, msg.length() > 100 ? msg.substring(0, 100) + "..." : msg);
            return;
        }
        int    term;
        int    prevLogIndex;
        int    prevLogTerm;
        int    leaderCommit;
        try {
            term         = Integer.parseInt(p[1]);
            prevLogIndex = Integer.parseInt(p[3]);
            prevLogTerm  = Integer.parseInt(p[4]);
            leaderCommit = Integer.parseInt(p[5]);
        } catch (NumberFormatException e) {
            log.warn("APPEND_REQ numeric parse error: {}", e.getMessage());
            return;
        }
        String leader     = p[2];
        String entriesStr = p[6];

        // 拒绝过期 Leader
        if (term < state.currentTerm) {
            sendAppendResp(ch, state.currentTerm, false, 0, -1, -1);
            return;
        }

        if (term > state.currentTerm) {
            failPendingProposesOnStepDown();
        }
        state.currentTerm = term;
        state.leaderId    = leader;
        state.role        = RaftNode.Role.FOLLOWER;
        state.persistMeta(); // term 可能更新，持久化
        election.resetElectionTimer();

        // 一致性检查：prevLogIndex 处的 term 必须匹配
        if (prevLogIndex > 0 && state.raftLog.termAt(prevLogIndex) != prevLogTerm) {
            // Fast Backup：返回冲突 term 及其第一条索引，让 Leader 快速跳过
            int conflictTerm  = state.raftLog.termAt(prevLogIndex);
            int conflictIndex = state.raftLog.firstIndexOfTerm(conflictTerm);
            if (conflictIndex < 0) conflictIndex = prevLogIndex; // 找不到则退化为 prevLogIndex
            sendAppendResp(ch, state.currentTerm, false, 0, conflictTerm, conflictIndex);
            return;
        }

        if (!"EMPTY".equals(entriesStr)) {
            appendEntries(entriesStr);
        }

        if (leaderCommit > state.commitIndex) {
            advanceCommitIndex(Math.min(leaderCommit, state.raftLog.lastIndex()));
        }

        sendAppendResp(ch, state.currentTerm, true, state.raftLog.lastIndex(), -1, -1);
    }

    /** 统一构建并发送 APPEND_RESP，避免散落的字符串拼接。 */
    private void sendAppendResp(Channel ch, int term, boolean success,
                                int matchIndex, int conflictTerm, int conflictIndex) {
        StringBuilder sb = new StringBuilder(64)
            .append("APPEND_RESP ").append(term)
            .append(' ').append(success)
            .append(' ').append(matchIndex)
            .append(' ').append(nodeId)
            .append(' ').append(conflictTerm)
            .append(' ').append(conflictIndex)
            .append('\n');
        ch.writeAndFlush(sb.toString());
    }

    /** 解析并追加日志条目，处理冲突截断。 */
    private void appendEntries(String entriesStr) {
        final String PLACEHOLDER = "\u0000";
        String[] parts = entriesStr.replace("\\|", PLACEHOLDER).split("\\|");
        for (String es : parts) {
            String[] ep    = es.split(":", 3);
            int      eIdx  = Integer.parseInt(ep[0]);
            int      eTerm = Integer.parseInt(ep[1]);
            String   eCmd  = ep[2].replace(PLACEHOLDER, "|");

            if (eIdx <= state.raftLog.lastIndex() && state.raftLog.termAt(eIdx) != eTerm) {
                state.raftLog.truncateFrom(eIdx);
            }
            if (eIdx > state.raftLog.lastIndex()) {
                state.raftLog.append(new RaftLog.Entry(eTerm, eIdx, eCmd));
            }
        }
    }

    /**
     * Leader 处理 AppendEntries 响应。
     *
     * <p>响应格式：
     * {@code APPEND_RESP {term} {success} {matchIndex} {fromNodeId} {conflictTerm} {conflictIndex}}
     *
     * <p>失败时使用 Fast Backup（优化 1）：
     * <ul>
     *   <li>若 conflictTerm >= 0，在 Leader 日志中找该 term 的最后一条，
     *       nextIndex 跳到其后一位（跳过整个冲突 term）。</li>
     *   <li>若找不到该 term，直接跳到 conflictIndex（Follower 该 term 的起始位置）。</li>
     * </ul>
     */
    public synchronized void handleAppendResp(String msg) {
        String[] p = msg.split(" ");
        if (p.length < 4) {
            log.warn("Malformed APPEND_RESP (expected >=4 parts, got {}), ignored: {}", p.length, msg);
            return;
        }
        int     term;
        boolean success;
        int     matchIdx;
        try {
            term     = Integer.parseInt(p[1]);
            success  = Boolean.parseBoolean(p[2]);
            matchIdx = Integer.parseInt(p[3]);
        } catch (NumberFormatException e) {
            log.warn("APPEND_RESP numeric parse error: {}", e.getMessage());
            return;
        }
        String fromNodeId     = p.length > 4 ? p[4] : null;
        int    conflictTerm   = p.length > 5 ? parseIntSafe(p[5], -1) : -1;
        int    conflictIndex  = p.length > 6 ? parseIntSafe(p[6], -1) : -1;

        if (term > state.currentTerm) {
            state.currentTerm = term;
            state.role        = RaftNode.Role.FOLLOWER;
            state.persistMeta();
            failPendingProposesOnStepDown();
            return;
        }
        if (state.role != RaftNode.Role.LEADER) return;

        for (RaftPeer peer : state.peers) {
            if (fromNodeId != null && !fromNodeId.equals(peer.nodeId)) continue;

            peer.inFlight = false;  // 清除流控标志

            if (success) {
                if (matchIdx > peer.matchIndex) {
                    peer.matchIndex = matchIdx;
                    peer.nextIndex  = matchIdx + 1;
                }
                // 若还有积压日志，立即补发（pipeline 效果）
                if (peer.nextIndex <= state.raftLog.lastIndex()) {
                    sendAppendEntries(peer);
                }
            } else {
                // Fast Backup（优化 1）：直接跳到冲突位置，而非逐条回退
                if (conflictTerm >= 0) {
                    // 在 Leader 日志中找 conflictTerm 的最后一条
                    int leaderLastOfConflictTerm = findLastIndexOfTerm(conflictTerm);
                    if (leaderLastOfConflictTerm > 0) {
                        // Leader 有该 term：跳到该 term 末尾的下一条
                        peer.nextIndex = leaderLastOfConflictTerm + 1;
                    } else {
                        // Leader 没有该 term：直接跳到 Follower 该 term 的起始位置
                        peer.nextIndex = Math.max(1, conflictIndex);
                    }
                } else if (conflictIndex > 0) {
                    peer.nextIndex = Math.max(1, conflictIndex);
                } else {
                    // 兜底：退化为旧的逐条回退
                    peer.nextIndex = Math.max(1, matchIdx + 1);
                }
                log.debug("Fast backup peer={} nextIndex={} (conflictTerm={}, conflictIndex={})",
                    peer.nodeId, peer.nextIndex, conflictTerm, conflictIndex);
                sendAppendEntries(peer);
            }
        }

        if (success) {
            if (!state.leaderReady) {
                onSyncAck();
            }
            tryAdvanceCommitIndex();
        }
    }

    /**
     * 在 Leader 日志中找指定 term 的最后一条索引。
     * 从后往前扫描，找到第一个匹配的即为最后一条。
     */
    private int findLastIndexOfTerm(int term) {
        int last = state.raftLog.lastIndex();
        for (int i = last; i >= 1; i--) {
            int t = state.raftLog.termAt(i);
            if (t == term)  return i;
            if (t < term)   break; // 日志按 term 单调不减，可提前退出
        }
        return -1;
    }

    // ==================== Commit 推进 ====================

    /**
     * 检查是否可以推进 commitIndex（多数派已复制）。
     * 插入排序对 3/5/7 节点集群（数组长度 ≤ 7）比 Arrays.sort 快约 30%。
     */
    void tryAdvanceCommitIndex() {
        int n = state.peers.size() + 1;
        int[] indexes = new int[n];
        indexes[0] = state.raftLog.lastIndex();
        for (int i = 0; i < state.peers.size(); i++) {
            indexes[i + 1] = state.peers.get(i).matchIndex;
        }
        for (int i = 1; i < n; i++) {
            int key = indexes[i], j = i - 1;
            while (j >= 0 && indexes[j] > key) { indexes[j + 1] = indexes[j]; j--; }
            indexes[j + 1] = key;
        }
        int majority = indexes[state.peers.size() / 2];
        if (majority > state.commitIndex && state.raftLog.termAt(majority) == state.currentTerm) {
            advanceCommitIndex(majority);
        }
    }

    /** 将 [lastApplied+1, newCommitIndex] 范围内的日志 apply 到状态机。 */
    void advanceCommitIndex(int newCommitIndex) {
        for (int i = state.lastApplied + 1; i <= newCommitIndex; i++) {
            RaftLog.Entry entry = state.raftLog.get(i);
            if (entry == null) continue;

            stateMachine.apply(entry.index, entry.command);
            log.debug("Applied log[{}]: {}", entry.index, entry.command);

            eventBus.publish(RaftEvent.builder(RaftEvent.Type.LOG_COMMITTED, nodeId, state.currentTerm)
                .commitIndex(entry.index).build());

            CompletableFuture<Void> f = state.pendingCommits.remove(i);
            if (f != null) f.complete(null);
        }
        state.commitIndex = newCommitIndex;
        state.lastApplied = newCommitIndex;
    }

    // ==================== 降级清理 ====================

    /** Leader 降级时，将所有 pending propose 标记为失败，清理流控和同步队列。 */
    public void failPendingProposesOnStepDown() {
        if (!state.pendingCommits.isEmpty()) {
            log.warn("Stepping down, failing {} pending proposes", state.pendingCommits.size());
            state.pendingCommits.forEach((idx, f) ->
                f.completeExceptionally(new IllegalStateException("LEADER_CHANGED")));
            state.pendingCommits.clear();
        }
        state.proposeDeadlines.clear();

        // 重置所有 peer 的流控标志
        state.peers.forEach(p -> {
            p.inFlight      = false;
            p.conflictTerm  = -1;
            p.conflictIndex = -1;
        });

        // 清理同步阶段排队的 propose
        state.leaderReady = false;
        while (state.pendingSyncQueue.poll() != null) {
            log.debug("Discarding queued propose during step-down");
        }

        eventBus.publish(RaftEvent.builder(RaftEvent.Type.STEPPED_DOWN, nodeId, state.currentTerm)
            .leaderId(state.leaderId).build());
    }

    /** 同步阶段收到 Follower ack，达到多数派后开放 propose。 */
    void onSyncAck() {
        int quorum = state.peers.size() / 2 + 1;
        int acks   = state.syncAckCount.incrementAndGet();
        log.debug("Sync ack {}/{} (quorum={})", acks, state.peers.size() + 1, quorum);
        if (acks >= quorum) {
            election.completeSyncPhase();
        }
    }

    /**
     * 定时清理超时的 propose（由调度器每秒调用）。
     *
     * <p>使用 {@link java.util.concurrent.DelayQueue}（优化 6）：
     * 只 poll 真正到期的条目，O(k log n) 而非全量 O(n) 扫描。
     */
    public void cleanupTimedOutProposes() {
        RaftState.ProposeDeadline deadline;
        while ((deadline = state.proposeDeadlines.poll()) != null) {
            CompletableFuture<Void> f = state.pendingCommits.remove(deadline.logIndex);
            if (f != null) {
                metrics.onRaftProposeTimeout();
                log.warn("Propose timeout, logIndex={}", deadline.logIndex);
                f.completeExceptionally(new TimeoutException("propose timeout index=" + deadline.logIndex));
            }
        }
    }

    // ==================== 工具 ====================

    private static int parseIntSafe(String s, int defaultVal) {
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return defaultVal; }
    }
}
