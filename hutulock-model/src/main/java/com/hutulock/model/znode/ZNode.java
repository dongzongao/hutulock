/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.model.znode;

/**
 * ZNode 数据节点（值对象）
 *
 * <p>包含路径、类型、数据和元数据（Stat）。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class ZNode {

    private final ZNodePath path;
    private final ZNodeType type;
    private volatile byte[] data;
    private final long      createTime;
    private volatile long   modifyTime;
    private volatile int    version;
    private final String    sessionId;
    private final int       sequenceNum;

    private ZNode(Builder b) {
        this.path        = b.path;
        this.type        = b.type;
        this.data        = b.data;
        this.sessionId   = b.sessionId;
        this.sequenceNum = b.sequenceNum;
        this.createTime  = System.currentTimeMillis();
        this.modifyTime  = this.createTime;
        this.version     = 0;
    }

    public synchronized void setData(byte[] data) {
        this.data       = data;
        this.modifyTime = System.currentTimeMillis();
        this.version++;
    }

    public ZNodePath getPath()       { return path;        }
    public ZNodeType getType()       { return type;        }
    public byte[]    getData()       { return data;        }
    public String    getSessionId()  { return sessionId;   }
    public int       getSequenceNum(){ return sequenceNum; }
    public long      getCreateTime() { return createTime;  }
    public long      getModifyTime() { return modifyTime;  }
    public int       getVersion()    { return version;     }

    public boolean isEphemeral()   { return type == ZNodeType.EPHEMERAL || type == ZNodeType.EPHEMERAL_SEQ; }
    public boolean isSequential()  { return type == ZNodeType.PERSISTENT_SEQ || type == ZNodeType.EPHEMERAL_SEQ; }

    @Override
    public String toString() {
        return String.format("ZNode{path=%s, type=%s, session=%s, seq=%d, ver=%d}",
            path, type, sessionId, sequenceNum, version);
    }

    public static Builder builder(ZNodePath path, ZNodeType type) { return new Builder(path, type); }

    public static final class Builder {
        private final ZNodePath path;
        private final ZNodeType type;
        private byte[] data        = new byte[0];
        private String sessionId   = null;
        private int    sequenceNum = -1;

        private Builder(ZNodePath path, ZNodeType type) { this.path = path; this.type = type; }

        public Builder data(byte[] data)     { this.data        = data;            return this; }
        public Builder data(String data)     { this.data        = data.getBytes(); return this; }
        public Builder sessionId(String sid) { this.sessionId   = sid;             return this; }
        public Builder sequenceNum(int num)  { this.sequenceNum = num;             return this; }

        public ZNode build() { return new ZNode(this); }
    }
}
