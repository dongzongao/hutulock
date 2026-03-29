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
package com.hutulock.client;

import com.hutulock.model.exception.HutuLockException;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 客户端锁上下文（Builder 模式）
 *
 * <p>封装单把锁的完整生命周期：
 * <ul>
 *   <li>锁名、sessionId、TTL 配置</li>
 *   <li>看门狗（定时心跳续期，防止服务端 TTL 过期）</li>
 *   <li>锁状态机（IDLE → HELD → RELEASED / EXPIRED）</li>
 *   <li>过期回调（业务方可注册，用于主动放弃临界区）</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 *   LockContext ctx = LockContext.builder("order-lock", sessionId)
 *       .ttl(30, TimeUnit.SECONDS)
 *       .watchdogInterval(10, TimeUnit.SECONDS)
 *       .onExpired(name -> abortCriticalSection())
 *       .build();
 *
 *   client.lock(ctx);
 *   try { ... } finally { client.unlock(ctx); }
 * }</pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class LockContext {

    private static final Logger log = LoggerFactory.getLogger(LockContext.class);

    /** 锁状态 */
    public enum State { IDLE, HELD, EXPIRED, RELEASED }

    // ---- 配置（不可变） ----
    private final String lockName;
    private final String sessionId;
    private final long   ttlMs;
    private final long   watchdogIntervalMs;
    private final Consumer<String> onExpiredCallback;
    private final ScheduledExecutorService scheduler;

    // ---- 运行时状态 ----
    private volatile State  state = State.IDLE;
    /** 服务端分配的顺序节点路径，如 {@code /locks/order-lock/seq-0000000001} */
    private volatile String seqNodePath;
    private volatile ScheduledFuture<?> watchdogTask;

    private LockContext(Builder b) {
        this.lockName           = b.lockName;
        this.sessionId          = b.sessionId;
        this.ttlMs              = b.ttlMs;
        this.watchdogIntervalMs = b.watchdogIntervalMs;
        this.onExpiredCallback  = b.onExpiredCallback;
        this.scheduler          = b.scheduler;
    }

    // ==================== 看门狗 ====================

    /**
     * 获锁成功后启动看门狗，定时向服务端发送 RENEW 心跳。
     *
     * @param channel 当前连接的 Netty Channel
     * @throws HutuLockException 若当前状态不允许转换到 HELD
     */
    void startWatchdog(Channel channel) {
        LockStateMachine.INSTANCE.transit(state, State.HELD);
        state = State.HELD;
        watchdogTask = scheduler.scheduleAtFixedRate(() -> {
            if (state != State.HELD) { stopWatchdog(); return; }
            if (channel.isActive()) {
                channel.writeAndFlush("RENEW " + lockName + " " + sessionId + "\n");
            } else {
                markExpired(); // 连接断开，触发过期回调
            }
        }, watchdogIntervalMs, watchdogIntervalMs, TimeUnit.MILLISECONDS);

        log.debug("Watchdog started: lock={}, interval={}ms", lockName, watchdogIntervalMs);
    }

    /** 停止看门狗（unlock 时调用）。 */
    void stopWatchdog() {
        ScheduledFuture<?> task = watchdogTask;
        if (task != null) { task.cancel(false); watchdogTask = null; }
    }

    /** 标记锁已过期，触发 onExpired 回调。 */
    void markExpired() {
        if (!LockStateMachine.INSTANCE.canTransit(state, State.EXPIRED)) return;
        state = State.EXPIRED;
        stopWatchdog();
        log.warn("Lock [{}] expired for session {}", lockName, sessionId);
        if (onExpiredCallback != null) onExpiredCallback.accept(lockName);
    }

    /** 标记锁已正常释放。 */
    void markReleased() {
        if (!LockStateMachine.INSTANCE.canTransit(state, State.RELEASED)) return;
        state = State.RELEASED;
        stopWatchdog();
    }

    // ==================== 访问器 ====================

    public String getLockName()    { return lockName;    }
    public String getSessionId()   { return sessionId;   }
    public long   getTtlMs()       { return ttlMs;       }
    public State  getState()       { return state;       }
    public boolean isHeld()        { return state == State.HELD; }
    public String getSeqNodePath() { return seqNodePath; }
    void   setSeqNodePath(String p){ this.seqNodePath = p; }

    @Override
    public String toString() {
        return String.format("LockContext{lock=%s, session=%s, state=%s, seq=%s}",
            lockName, sessionId, state, seqNodePath);
    }

    // ==================== Builder ====================

    public static Builder builder(String lockName, String sessionId) {
        return new Builder(lockName, sessionId);
    }

    /** LockContext 构建器 */
    public static final class Builder {
        private final String lockName;
        private final String sessionId;
        private long   ttlMs              = 30_000;
        /** -1 表示自动推导为 ttl/4，消除与 ttl 的同步隐患 */
        private long   watchdogIntervalMs = -1;
        private Consumer<String> onExpiredCallback;
        private ScheduledExecutorService scheduler;

        private Builder(String lockName, String sessionId) {
            this.lockName  = lockName;
            this.sessionId = sessionId;
        }

        /** 锁的 TTL，超过此时间无心跳则服务端强制释放。 */
        public Builder ttl(long duration, TimeUnit unit) {
            this.ttlMs = unit.toMillis(duration); return this;
        }

        /** 看门狗心跳间隔，建议 &lt; TTL/3。不设置则自动取 TTL/4。 */
        public Builder watchdogInterval(long duration, TimeUnit unit) {
            this.watchdogIntervalMs = unit.toMillis(duration); return this;
        }

        /** 锁过期时的回调（业务方可在此主动放弃临界区）。 */
        public Builder onExpired(Consumer<String> callback) {
            this.onExpiredCallback = callback; return this;
        }

        /** 共享调度器（不传则自建独立调度器）。 */
        public Builder scheduler(ScheduledExecutorService scheduler) {
            this.scheduler = scheduler; return this;
        }

        public LockContext build() {
            long effectiveInterval = watchdogIntervalMs < 0
                ? ttlMs / 4
                : watchdogIntervalMs;
            if (effectiveInterval >= ttlMs / 3) {
                throw new IllegalArgumentException(
                    "watchdogInterval (" + effectiveInterval + "ms) must be < ttl/3 ("
                    + ttlMs / 3 + "ms) to ensure renewal before expiry");
            }
            this.watchdogIntervalMs = effectiveInterval;
            if (scheduler == null) {
                scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "hutulock-watchdog-" + lockName);
                    t.setDaemon(true);
                    return t;
                });
            }
            return new LockContext(this);
        }
    }
}
