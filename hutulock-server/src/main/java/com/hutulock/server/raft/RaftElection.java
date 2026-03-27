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
import com.hutulock.model.util.Strings;
import com.hutulock.spi.event.EventBus;
import com.hutulock.spi.event.RaftEvent;
import com.hutulock.spi.metrics.MetricsCollector;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Raft 选举模块
 *
 * <p>职责：
 * <ol>
 *   <li>选举超时计时器管理（随机超时，防止选票分裂）</li>
 *   <li>发起选举（VOTE_REQ）</li>
 *   <li>处理投票请求（handleVoteReq）</li>
 *   <li>处理投票响应（handleVoteResp）</li>
 *   <li>成为 Leader（becomeLeader）</li>
 * </ol>
 *
 * <p>并发模型：所有公共方法均加 {@code synchronized}，
 * 与 {@link RaftReplication} 共享同一把对象锁（均锁 {@code this}，
 * 调用方保证在同一线程/锁域内操作 {@link RaftState}）。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class RaftElection {

    private static final Logger log = LoggerFactory.getLogger(RaftElection.class);

    private final String                   nodeId;
    private final RaftState                state;
    private final ServerProperties         props;
    private final MetricsCollector         metrics;
    private final EventBus                 eventBus;
    private final ScheduledExecutorService scheduler;
    private final RaftReplication          replication;

    /** 选举超时范围（ms），缓存避免每次重算 */
    private final long electionTimeoutRange;

    /** 连续选举失败计数（超过上限后退回 FOLLOWER 等待，防止无限选举） */
    private int consecutiveElections = 0;
    /** 单轮选举最大连续次数，超过后强制冷却一个完整超时周期 */
    private static final int MAX_CONSECUTIVE_ELECTIONS = Numbers.RAFT_MAX_CONSECUTIVE_ELECTIONS;

    private ScheduledFuture<?> electionTimer;
    private ScheduledFuture<?> heartbeatTimer;

    public RaftElection(String nodeId, RaftState state, ServerProperties props,
                        MetricsCollector metrics, EventBus eventBus,
                        ScheduledExecutorService scheduler, RaftReplication replication) {
        this.nodeId               = nodeId;
        this.state                = state;
        this.props                = props;
        this.metrics              = metrics;
        this.eventBus             = eventBus;
        this.scheduler            = scheduler;
        this.replication          = replication;
        this.electionTimeoutRange = props.electionTimeoutMaxMs - props.electionTimeoutMinMs;
    }

    // ==================== 计时器 ====================

    /**
     * 重置选举超时计时器。
     *
     * <p>使用 {@link ThreadLocalRandom} 替代 {@link Math#random()}，
     * 避免全局锁竞争，在多线程环境下性能更好。
     */
    public void resetElectionTimer() {
        if (electionTimer != null) electionTimer.cancel(false);
        // ThreadLocalRandom 无全局锁，比 Math.random() 快约 3-5 倍
        long timeout = props.electionTimeoutMinMs
            + ThreadLocalRandom.current().nextLong(electionTimeoutRange + 1);
        electionTimer = scheduler.schedule(this::startElection, timeout, TimeUnit.MILLISECONDS);
    }

    public void cancelHeartbeat() {
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel(false);
            heartbeatTimer = null;
        }
    }

    // ==================== 选举发起 ====================

    /**
     * 发起选举：term++，广播 VOTE_REQ。
     *
     * <p>VOTE_REQ 消息用 {@link StringBuilder} 手动拼接，
     * 避免 {@link String#format} 的反射和格式解析开销。
     * 连续选举超过 {@link #MAX_CONSECUTIVE_ELECTIONS} 次后强制冷却，防止无限选举风暴。
     */
    synchronized void startElection() {
        if (state.role == RaftNode.Role.LEADER) return;

        // 兜底：连续选举次数上限，防止无限选举（如网络分区场景）
        if (++consecutiveElections > MAX_CONSECUTIVE_ELECTIONS) {
            log.warn("Node [{}] reached max consecutive elections ({}), cooling down for one timeout period",
                nodeId, MAX_CONSECUTIVE_ELECTIONS);
            consecutiveElections = 0;
            RaftRoleStateMachine.INSTANCE.tryTransit(state, RaftNode.Role.FOLLOWER);
            resetElectionTimer();
            return;
        }

        state.currentTerm++;
        RaftRoleStateMachine.INSTANCE.tryTransit(state, RaftNode.Role.CANDIDATE);
        state.votedFor = nodeId;
        state.voteCount.set(1);
        state.persistMeta(); // Raft §5.4：term/votedFor 必须在发送 RPC 前持久化

        metrics.onRaftElectionStarted();
        log.info("Starting election, term={}", state.currentTerm);
        eventBus.publish(RaftEvent.builder(RaftEvent.Type.ELECTION_STARTED, nodeId, state.currentTerm).build());

        // 单节点集群直接成为 Leader，无需广播
        if (state.peers.isEmpty()) {
            becomeLeader();
            return;
        }

        // 手动拼接替代 String.format，避免反射开销
        String req = buildVoteReq();
        state.peers.forEach(p -> p.send(req));
        resetElectionTimer();
    }

    /**
     * 构建 VOTE_REQ 消息。
     * 格式：{@code VOTE_REQ {term} {nodeId} {lastLogIndex} {lastLogTerm}}
     */
    private String buildVoteReq() {
        return new StringBuilder(Numbers.MSG_BUILDER_MEDIUM)
            .append("VOTE_REQ ")
            .append(state.currentTerm).append(' ')
            .append(nodeId).append(' ')
            .append(state.raftLog.lastIndex()).append(' ')
            .append(state.raftLog.lastTerm())
            .toString();
    }

    /**
     * 赢得多数票后成为 Leader，进入 Zab 风格的 Recovery Phase。
     *
     * <p>不立即开放 propose，而是先广播一轮同步心跳（空 AppendEntries），
     * 等多数派确认日志对齐后（{@link RaftState#leaderReady} = true）才开放写入。
     * 这样避免 Leader 刚上任时 Follower 日志参差不齐导致的多轮 nextIndex 回退。
     */
    synchronized void becomeLeader() {
        if (state.role != RaftNode.Role.CANDIDATE) return;
        RaftRoleStateMachine.INSTANCE.tryTransit(state, RaftNode.Role.LEADER);
        state.leaderId    = nodeId;
        state.leaderReady = false;          // 进入同步阶段，暂不开放 propose
        state.syncAckCount.set(1);          // 自身算一票
        consecutiveElections = 0;
        metrics.onRaftLeaderChanged();
        log.info("Became LEADER term={}, entering sync phase (Zab-style recovery)", state.currentTerm);

        eventBus.publish(RaftEvent.builder(RaftEvent.Type.BECAME_LEADER, nodeId, state.currentTerm)
            .leaderId(nodeId).build());

        // 初始化 nextIndex / matchIndex
        int nextIdx = state.raftLog.lastIndex() + 1;
        state.peers.forEach(p -> { p.nextIndex = nextIdx; p.matchIndex = 0; });

        if (state.peers.isEmpty()) {
            // 单节点：无需同步，直接就绪
            completeSyncPhase();
        } else {
            // 广播同步心跳，等多数派 ack 后再开放 propose
            startHeartbeat();
        }
    }

    /**
     * 同步阶段完成：开放 propose，并触发排队中的请求。
     * 由 {@link RaftReplication#onSyncAck()} 在多数派确认后调用。
     */
    synchronized void completeSyncPhase() {
        if (state.role != RaftNode.Role.LEADER || state.leaderReady) return;
        state.leaderReady = true;
        log.info("Leader [{}] sync phase complete, term={}, ready to accept proposals", nodeId, state.currentTerm);

        eventBus.publish(RaftEvent.builder(RaftEvent.Type.BECAME_LEADER, nodeId, state.currentTerm)
            .leaderId(nodeId).build());

        // 触发同步阶段期间排队的 propose
        Runnable task;
        while ((task = state.pendingSyncQueue.poll()) != null) {
            task.run();
        }
    }

    private void startHeartbeat() {
        cancelHeartbeat();
        heartbeatTimer = scheduler.scheduleAtFixedRate(
            this::sendHeartbeats, 0, props.heartbeatIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void sendHeartbeats() {
        if (state.role != RaftNode.Role.LEADER) return;
        // 复用 flushPipeline：心跳与积压日志合并发送，inFlight 流控自动跳过在途 peer
        replication.flushPipeline();
    }

    // ==================== 消息处理 ====================

    /**
     * 处理 VOTE_REQ。
     * 格式：{@code VOTE_REQ {term} {candidateId} {lastLogIndex} {lastLogTerm}}
     *
     * <p>使用手动 {@code indexOf} 解析替代 {@code split(" ")}，
     * 避免正则引擎初始化和数组分配开销。
     */
    public synchronized void handleVoteReq(String msg, Channel ch) {
        // 兜底：格式校验
        int i1 = msg.indexOf(' ');
        int i2 = i1 > 0 ? msg.indexOf(' ', i1 + 1) : -1;
        int i3 = i2 > 0 ? msg.indexOf(' ', i2 + 1) : -1;
        int i4 = i3 > 0 ? msg.indexOf(' ', i3 + 1) : -1;
        if (i1 < 0 || i2 < 0 || i3 < 0 || i4 < 0) {
            log.warn("Malformed VOTE_REQ message, ignored: {}", msg);
            return;
        }

        int    term      = parseInt(msg, i1 + 1, i2);
        String candidate = msg.substring(i2 + 1, i3);
        int    lastIdx   = parseInt(msg, i3 + 1, i4);
        int    lastTerm  = parseInt(msg, i4 + 1, msg.length());

        if (term > state.currentTerm) {
            state.currentTerm = term;
            RaftRoleStateMachine.INSTANCE.tryTransit(state, RaftNode.Role.FOLLOWER);
            state.votedFor    = null;
            state.persistMeta();
            replication.failPendingProposesOnStepDown();
        }

        boolean granted = term == state.currentTerm
            && (state.votedFor == null || state.votedFor.equals(candidate))
            && isLogUpToDate(lastIdx, lastTerm);

        if (granted) {
            state.votedFor = candidate;
            state.persistMeta(); // 投票前持久化，防止重启后重复投票
            resetElectionTimer();
        }

        // 手动拼接响应，避免字符串连接产生中间对象
        ch.writeAndFlush(buildVoteResp(state.currentTerm, granted));
        log.debug("Vote for {}: {}", candidate, granted);
    }

    /**
     * 处理 VOTE_RESP。
     * 格式：{@code VOTE_RESP {term} {granted}}
     */
    public synchronized void handleVoteResp(String msg) {
        // 兜底：格式校验
        int i1 = msg.indexOf(' ');
        int i2 = i1 > 0 ? msg.indexOf(' ', i1 + 1) : -1;
        if (i1 < 0 || i2 < 0) {
            log.warn("Malformed VOTE_RESP message, ignored: {}", msg);
            return;
        }

        int     term    = parseInt(msg, i1 + 1, i2);
        boolean granted = msg.charAt(i2 + 1) == 't'; // "true" 首字母判断，避免 Boolean.parseBoolean

        if (term > state.currentTerm) {
            state.currentTerm = term;
            RaftRoleStateMachine.INSTANCE.tryTransit(state, RaftNode.Role.FOLLOWER);
            state.persistMeta();
            replication.failPendingProposesOnStepDown();
            return;
        }
        if (state.role != RaftNode.Role.CANDIDATE || term != state.currentTerm) return;

        if (!granted) return;

        // quorum = floor((n+1)/2) + 1，n = 总节点数（含自身）
        // 等价于 ceil((n+1)/2)，即严格多数派
        int totalNodes = state.peers.size() + 1;
        int quorum     = totalNodes / 2 + 1;
        if (state.voteCount.incrementAndGet() >= quorum) {
            becomeLeader();
        }
    }

    // ==================== 工具 ====================

    /**
     * 判断候选人日志是否至少与本节点一样新（Raft §5.4.1）。
     * 先比较 lastTerm，相同再比较 lastIndex。
     */
    private boolean isLogUpToDate(int lastIdx, int lastTerm) {
        int myLastTerm = state.raftLog.lastTerm();
        if (lastTerm != myLastTerm) return lastTerm > myLastTerm;
        return lastIdx >= state.raftLog.lastIndex();
    }

    /**
     * 从字符串 [start, end) 区间解析整数，避免 {@link Integer#parseInt(String)} 的子串分配。
     */
    private static int parseInt(String s, int start, int end) {
        int result = 0;
        for (int i = start; i < end; i++) {
            result = result * 10 + (s.charAt(i) - '0');
        }
        return result;
    }

    /**
     * 构建 VOTE_RESP 消息，避免字符串拼接产生中间对象。
     */
    private static String buildVoteResp(int term, boolean granted) {
        return new StringBuilder(Numbers.MSG_BUILDER_SMALL)
            .append("VOTE_RESP ")
            .append(term).append(' ')
            .append(granted)
            .append(Strings.MSG_LINE_END)
            .toString();
    }
}
