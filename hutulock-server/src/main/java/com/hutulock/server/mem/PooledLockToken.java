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
package com.hutulock.server.mem;

import com.hutulock.spi.lock.LockToken;

/**
 * 可池化的 LockToken 包装器（组合模式）
 *
 * <p>{@link LockToken} 是 final 类，无法继承，改用组合：
 * 持有一个可复用的 {@link LockToken} 引用，通过 {@link #init} 重新赋值。
 *
 * <p>使用方式：
 * <pre>{@code
 *   PooledLockToken wrapper = pool.borrow();
 *   wrapper.init(lockName, seqNodePath, sessionId);
 *   LockToken token = wrapper.token();   // 对外暴露 LockToken
 *   // ...
 *   pool.release(wrapper);               // 自动调用 reset()
 * }</pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class PooledLockToken implements ObjectPool.Pooled {

    private String lockName;
    private String seqNodePath;
    private String sessionId;
    private long   acquiredAt;

    /** 无参构造，供对象池预分配使用。 */
    public PooledLockToken() {}

    /**
     * 初始化（借出后调用，替代构造函数）。
     */
    public PooledLockToken init(String lockName, String seqNodePath, String sessionId) {
        this.lockName    = lockName;
        this.seqNodePath = seqNodePath;
        this.sessionId   = sessionId;
        this.acquiredAt  = System.currentTimeMillis();
        return this;
    }

    /**
     * 构建 {@link LockToken}（对外暴露标准接口）。
     * 注意：每次调用仍会 new 一个 LockToken，但 PooledLockToken 本身被复用，
     * 减少了包装对象的分配。如需完全零分配，可直接使用 getter。
     */
    public LockToken token() {
        return new LockToken(lockName, seqNodePath, sessionId);
    }

    public String getLockName()    { return lockName;    }
    public String getSeqNodePath() { return seqNodePath; }
    public String getSessionId()   { return sessionId;   }
    public long   getAcquiredAt()  { return acquiredAt;  }
    public long   heldMillis()     { return System.currentTimeMillis() - acquiredAt; }

    /** 归还前清理状态。 */
    @Override
    public void reset() {
        lockName    = null;
        seqNodePath = null;
        sessionId   = null;
        acquiredAt  = 0;
    }

    @Override
    public String toString() {
        return String.format("PooledLockToken{lock=%s, seq=%s, session=%s}",
            lockName, seqNodePath, sessionId);
    }
}
