/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server.mem;

import com.hutulock.spi.lock.LockToken;

/**
 * 可池化的 LockToken
 *
 * <p>继承 {@link LockToken} 并实现 {@link ObjectPool.Pooled}，
 * 支持通过 {@link ObjectPool} 复用，减少高频锁操作中的对象分配。
 *
 * <p>使用方式：
 * <pre>{@code
 *   PooledLockToken token = pool.borrow();
 *   token.init(lockName, seqNodePath, sessionId);
 *   try {
 *       // 使用 token
 *   } finally {
 *       pool.release(token);  // 自动调用 reset()
 *   }
 * }</pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class PooledLockToken extends LockToken implements ObjectPool.Pooled {

    // 可变字段，通过 init() 重新赋值
    private String mutableLockName;
    private String mutableSeqNodePath;
    private String mutableSessionId;
    private long   mutableAcquiredAt;

    /** 无参构造，供对象池预分配使用。 */
    public PooledLockToken() {
        super("__pool__", "__pool__", "__pool__");
    }

    /**
     * 初始化（借出后调用，替代构造函数）。
     */
    public PooledLockToken init(String lockName, String seqNodePath, String sessionId) {
        this.mutableLockName    = lockName;
        this.mutableSeqNodePath = seqNodePath;
        this.mutableSessionId   = sessionId;
        this.mutableAcquiredAt  = System.currentTimeMillis();
        return this;
    }

    @Override public String getLockName()    { return mutableLockName;    }
    @Override public String getSeqNodePath() { return mutableSeqNodePath; }
    @Override public String getSessionId()   { return mutableSessionId;   }
    @Override public long   getAcquiredAt()  { return mutableAcquiredAt;  }
    @Override public long   heldMillis()     { return System.currentTimeMillis() - mutableAcquiredAt; }

    /** 归还前清理状态。 */
    @Override
    public void reset() {
        mutableLockName    = null;
        mutableSeqNodePath = null;
        mutableSessionId   = null;
        mutableAcquiredAt  = 0;
    }

    @Override
    public String toString() {
        return String.format("PooledLockToken{lock=%s, seq=%s, session=%s}",
            mutableLockName, mutableSeqNodePath, mutableSessionId);
    }
}
