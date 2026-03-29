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
import com.hutulock.model.util.Strings;
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
            Thread t = new Thread(r, Strings.THREAD_SESSION_SCANNER);
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
        // remove+offer 必须与 scanExpiredSessions 的 peek+poll 用同一把锁，
        // 否则 scan 可能在 remove 后、offer 前 poll 到已续期的 session 并错误过期
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

    /** 返回所有活跃会话的快照列表（供 Admin 控制台读取）。 */
    public java.util.List<com.hutulock.model.session.Session> listSessions() {
        return new java.util.ArrayList<>(sessions.values());
    }

    @Override
    public void shutdown() {
        // 先停止扫描，等待当前扫描任务完成，避免关闭期间会话清理被中断
        scanner.shutdown();
        try {
            if (!scanner.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) {
                scanner.shutdownNow();
            }
        } catch (InterruptedException e) {
            scanner.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("SessionManager shutdown");
    }

    private void scanExpiredSessions() {
        long now = System.currentTimeMillis();
        while (true) {
            Session session;
            synchronized (expiryQueue) {
                session = expiryQueue.peek();
                if (session == null || session.getExpireTime() > now) break;
                expiryQueue.poll();
            }
            // 二次确认：heartbeat 可能在 poll 后已续期（session.isExpired() 会重新检查时间戳）
            // sessions.containsKey 确认未被 closeSession 提前移除
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
            ch.writeAndFlush(event.serializeWithNewline());
        }
    }
}
