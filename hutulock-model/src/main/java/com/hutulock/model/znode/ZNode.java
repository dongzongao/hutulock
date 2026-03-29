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

    /**
     * 创建时对应的 Raft logIndex（czxid，参考 ZooKeeper Stat.czxid）。
     *
     * <p>客户端可携带此值发起读请求，服务端确保 {@code lastApplied >= czxid}
     * 后再响应，实现线性一致读（避免读到旧 Leader 的过期数据）。
     * -1 表示未经 Raft 提交（本地直接创建，如 /locks 根节点）。
     */
    private final long czxid;

    /**
     * 最后修改时对应的 Raft logIndex（mzxid，参考 ZooKeeper Stat.mzxid）。
     * 初始值等于 czxid，每次 setData 后更新。
     */
    private volatile long mzxid;

    private ZNode(Builder b) {
        this.path        = b.path;
        this.type        = b.type;
        this.data        = b.data;
        this.sessionId   = b.sessionId;
        this.sequenceNum = b.sequenceNum;
        this.czxid       = b.czxid;
        this.mzxid       = b.czxid;
        this.createTime  = System.currentTimeMillis();
        this.modifyTime  = this.createTime;
        this.version     = 0;
    }

    public synchronized void setData(byte[] data) {
        this.data       = data;
        this.modifyTime = System.currentTimeMillis();
        this.version++;
    }

    /** 更新数据并记录修改时的 Raft logIndex（mzxid）。 */
    public synchronized void setData(byte[] data, long logIndex) {
        this.data       = data;
        this.modifyTime = System.currentTimeMillis();
        this.mzxid      = logIndex;
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
    /** 创建时的 Raft logIndex，用于线性一致读。 */
    public long      getCzxid()      { return czxid;       }
    /** 最后修改时的 Raft logIndex。 */
    public long      getMzxid()      { return mzxid;       }

    public boolean isEphemeral()   { return type == ZNodeType.EPHEMERAL || type == ZNodeType.EPHEMERAL_SEQ; }
    public boolean isSequential()  { return type == ZNodeType.PERSISTENT_SEQ || type == ZNodeType.EPHEMERAL_SEQ; }

    @Override
    public String toString() {
        return "ZNode{path=" + path + ", type=" + type + ", session=" + sessionId
            + ", seq=" + sequenceNum + ", ver=" + version + '}';
    }

    public static Builder builder(ZNodePath path, ZNodeType type) { return new Builder(path, type); }

    public static final class Builder {
        private final ZNodePath path;
        private final ZNodeType type;
        private byte[] data        = new byte[0];
        private String sessionId   = null;
        private int    sequenceNum = -1;
        private long   czxid       = -1;

        private Builder(ZNodePath path, ZNodeType type) { this.path = path; this.type = type; }

        public Builder data(byte[] data)     { this.data        = data;            return this; }
        public Builder data(String data)     { this.data        = data.getBytes(); return this; }
        public Builder sessionId(String sid) { this.sessionId   = sid;             return this; }
        public Builder sequenceNum(int num)  { this.sequenceNum = num;             return this; }
        /** 设置创建时的 Raft logIndex（czxid），用于线性一致读。 */
        public Builder czxid(long logIndex)  { this.czxid       = logIndex;        return this; }

        public ZNode build() { return new ZNode(this); }
    }
}
