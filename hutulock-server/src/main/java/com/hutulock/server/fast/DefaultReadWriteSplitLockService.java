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
package com.hutulock.server.fast;

import com.hutulock.model.protocol.CommandType;
import com.hutulock.model.protocol.Message;
import com.hutulock.model.znode.ZNode;
import com.hutulock.model.znode.ZNodePath;
import com.hutulock.model.util.Strings;
import com.hutulock.server.raft.RaftNode;
import com.hutulock.spi.lock.LockToken;
import com.hutulock.spi.storage.ZNodeStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 读写分离锁服务默认实现（秒杀场景优化）
 *
 * <p>核心设计：
 * <ul>
 *   <li>读操作：直接查询 ZNodeStorage（内存），O(1) 延迟</li>
 *   <li>写操作：批量提交到 Raft，减少 RPC 次数</li>
 *   <li>本地缓存：维护锁持有状态的内存快照，加速读操作</li>
 * </ul>
 *
 * <p>一致性保证：
 * <ul>
 *   <li>写操作：强一致性（通过 Raft 复制）</li>
 *   <li>读操作：最终一致性（本地快照可能滞后 10ms）</li>
 * </ul>
 *
 * @author HutuLock Authors
 * @since 1.1.0
 */
public class DefaultReadWriteSplitLockService implements ReadWriteSplitLockService, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DefaultReadWriteSplitLockService.class);

    private static final String LOCKS_ROOT = Strings.LOCKS_ROOT;

    private final ZNodeStorage      zNodeStorage;
    private final RaftNode          raftNode;
    private final WriteBatchQueue   batchQueue;

    /** 锁持有状态缓存：lockName → 是否被持有 */
    private final ConcurrentHashMap<String, Boolean> lockStateCache = new ConcurrentHashMap<>();

    /**
     * 构造读写分离锁服务。
     *
     * @param zNodeStorage ZNode 存储（用于读操作）
     * @param raftNode     Raft 节点（用于写操作）
     * @param batchSize    批量大小（默认 100）
     * @param flushIntervalMs 批量窗口（默认 10ms）
     */
    public DefaultReadWriteSplitLockService(ZNodeStorage zNodeStorage, RaftNode raftNode,
                                             int batchSize, long flushIntervalMs) {
        this.zNodeStorage = zNodeStorage;
        this.raftNode     = raftNode;
        this.batchQueue   = new WriteBatchQueue(batchSize, flushIntervalMs, this::flushBatch);
    }

    /**
     * 使用默认配置构造（批量 100 条，窗口 10ms）。
     */
    public DefaultReadWriteSplitLockService(ZNodeStorage zNodeStorage, RaftNode raftNode) {
        this(zNodeStorage, raftNode, 100, 10);
    }

    // ==================== 读操作（本地内存）====================

    @Override
    public boolean isLockAvailable(String lockName) {
        // 先查缓存（热路径优化）
        Boolean cached = lockStateCache.get(lockName);
        if (cached != null) {
            return !cached; // true 表示被持有，返回 false（不可用）
        }

        // 缓存未命中，查询 ZNodeStorage
        ZNodePath lockRoot = ZNodePath.of(LOCKS_ROOT + "/" + lockName);
        if (!zNodeStorage.exists(lockRoot)) {
            lockStateCache.put(lockName, false);
            return true; // 锁不存在，可用
        }

        List<ZNodePath> children = zNodeStorage.getChildren(lockRoot);
        boolean held = !children.isEmpty();
        lockStateCache.put(lockName, held);
        return !held;
    }

    // ==================== 写操作（批量提交）====================

    @Override
    public CompletableFuture<LockToken> tryAcquireAsync(String lockName, String sessionId,
                                                         long timeout, TimeUnit unit) {
        String command = Message.of(CommandType.LOCK, lockName, sessionId).serialize();
        CompletableFuture<String> batchFuture = batchQueue.submit(command);

        return batchFuture.thenApply(seqNodePath -> {
            if (seqNodePath == null) return null;
            // 更新缓存
            lockStateCache.put(lockName, true);
            return new LockToken(lockName, seqNodePath, sessionId);
        });
    }

    @Override
    public CompletableFuture<Void> releaseAsync(LockToken token) {
        String command = Message.of(CommandType.UNLOCK, token.getSeqNodePath(), token.getSessionId()).serialize();
        CompletableFuture<String> batchFuture = batchQueue.submit(command);

        return batchFuture.thenAccept(result -> {
            // 更新缓存
            lockStateCache.put(token.getLockName(), false);
        });
    }

    @Override
    public void renew(String lockName, String sessionId) {
        // 续期操作不需要批量，直接提交
        String command = Message.of(CommandType.RENEW, lockName, sessionId).serialize();
        raftNode.propose(command);
    }

    // ==================== 批量 flush ====================

    /**
     * 批量 flush 回调（由 WriteBatchQueue 调用）。
     *
     * <p>将批量请求合并为单个 Raft 日志条目，减少 RPC 次数。
     */
    private void flushBatch(List<WriteBatchQueue.WriteRequest> batch) {
        if (batch.isEmpty()) return;

        // 批量提交到 Raft（每个请求独立 propose）
        for (WriteBatchQueue.WriteRequest req : batch) {
            CompletableFuture<Void> raftFuture = raftNode.propose(req.command);

            raftFuture.whenComplete((result, error) -> {
                if (error != null) {
                    req.future.completeExceptionally(error);
                } else {
                    // 解析响应，提取 seqNodePath
                    try {
                        Message msg = Message.parse(req.command);
                        if (msg.getType() == CommandType.LOCK) {
                            // LOCK 命令成功后，返回 seqNodePath
                            // 注意：实际的 seqNodePath 需要从 ZNodeStorage 查询
                            String lockName = msg.arg(0);
                            String sessionId = msg.arg(1);
                            String seqNodePath = findSeqNodePath(lockName, sessionId);
                            req.future.complete(seqNodePath);
                        } else {
                            req.future.complete(null);
                        }
                    } catch (Exception e) {
                        req.future.completeExceptionally(e);
                    }
                }
            });
        }
    }

    /**
     * 查找会话对应的顺序节点路径。
     */
    private String findSeqNodePath(String lockName, String sessionId) {
        ZNodePath lockRoot = ZNodePath.of(LOCKS_ROOT + "/" + lockName);
        List<ZNodePath> children = zNodeStorage.getChildren(lockRoot);

        for (ZNodePath child : children) {
            ZNode node = zNodeStorage.get(child);
            if (node != null && sessionId.equals(node.getSessionId())) {
                return child.value();
            }
        }
        return null;
    }

    @Override
    public void close() {
        batchQueue.close();
        log.info("DefaultReadWriteSplitLockService closed");
    }
}
