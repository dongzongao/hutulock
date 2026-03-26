/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.spi.session;

import com.hutulock.model.session.Session;
import io.netty.channel.Channel;

/**
 * 会话追踪器接口（SPI 边界契约）
 *
 * <p>负责会话的完整生命周期管理。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public interface SessionTracker {

    Session createSession(String clientId, Channel channel);
    Session createSession(String clientId, Channel channel, long timeoutMs);
    void heartbeat(String sessionId);
    boolean reconnect(String sessionId, Channel newChannel);
    void closeSession(String sessionId);
    void onChannelDisconnected(Channel channel);
    Channel getChannel(String sessionId);
    String getSessionId(Channel channel);
    int activeSessionCount();
    void shutdown();
}
