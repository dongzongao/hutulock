/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server.raft;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Raft 节点间消息处理器
 *
 * <p>接收来自其他 Raft 节点的 RPC 消息，转发给 {@link RaftNode} 处理。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class RaftPeerHandler extends SimpleChannelInboundHandler<String> {

    private static final Logger log = LoggerFactory.getLogger(RaftPeerHandler.class);

    private final RaftNode node;

    public RaftPeerHandler(RaftNode node) {
        this.node = node;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) {
        node.handlePeerMessage(msg.trim(), ctx.channel());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // 连接断开时记录，RaftPeer.connect() 的 closeFuture 监听器负责重连
        log.warn("Raft peer connection closed: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Raft peer connection error from {}: {}", ctx.channel().remoteAddress(), cause.getMessage());
        // 关闭 channel，触发 closeFuture → RaftPeer 自动重连
        ctx.close();
    }
}
