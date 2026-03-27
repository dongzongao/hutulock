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
package com.hutulock.spi.lock;

/**
 * 锁令牌（不可变值对象）
 *
 * <p>由 {@link LockService#tryAcquire} 返回，封装锁的持有信息，
 * 用于后续的 {@link LockService#release} 操作。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class LockToken {

    /** 锁名称，如 {@code order-lock} */
    private final String lockName;
    /** 服务端分配的顺序节点路径，如 {@code /locks/order-lock/seq-0000000001} */
    private final String seqNodePath;
    /** 持有此锁的会话 ID */
    private final String sessionId;
    /** 锁获取时间戳（毫秒） */
    private final long   acquiredAt;

    public LockToken(String lockName, String seqNodePath, String sessionId) {
        this.lockName    = lockName;
        this.seqNodePath = seqNodePath;
        this.sessionId   = sessionId;
        this.acquiredAt  = System.currentTimeMillis();
    }

    public String getLockName()    { return lockName;    }
    public String getSeqNodePath() { return seqNodePath; }
    public String getSessionId()   { return sessionId;   }
    public long   getAcquiredAt()  { return acquiredAt;  }
    public long   heldMillis()     { return System.currentTimeMillis() - acquiredAt; }

    @Override
    public String toString() {
        return String.format("LockToken{lock=%s, seq=%s, session=%s}", lockName, seqNodePath, sessionId);
    }
}
