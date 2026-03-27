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
package com.hutulock.spi.event;

/**
 * 会话生命周期事件
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class SessionEvent extends HutuEvent {

    public enum Type { CREATED, DISCONNECTED, RECONNECTED, EXPIRED, CLOSED }

    private final Type   type;
    private final String sessionId;
    private final String clientId;

    private SessionEvent(Builder b) {
        super(b.sourceNodeId);
        this.type      = b.type;
        this.sessionId = b.sessionId;
        this.clientId  = b.clientId;
    }

    @Override public String getEventType() { return "SESSION_" + type.name(); }

    public Type   getType()      { return type;      }
    public String getSessionId() { return sessionId; }
    public String getClientId()  { return clientId;  }

    public static Builder builder(Type type, String sessionId, String clientId) {
        return new Builder(type, sessionId, clientId);
    }

    public static final class Builder {
        private final Type   type;
        private final String sessionId;
        private final String clientId;
        private String sourceNodeId = "unknown";

        private Builder(Type type, String sessionId, String clientId) {
            this.type = type; this.sessionId = sessionId; this.clientId = clientId;
        }

        public Builder sourceNode(String n) { sourceNodeId = n; return this; }
        public SessionEvent build()         { return new SessionEvent(this); }
    }
}
