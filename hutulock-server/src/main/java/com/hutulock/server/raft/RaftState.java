/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server.raft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Raft 节点共享状态（单一数据源）
 *
 * <p>将原 RaftNode 中散落的状态字段集中到此类，
 * RaftElection / RaftReplication 通过持有同一个 RaftState 实例共享状态，
 * 避免跨模块的字段引用混乱。
 *
 * <p>所有字段的并发访问由调用方持有 RaftNode 的 {@code synchronized} 锁保护，
 * 此类本身不加锁。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class RaftState {

    // ---- 持久化状态（重启后需恢复，当前为内存实现） ----
    public volatile int    currentTerm = 0;
    public volatile String votedFor    = null;
    public final    RaftLog raftLog;

    /** 元数据持久化（currentTerm + votedFor），null 表示内存模式。 */
    public final com.hutulock.server.persistence.RaftMetaStore metaStore;

    /** 无持久化（内存模式，测试用）。 */
    public RaftState() {
        this.raftLog   = new RaftLog();
        this.metaStore = null;
    }

    /** WAL 持久化模式。 */
    public RaftState(String dataDir) {
        this.raftLog   = new RaftLog(dataDir);
        this.metaStore = new com.hutulock.server.persistence.RaftMetaStore(dataDir);
        com.hutulock.server.persistence.RaftMetaStore.Meta meta = metaStore.load();
        this.currentTerm = meta.currentTerm;
        this.votedFor    = meta.votedFor;
    }

    /**
     * 持久化 currentTerm 和 votedFor（Raft §5.4 要求在响应 RPC 前完成）。
     * 内存模式下为空操作。
     */
    public void persistMeta() {
        if (metaStore != null) {
            metaStore.persist(currentTerm, votedFor);
        }
    }

    // ---- 易失状态 ----
    public volatile int    commitIndex = 0;
    public volatile int    lastApplied = 0;
    public volatile RaftNode.Role role = RaftNode.Role.FOLLOWER;
    public volatile String leaderId    = null;

    // ---- 选举 ----
    public final AtomicInteger voteCount = new AtomicInteger(0);

    /**
     * Leader 同步屏障（参考 Zab Recovery Phase）。
     *
     * <p>新 Leader 上任后先广播一轮同步心跳，等多数派确认日志对齐后置 true，
     * 才开放 propose。避免 Leader 刚上任时 Follower 日志未对齐导致的多轮回退。
     *
     * <p>FOLLOWER / CANDIDATE 状态下此字段无意义。
     */
    public volatile boolean leaderReady = false;

    /**
     * Leader 同步阶段已收到 ack 的节点数（含自身）。
     * 达到多数派后置 {@link #leaderReady} = true。
     */
    public final AtomicInteger syncAckCount = new AtomicInteger(0);

    /**
     * Leader 同步阶段等待 propose 的队列。
     * leaderReady=true 后批量触发。
     */
    public final java.util.Queue<Runnable> pendingSyncQueue =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    // ---- Propose 管理 ----
    /** logIndex → 等待 commit 的 CompletableFuture */
    public final Map<Integer, CompletableFuture<Void>> pendingCommits = new ConcurrentHashMap<>();

    /**
     * propose 超时优先队列（替代原 proposeDeadlines Map 的全量扫描）。
     *
     * <p>每个 {@link ProposeDeadline} 按到期时间排序，
     * {@code cleanupTimedOutProposes} 只需 poll 已到期的条目，O(k log n) vs O(n)。
     */
    public final java.util.concurrent.DelayQueue<ProposeDeadline> proposeDeadlines =
        new java.util.concurrent.DelayQueue<>();

    /**
     * propose 批量 pipeline 窗口（参考 etcd/raft）。
     *
     * <p>短时间内多个 propose 先缓冲到此队列，由调度器定期（1-5ms）合并成
     * 一次 AppendEntries 批量发出，减少 RPC 次数，提升吞吐量。
     * 当前实现：每次心跳触发时顺带 flush，无需额外定时器。
     */
    public final java.util.Queue<String> proposePipeline =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** propose 超时条目（用于 DelayQueue）。 */
    public static final class ProposeDeadline implements java.util.concurrent.Delayed {
        public final int  logIndex;
        private final long deadlineNs;

        public ProposeDeadline(int logIndex, long delayMs) {
            this.logIndex   = logIndex;
            this.deadlineNs = System.nanoTime() + delayMs * 1_000_000L;
        }

        @Override
        public long getDelay(java.util.concurrent.TimeUnit unit) {
            return unit.convert(deadlineNs - System.nanoTime(), java.util.concurrent.TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(java.util.concurrent.Delayed o) {
            return Long.compare(deadlineNs, ((ProposeDeadline) o).deadlineNs);
        }
    }

    // ---- Peer 列表 ----
    public final List<RaftPeer> peers = new ArrayList<>();
}
