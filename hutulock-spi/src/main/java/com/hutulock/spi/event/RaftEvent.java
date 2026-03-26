/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.spi.event;

/**
 * Raft 共识层事件
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class RaftEvent extends HutuEvent {

    public enum Type { ELECTION_STARTED, BECAME_LEADER, STEPPED_DOWN, LOG_COMMITTED, PROPOSE_TIMEOUT }

    private final Type   type;
    private final int    term;
    private final String leaderId;
    private final int    commitIndex;

    private RaftEvent(Builder b) {
        super(b.sourceNodeId);
        this.type        = b.type;
        this.term        = b.term;
        this.leaderId    = b.leaderId;
        this.commitIndex = b.commitIndex;
    }

    @Override public String getEventType() { return "RAFT_" + type.name(); }

    public Type   getType()        { return type;        }
    public int    getTerm()        { return term;        }
    public String getLeaderId()    { return leaderId;    }
    public int    getCommitIndex() { return commitIndex; }

    public static Builder builder(Type type, String sourceNodeId, int term) {
        return new Builder(type, sourceNodeId, term);
    }

    public static final class Builder {
        private final Type   type;
        private final String sourceNodeId;
        private final int    term;
        private String leaderId    = null;
        private int    commitIndex = -1;

        private Builder(Type type, String sourceNodeId, int term) {
            this.type = type; this.sourceNodeId = sourceNodeId; this.term = term;
        }

        public Builder leaderId(String id)    { leaderId    = id;    return this; }
        public Builder commitIndex(int index) { commitIndex = index; return this; }
        public RaftEvent build()              { return new RaftEvent(this); }
    }
}
