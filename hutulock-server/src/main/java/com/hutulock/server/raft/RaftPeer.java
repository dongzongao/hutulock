/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server.raft;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Raft 集群中的远端节点连接
 *
 * <p>维护到远端节点的 Netty TCP 连接，断线后自动重连。
 * Leader 通过此类向 Follower 发送 AppendEntries 和 RequestVote RPC。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class RaftPeer {

    private static final Logger log = LoggerFactory.getLogger(RaftPeer.class);

    public final String nodeId;
    public final String host;
    public final int    port;

    /** Leader 维护的 nextIndex（下一条要发送的日志索引） */
    public volatile int nextIndex  = 1;
    /** Leader 维护的 matchIndex（已确认复制的最高日志索引） */
    public volatile int matchIndex = 0;

    private volatile Channel     channel;
    private final EventLoopGroup group;
    private final RaftNode       owner;

    public RaftPeer(String nodeId, String host, int port, RaftNode owner, EventLoopGroup group) {
        this.nodeId = nodeId;
        this.host   = host;
        this.port   = port;
        this.owner  = owner;
        this.group  = group;
    }

    /** 建立连接，断线后自动重连（2s 间隔）。 */
    public void connect() {
        new Bootstrap()
            .group(group)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override protected void initChannel(SocketChannel ch) {
                    ch.pipeline()
                      .addLast(new LineBasedFrameDecoder(4096))
                      .addLast(new StringDecoder(CharsetUtil.UTF_8))
                      .addLast(new StringEncoder(CharsetUtil.UTF_8))
                      .addLast(new RaftPeerHandler(owner));
                }
            })
            .connect(host, port)
            .addListener((ChannelFuture f) -> {
                if (f.isSuccess()) {
                    channel = f.channel();
                    log.info("Raft [{}] connected to peer {}", owner.getNodeId(), nodeId);
                    channel.closeFuture().addListener(cf ->
                        group.schedule(this::connect, 2, TimeUnit.SECONDS));
                } else {
                    log.debug("Raft [{}] failed to connect to {}, retry in 2s", owner.getNodeId(), nodeId);
                    group.schedule(this::connect, 2, TimeUnit.SECONDS);
                }
            });
    }

    /** 发送消息，若连接不可用则静默丢弃。 */
    public void send(String msg) {
        Channel ch = channel;
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(msg + "\n");
        }
    }
}
