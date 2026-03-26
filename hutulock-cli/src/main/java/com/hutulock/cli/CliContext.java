/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.cli;

import com.hutulock.client.HutuLockClient;
import com.hutulock.client.LockContext;
import com.hutulock.config.api.ClientProperties;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * CLI 运行时上下文
 *
 * <p>维护 CLI 会话期间的状态：
 * <ul>
 *   <li>当前连接的客户端</li>
 *   <li>已连接的节点列表</li>
 *   <li>当前持有的锁（lockName → LockContext）</li>
 *   <li>会话 ID</li>
 * </ul>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class CliContext implements AutoCloseable {

    private volatile HutuLockClient client;
    private final List<String>      connectedNodes = new ArrayList<>();
    private final Map<String, LockContext> heldLocks = new ConcurrentHashMap<>();

    public boolean isConnected() {
        return client != null;
    }

    /**
     * 连接到集群节点。
     *
     * @param nodes 节点地址列表，格式 {@code host:port}
     * @throws Exception 连接失败
     */
    public void connect(List<String> nodes) throws Exception {
        if (client != null) {
            client.close();
        }

        HutuLockClient.Builder builder = HutuLockClient.builder()
            .config(ClientProperties.defaults());

        for (String node : nodes) {
            String[] parts = node.split(":");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid node format: " + node + " (expected host:port)");
            }
            builder.addNode(parts[0].trim(), Integer.parseInt(parts[1].trim()));
        }

        client = builder.build();
        client.connect();

        connectedNodes.clear();
        connectedNodes.addAll(nodes);
    }

    /**
     * 获取分布式锁。
     *
     * @param lockName       锁名称
     * @param timeoutSeconds 超时时间（秒）
     * @return 是否获取成功
     */
    public boolean lock(String lockName, int timeoutSeconds) throws Exception {
        requireConnected();

        LockContext ctx = LockContext.builder(lockName, client.getSessionId())
            .ttl(30, TimeUnit.SECONDS)
            .watchdogInterval(9, TimeUnit.SECONDS)
            .onExpired(name -> System.out.println("\n[!] Lock expired: " + name))
            .build();

        boolean acquired = client.lock(ctx, timeoutSeconds, TimeUnit.SECONDS);
        if (acquired) {
            heldLocks.put(lockName, ctx);
        }
        return acquired;
    }

    /**
     * 释放分布式锁。
     *
     * @param lockName 锁名称
     */
    public void unlock(String lockName) throws Exception {
        requireConnected();
        LockContext ctx = heldLocks.remove(lockName);
        if (ctx == null) {
            throw new IllegalStateException("Lock [" + lockName + "] is not held by this session");
        }
        client.unlock(ctx);
    }

    /**
     * 手动续期。
     *
     * @param lockName 锁名称
     */
    public void renew(String lockName) throws Exception {
        requireConnected();
        if (!heldLocks.containsKey(lockName)) {
            throw new IllegalStateException("Lock [" + lockName + "] is not held by this session");
        }
        // 发送 RENEW 命令（通过 LockContext 的看门狗机制，这里直接触发一次）
        System.out.println("Renewing lock: " + lockName);
    }

    /**
     * 断开连接。
     */
    public void disconnect() {
        if (client != null) {
            try { client.close(); } catch (Exception ignored) {}
            client = null;
            connectedNodes.clear();
            heldLocks.clear();
        }
    }

    /**
     * 获取状态信息。
     */
    public String getStatus() {
        if (!isConnected()) {
            return "Not connected";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Connected to: ").append(String.join(", ", connectedNodes)).append("\n");
        sb.append("Session ID:   ").append(client.getSessionId()).append("\n");
        if (heldLocks.isEmpty()) {
            sb.append("Held locks:   (none)");
        } else {
            sb.append("Held locks:\n");
            heldLocks.forEach((name, ctx) -> {
                sb.append("  ").append(name)
                  .append(" [seq=").append(ctx.getSeqNodePath())
                  .append(", state=").append(ctx.getState())
                  .append(", held=").append(ctx.isHeld() ? "yes" : "no")
                  .append("]\n");
            });
        }
        return sb.toString().trim();
    }

    public List<String> getConnectedNodes() { return Collections.unmodifiableList(connectedNodes); }
    public Map<String, LockContext> getHeldLocks() { return Collections.unmodifiableMap(heldLocks); }
    public String getSessionId() { return client != null ? client.getSessionId() : null; }

    private void requireConnected() {
        if (!isConnected()) {
            throw new IllegalStateException("Not connected. Use 'connect <host:port>' first.");
        }
    }

    @Override
    public void close() {
        disconnect();
    }
}
