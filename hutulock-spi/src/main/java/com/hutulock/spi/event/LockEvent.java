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
 * 锁生命周期事件
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class LockEvent extends HutuEvent {

    public enum Type { ACQUIRED, ACQUIRED_QUEUED, WAITING, RELEASED, EXPIRED }

    private final Type   type;
    private final String lockName;
    private final String sessionId;
    private final String seqNodePath;
    private final long   heldDurationMs;

    private LockEvent(Builder b) {
        super(b.sourceNodeId);
        this.type           = b.type;
        this.lockName       = b.lockName;
        this.sessionId      = b.sessionId;
        this.seqNodePath    = b.seqNodePath;
        this.heldDurationMs = b.heldDurationMs;
    }

    @Override public String getEventType() { return "LOCK_" + type.name(); }

    public Type   getType()           { return type;           }
    public String getLockName()       { return lockName;       }
    public String getSessionId()      { return sessionId;      }
    public String getSeqNodePath()    { return seqNodePath;    }
    public long   getHeldDurationMs() { return heldDurationMs; }

    public static Builder builder(Type type, String lockName, String sessionId) {
        return new Builder(type, lockName, sessionId);
    }

    public static final class Builder {
        private final Type   type;
        private final String lockName;
        private final String sessionId;
        private String sourceNodeId   = "unknown";
        private String seqNodePath    = "";
        private long   heldDurationMs = -1;

        private Builder(Type type, String lockName, String sessionId) {
            this.type = type; this.lockName = lockName; this.sessionId = sessionId;
        }

        public Builder sourceNode(String n)  { sourceNodeId   = n;  return this; }
        public Builder seqNodePath(String p) { seqNodePath    = p;  return this; }
        public Builder heldDuration(long ms) { heldDurationMs = ms; return this; }
        public LockEvent build()             { return new LockEvent(this); }
    }
}
