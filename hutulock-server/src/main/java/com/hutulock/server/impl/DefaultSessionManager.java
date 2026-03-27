/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server.impl;

import com.hutulock.config.api.ServerProperties;
import com.hutulock.model.session.Session;
import com.hutulock.model.session.Session.State;
import com.hutulock.model.watcher.WatchEvent;
import com.hutulock.model.znode.ZNodePath;
import com.hutulock.spi.event.EventBus;
import com.hutulock.spi.event.SessionEvent;
import com.hutulock.spi.metrics.MetricsCollector;
import com.hutulock.spi.session.SessionTracker;
import com.hutulock.spi.storage.ZNodeStorage;
import com.hutulock.server.ioc.Lifecycle;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * 会话管理器默认实现
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class DefaultSessionManager implements SessionTracker, Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(DefaultSessionManager.class);

    private final Map<String, Session> sessions         = new ConcurrentHashMap<>();
    private final Map<String, Channel> sessionChannels  = new ConcurrentHashMap<>();
    private final Map<String, String>  channelToSession = new ConcurrentHashMap<>();
    /**
     * 按过期时间排序的优先队列，用于加速过期扫描。
     * 扫描时只需检查队头，O(k log s) 而非 O(s)（k=过期数，s=总会话数）。
     */
    private final java.util.PriorityQueue<Session> expiryQueue = new java.util.PriorityQueue<>(
        Comparator.comparingLong(Session::getExpireTime));

    private final ZNodeStorage  zNodeStorage;
    private final MetricsCollector metrics;
    private final EventBus      eventBus;
    private final long          defaultTimeoutMs;
    private final ScheduledExecutorService scanner;
    private String nodeId = "unknown";

    public DefaultSessionManager(ZNodeStorage zNodeStorage, MetricsCollector metrics,
                                  EventBus eventBus, ServerProperties props) {
        this.zNodeStorage     = zNodeStorage;
        this.metrics          = metrics;
        this.eventBus         = eventBus;
        this.defaultTimeoutMs = props.watchdogTtlMs;

        this.scanner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hutulock-session-scanner");
            t.setDaemon(true);
            return t;
        });
        scanner.scheduleAtFixedRate(this::scanExpiredSessions,
            props.watchdogScanIntervalMs, props.watchdogScanIntervalMs, TimeUnit.MILLISECONDS);
    }

    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    @Override
    public Session createSession(String clientId, Channel channel) {
        return createSession(clientId, channel, defaultTimeoutMs);
    }

    @Override
    public Session createSession(String clientId, Channel channel, long timeoutMs) {
        Session session = new Session(clientId, timeoutMs);
        session.transitionTo(State.CONNECTED);
        sessions.put(session.getSessionId(), session);
        sessionChannels.put(session.getSessionId(), channel);
        channelToSession.put(channel.id().asShortText(), session.getSessionId());
        synchronized (expiryQueue) { expiryQueue.offer(session); }

        metrics.onSessionCreated();
        log.info("Session created: {}", session);
        eventBus.publish(SessionEvent.builder(SessionEvent.Type.CREATED, session.getSessionId(), clientId)
            .sourceNode(nodeId).build());
        return session;
    }

    @Override
    public void heartbeat(String sessionId) {
        Session s = sessions.get(sessionId);
        if (s == null) return;
        s.heartbeat();
        // ZooKeeper ExpiryQueue 设计：heartbeat 后重新入队，确保过期时间更新到队列
        // PriorityQueue 不支持 decrease-key，用 remove+offer 实现（O(log n)）
        synchronized (expiryQueue) {
            expiryQueue.remove(s);
            expiryQueue.offer(s);
        }
    }

    @Override
    public boolean reconnect(String sessionId, Channel newChannel) {
        Session session = sessions.get(sessionId);
        if (session == null || !session.isAlive()) return false;

        session.transitionTo(State.CONNECTED);
        session.heartbeat();
        sessionChannels.put(sessionId, newChannel);
        channelToSession.put(newChannel.id().asShortText(), sessionId);

        metrics.onSessionReconnected();
        log.info("Session {} reconnected", sessionId);
        eventBus.publish(SessionEvent.builder(SessionEvent.Type.RECONNECTED, sessionId, session.getClientId())
            .sourceNode(nodeId).build());
        return true;
    }

    @Override
    public void closeSession(String sessionId) {
        Session session = sessions.remove(sessionId);
        if (session == null) return;

        session.transitionTo(State.CLOSED);
        Channel ch = sessionChannels.remove(sessionId);
        if (ch != null) channelToSession.remove(ch.id().asShortText());

        zNodeStorage.cleanupSession(sessionId);
        metrics.onSessionClosed();
        log.info("Session closed: {}", session);
        eventBus.publish(SessionEvent.builder(SessionEvent.Type.CLOSED, sessionId, session.getClientId())
            .sourceNode(nodeId).build());
    }

    @Override
    public void onChannelDisconnected(Channel channel) {
        String sessionId = channelToSession.remove(channel.id().asShortText());
        if (sessionId == null) return;

        Session session = sessions.get(sessionId);
        if (session != null && session.isAlive()) {
            session.transitionTo(State.RECONNECTING);
            sessionChannels.remove(sessionId);
            log.info("Channel disconnected, session {} RECONNECTING", sessionId);
            eventBus.publish(SessionEvent.builder(SessionEvent.Type.DISCONNECTED, sessionId, session.getClientId())
                .sourceNode(nodeId).build());
        }
    }

    @Override public Channel getChannel(String sessionId)  { return sessionChannels.get(sessionId); }
    @Override public String  getSessionId(Channel channel) { return channelToSession.get(channel.id().asShortText()); }
    @Override public int     activeSessionCount()          { return sessions.size(); }

    @Override
    public void shutdown() {
        scanner.shutdownNow();
        log.info("SessionManager shutdown");
    }

    private void scanExpiredSessions() {
        // 只检查队头（最早过期的），避免全表扫描
        long now = System.currentTimeMillis();
        while (true) {
            Session session;
            synchronized (expiryQueue) {
                session = expiryQueue.peek();
                if (session == null || session.getExpireTime() > now) break;
                expiryQueue.poll();
            }
            // 会话可能已被 heartbeat 续期或已关闭，需二次确认
            if (sessions.containsKey(session.getSessionId()) && session.isExpired()) {
                expireSession(session);
            }
        }
    }

    private void expireSession(Session session) {
        String sessionId = session.getSessionId();
        sessions.remove(sessionId);
        Channel ch = sessionChannels.remove(sessionId);
        if (ch != null) channelToSession.remove(ch.id().asShortText()); // 防止内存泄漏
        session.transitionTo(State.EXPIRED);

        metrics.onSessionExpired();
        log.warn("Session expired: {}", session);
        eventBus.publish(SessionEvent.builder(SessionEvent.Type.EXPIRED, sessionId, session.getClientId())
            .sourceNode(nodeId).build());

        zNodeStorage.cleanupSession(sessionId);

        if (ch != null && ch.isActive()) {
            WatchEvent event = new WatchEvent(WatchEvent.Type.SESSION_EXPIRED, ZNodePath.ROOT);
            ch.writeAndFlush(event.serialize() + "\n");
        }
    }
}
