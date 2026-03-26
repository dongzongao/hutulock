/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server.impl;

import com.hutulock.model.exception.ErrorCode;
import com.hutulock.model.exception.HutuLockException;
import com.hutulock.model.watcher.WatchEvent;
import com.hutulock.model.znode.ZNode;
import com.hutulock.model.znode.ZNodePath;
import com.hutulock.model.znode.ZNodeType;
import com.hutulock.spi.event.EventBus;
import com.hutulock.spi.event.ZNodeEvent;
import com.hutulock.spi.metrics.MetricsCollector;
import com.hutulock.spi.storage.WatcherRegistry;
import com.hutulock.spi.storage.ZNodeStorage;
import com.hutulock.server.mem.MemoryManager;
import com.hutulock.server.mem.ZNodePathCache;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * ZNode 树形存储默认实现（内存存储）
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class DefaultZNodeTree implements ZNodeStorage {

    private static final Logger log = LoggerFactory.getLogger(DefaultZNodeTree.class);

    private final Map<String, ZNode>         nodes       = new ConcurrentHashMap<>();
    /** 父路径 → 子路径集合（有序：按 seqNum 升序，非顺序节点排最后） */
    private final Map<String, Set<String>>   children    = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> seqCounters = new ConcurrentHashMap<>();

    private final WatcherRegistry  watcherRegistry;
    private final MetricsCollector metrics;
    private final EventBus         eventBus;
    private final ZNodePathCache   pathCache;
    private String nodeId = "unknown";

    public DefaultZNodeTree(WatcherRegistry watcherRegistry, MetricsCollector metrics,
                             EventBus eventBus, MemoryManager memoryManager) {
        this.watcherRegistry = watcherRegistry;
        this.metrics         = metrics;
        this.eventBus        = eventBus;
        this.pathCache       = memoryManager.getPathCache();
        nodes.put("/", ZNode.builder(ZNodePath.ROOT, ZNodeType.PERSISTENT).build());
        children.put("/", ConcurrentHashMap.newKeySet());
    }

    /** 兼容构造（测试用，不使用路径缓存）。 */
    public DefaultZNodeTree(WatcherRegistry watcherRegistry, MetricsCollector metrics, EventBus eventBus) {
        this.watcherRegistry = watcherRegistry;
        this.metrics         = metrics;
        this.eventBus        = eventBus;
        this.pathCache       = new ZNodePathCache();
        nodes.put("/", ZNode.builder(ZNodePath.ROOT, ZNodeType.PERSISTENT).build());
        children.put("/", ConcurrentHashMap.newKeySet());
    }

    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    @Override
    public synchronized ZNodePath create(ZNodePath path, ZNodeType type, byte[] data, String sessionId) {
        ZNodePath parent = path.parent();
        if (!nodes.containsKey(parent.value())) {
            throw new HutuLockException(ErrorCode.PARENT_NOT_FOUND, "parent not found: " + parent);
        }

        ZNodePath actualPath = path;
        int seqNum = -1;
        if (type == ZNodeType.EPHEMERAL_SEQ || type == ZNodeType.PERSISTENT_SEQ) {
            seqNum = seqCounters.computeIfAbsent(parent.value(), k -> new AtomicInteger(0)).incrementAndGet();
            // 使用预计算序号字符串，避免 String.format
            actualPath = pathCache.get(path.value() + ZNodePathCache.formatSeq(seqNum));
        }

        if (nodes.containsKey(actualPath.value())) {
            throw new HutuLockException(ErrorCode.NODE_ALREADY_EXISTS, "node exists: " + actualPath);
        }

        ZNode node = ZNode.builder(actualPath, type).data(data).sessionId(sessionId).sequenceNum(seqNum).build();
        nodes.put(actualPath.value(), node);
        children.computeIfAbsent(parent.value(), k -> ConcurrentHashMap.newKeySet()).add(actualPath.value());

        metrics.onZNodeCreated();
        log.debug("ZNode created: {}", node);

        eventBus.publish(ZNodeEvent.builder(ZNodeEvent.Type.CREATED, actualPath, nodeId)
            .nodeType(type).sessionId(sessionId).build());
        watcherRegistry.fire(actualPath, WatchEvent.Type.NODE_CREATED);
        watcherRegistry.fire(parent, WatchEvent.Type.NODE_DATA_CHANGED);

        return actualPath;
    }

    @Override
    public synchronized void delete(ZNodePath path) {
        ZNode node = nodes.remove(path.value());
        if (node == null) { log.warn("Delete non-existent node: {}", path); return; }

        ZNodePath parent = path.parent();
        Set<String> pc = children.get(parent.value());
        if (pc != null) pc.remove(path.value());

        // 顺序节点删除后 evict 路径缓存，防止内存泄漏
        if (node.isEphemeral() || node.isSequential()) {
            pathCache.evict(path.value());
        }

        metrics.onZNodeDeleted();
        log.debug("ZNode deleted: {}", path);

        eventBus.publish(ZNodeEvent.builder(ZNodeEvent.Type.DELETED, path, nodeId).build());
        watcherRegistry.fire(path, WatchEvent.Type.NODE_DELETED);
    }

    @Override
    public synchronized void setData(ZNodePath path, byte[] data, int version) {
        ZNode node = nodes.get(path.value());
        if (node == null) throw new HutuLockException(ErrorCode.NODE_NOT_FOUND, "node not found: " + path);
        if (version != -1 && node.getVersion() != version)
            throw new HutuLockException(ErrorCode.VERSION_MISMATCH, "version mismatch");
        node.setData(data);
        watcherRegistry.fire(path, WatchEvent.Type.NODE_DATA_CHANGED);
    }

    @Override public ZNode    get(ZNodePath path)    { return nodes.get(path.value()); }
    @Override public boolean  exists(ZNodePath path) { return nodes.containsKey(path.value()); }

    @Override
    public List<ZNodePath> getChildren(ZNodePath path) {
        Set<String> cp = children.get(path.value());
        if (cp == null || cp.isEmpty()) return Collections.emptyList();
        // 直接构建列表，按 seqNum 排序（顺序节点路径字典序 = seqNum 升序）
        List<String> sorted = new ArrayList<>(cp);
        sorted.sort(Comparator.naturalOrder()); // seq-0000000001 字典序即数值序
        List<ZNodePath> result = new ArrayList<>(sorted.size());
        for (String s : sorted) result.add(pathCache.get(s));
        return result;
    }

    @Override
    public void watch(ZNodePath path, Channel channel) { watcherRegistry.register(path, channel); }

    @Override
    public synchronized List<ZNodePath> cleanupSession(String sessionId) {
        List<ZNodePath> deleted = new ArrayList<>();
        for (ZNode node : new ArrayList<>(nodes.values())) {
            if (node.isEphemeral() && sessionId.equals(node.getSessionId())) {
                delete(node.getPath());
                deleted.add(node.getPath());
            }
        }
        if (!deleted.isEmpty()) log.info("Session {} expired, deleted {} nodes", sessionId, deleted.size());
        return deleted;
    }

    @Override public int size() { return nodes.size(); }
}
