/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.model.session;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 客户端会话（值对象）
 *
 * <p>会话独立于 TCP 连接，TCP 断开不等于会话过期。
 * 客户端可在 {@code sessionTimeout} 内重连并恢复会话。
 *
 * <p>状态机：CONNECTING → CONNECTED ⇄ RECONNECTING → EXPIRED / CLOSED
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class Session {

    public enum State { CONNECTING, CONNECTED, RECONNECTING, EXPIRED, CLOSED }

    private final String     sessionId;
    private final long       timeoutMs;
    private final AtomicLong lastHeartbeat = new AtomicLong(System.currentTimeMillis());
    private volatile State   state         = State.CONNECTING;
    private final String     clientId;

    public Session(String clientId, long timeoutMs) {
        this.sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.clientId  = clientId;
        this.timeoutMs = timeoutMs;
    }

    public void heartbeat() {
        if (state == State.CONNECTED || state == State.RECONNECTING) {
            lastHeartbeat.set(System.currentTimeMillis());
        }
    }

    public boolean isExpired() {
        return (state == State.CONNECTED || state == State.RECONNECTING)
            && System.currentTimeMillis() - lastHeartbeat.get() > timeoutMs;
    }

    public void transitionTo(State newState) { this.state = newState; }

    public String getSessionId()     { return sessionId;           }
    public String getClientId()      { return clientId;            }
    public long   getTimeoutMs()     { return timeoutMs;           }
    public State  getState()         { return state;               }
    public long   getLastHeartbeat() { return lastHeartbeat.get(); }
    /** 预计过期时间戳（毫秒），用于优先队列排序。 */
    public long   getExpireTime()    { return lastHeartbeat.get() + timeoutMs; }
    public boolean isAlive() {
        return state == State.CONNECTED || state == State.RECONNECTING;
    }

    @Override
    public String toString() {
        return String.format("Session{id=%s, client=%s, state=%s}", sessionId, clientId, state);
    }
}
