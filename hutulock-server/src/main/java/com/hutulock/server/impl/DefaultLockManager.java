/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server.impl;

import com.hutulock.model.protocol.CommandType;
import com.hutulock.model.protocol.Message;
import com.hutulock.model.znode.ZNode;
import com.hutulock.model.znode.ZNodePath;
import com.hutulock.model.znode.ZNodeType;
import com.hutulock.spi.event.EventBus;
import com.hutulock.spi.event.LockEvent;
import com.hutulock.spi.lock.LockService;
import com.hutulock.spi.lock.LockToken;
import com.hutulock.spi.metrics.MetricsCollector;
import com.hutulock.spi.session.SessionTracker;
import com.hutulock.spi.storage.ZNodeStorage;
import com.hutulock.server.api.RaftStateMachine;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁管理器默认实现（ZooKeeper 顺序临时节点模式）
 *
 * <p>同时实现 {@link LockService} 和 {@link RaftStateMachine}。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class DefaultLockManager implements LockService, RaftStateMachine {

    private static final Logger log = LoggerFactory.getLogger(DefaultLockManager.class);

    private static final String LOCKS_ROOT = "/locks";
    private static final String SEQ_PREFIX = "seq-";

    private final ZNodeStorage   zNodeStorage;
    private final SessionTracker sessionTracker;
    private final MetricsCollector metrics;
    private final EventBus       eventBus;
    private String nodeId = "unknown";

    public DefaultLockManager(ZNodeStorage zNodeStorage, SessionTracker sessionTracker,
                               MetricsCollector metrics, EventBus eventBus) {
        this.zNodeStorage   = zNodeStorage;
        this.sessionTracker = sessionTracker;
        this.metrics        = metrics;
        this.eventBus       = eventBus;
        ensureLocksRoot();
    }

    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    private void ensureLocksRoot() {
        ZNodePath root = ZNodePath.of(LOCKS_ROOT);
        if (!zNodeStorage.exists(root)) {
            zNodeStorage.create(root, ZNodeType.PERSISTENT, new byte[0], null);
        }
    }

    // ==================== LockService ====================

    @Override
    public LockToken tryAcquire(String lockName, String sessionId, long timeout, TimeUnit unit) {
        throw new UnsupportedOperationException("Use Raft propose to acquire lock");
    }

    @Override
    public void release(LockToken token) {
        throw new UnsupportedOperationException("Use Raft propose to release lock");
    }

    @Override
    public void renew(String lockName, String sessionId) {
        sessionTracker.heartbeat(sessionId);
    }

    // ==================== RaftStateMachine ====================

    @Override
    public synchronized void apply(int index, String command) {
        Message msg;
        try {
            msg = Message.parse(command);
        } catch (Exception e) {
            log.error("Failed to parse command at index {}: {}", index, command, e);
            return;
        }
        switch (msg.getType()) {
            case LOCK:   applyLock(msg.arg(0), msg.arg(1));   break;
            case UNLOCK: applyUnlock(msg.arg(0), msg.arg(1)); break;
            case RENEW:  renew(msg.arg(0), msg.arg(1));       break;
            default: log.warn("Unexpected command: {}", msg.getType());
        }
    }

    // ==================== 锁操作 ====================

    private void applyLock(String lockName, String sessionId) {
        long startMs = System.currentTimeMillis();
        Channel channel = sessionTracker.getChannel(sessionId);

        ZNodePath lockRoot = ZNodePath.of(LOCKS_ROOT + "/" + lockName);
        if (!zNodeStorage.exists(lockRoot)) {
            zNodeStorage.create(lockRoot, ZNodeType.PERSISTENT, new byte[0], null);
        }

        ZNodePath seqPath    = ZNodePath.of(lockRoot, SEQ_PREFIX);
        ZNodePath actualPath = zNodeStorage.create(seqPath, ZNodeType.EPHEMERAL_SEQ,
            sessionId.getBytes(), sessionId);

        checkAndGrantLock(lockRoot, actualPath, sessionId, channel, startMs);
    }

    private void checkAndGrantLock(ZNodePath lockRoot, ZNodePath myPath,
                                    String sessionId, Channel channel, long startMs) {
        List<ZNodePath> children = zNodeStorage.getChildren(lockRoot);
        if (children.isEmpty()) { log.error("No children under {}", lockRoot); return; }

        if (children.get(0).equals(myPath)) {
            metrics.onLockAcquired(lockRoot.name());
            metrics.recordLockAcquireDuration(lockRoot.name(), System.currentTimeMillis() - startMs);
            log.info("Lock acquired: path={}, session={}", myPath, sessionId);
            eventBus.publish(LockEvent.builder(LockEvent.Type.ACQUIRED, lockRoot.name(), sessionId)
                .sourceNode(nodeId).seqNodePath(myPath.value()).build());
            if (channel != null && channel.isActive()) {
                channel.writeAndFlush(Message.of(CommandType.OK, lockRoot.name(), myPath.value()).serialize() + "\n");
            }
        } else {
            ZNodePath prevPath = findPrevNode(children, myPath);
            metrics.onLockWaiting(lockRoot.name());
            log.debug("Lock waiting, watching prev: {}", prevPath);
            eventBus.publish(LockEvent.builder(LockEvent.Type.WAITING, lockRoot.name(), sessionId)
                .sourceNode(nodeId).seqNodePath(myPath.value()).build());
            if (channel != null) {
                zNodeStorage.watch(prevPath, channel);
                channel.writeAndFlush(Message.of(CommandType.WAIT, lockRoot.name(), myPath.value()).serialize() + "\n");
            }
        }
    }

    private ZNodePath findPrevNode(List<ZNodePath> sorted, ZNodePath myPath) {
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).equals(myPath)) return sorted.get(i - 1);
        }
        return sorted.get(0);
    }

    private void applyUnlock(String seqNodePath, String sessionId) {
        ZNodePath path = ZNodePath.of(seqNodePath);
        ZNode     node = zNodeStorage.get(path);

        if (node == null) { log.warn("Unlock: node not found: {}", seqNodePath); return; }
        if (!sessionId.equals(node.getSessionId())) {
            log.warn("Unlock rejected: {} is not owner of {}", sessionId, seqNodePath); return;
        }

        long acquiredAt = node.getCreateTime();
        zNodeStorage.delete(path);
        metrics.onLockReleased(path.parent().name());
        log.info("Lock released: path={}, session={}", seqNodePath, sessionId);
        eventBus.publish(LockEvent.builder(LockEvent.Type.RELEASED, path.parent().name(), sessionId)
            .sourceNode(nodeId).seqNodePath(seqNodePath)
            .heldDuration(System.currentTimeMillis() - acquiredAt).build());

        Channel channel = sessionTracker.getChannel(sessionId);
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(Message.of(CommandType.RELEASED, path.parent().name()).serialize() + "\n");
        }
    }

    // BUG-FIX 4: recheckLock 与 apply(synchronized) 存在竞态。
    // 场景：apply 正在删除 seq-1（unlock），recheckLock 同时读 getChildren，
    // 可能看到 seq-1 仍存在，错误地认为 seq-2 不是最小节点。
    // 修复：加 synchronized，与 apply() 互斥。
    public synchronized void recheckLock(String lockName, String mySeqPath, String sessionId) {
        ZNodePath lockRoot = ZNodePath.of(LOCKS_ROOT + "/" + lockName);
        ZNodePath myPath   = ZNodePath.of(mySeqPath);
        Channel   channel  = sessionTracker.getChannel(sessionId);

        if (!zNodeStorage.exists(myPath)) {
            log.warn("Recheck: seq node {} no longer exists", mySeqPath); return;
        }

        List<ZNodePath> children = zNodeStorage.getChildren(lockRoot);
        if (children.isEmpty()) return;

        if (children.get(0).equals(myPath)) {
            metrics.onLockGrantedFromQueue(lockRoot.name());
            log.info("Lock granted from queue: path={}, session={}", myPath, sessionId);
            eventBus.publish(LockEvent.builder(LockEvent.Type.ACQUIRED_QUEUED, lockRoot.name(), sessionId)
                .sourceNode(nodeId).seqNodePath(myPath.value()).build());
            if (channel != null && channel.isActive()) {
                channel.writeAndFlush(Message.of(CommandType.OK, lockRoot.name(), myPath.value()).serialize() + "\n");
            }
        } else {
            ZNodePath prevPath = findPrevNode(children, myPath);
            log.debug("Still waiting, re-watching: {}", prevPath);
            if (channel != null) {
                zNodeStorage.watch(prevPath, channel);
                channel.writeAndFlush(Message.of(CommandType.WAIT, lockRoot.name(), myPath.value()).serialize() + "\n");
            }
        }
    }
}
