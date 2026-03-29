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
package com.hutulock.server.raft;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.ChannelFutureListener;
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

    /**
     * 流控标志：上一条 AppendEntries 尚未收到 ack 时为 true。
     *
     * <p>防止对慢速 Follower 无限堆积发送，避免内存和网络资源浪费。
     * ack 回来（{@code handleAppendResp}）后由 Leader 清除此标志并立即补发。
     */
    public volatile boolean inFlight = false;

    /**
     * 快速回退（Fast Backup）：Follower 拒绝时携带的冲突 term。
     * -1 表示无冲突信息（旧协议兼容）。
     */
    public volatile int conflictTerm  = -1;
    /**
     * 快速回退：冲突 term 在 Follower 日志中的第一条索引。
     * Leader 可直接跳到此位置，避免逐条回退。
     */
    public volatile int conflictIndex = -1;

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

    /** 发送消息，若连接不可用则记录 warn 并丢弃（避免静默失败）。 */
    public void send(String msg) {
        Channel ch = channel;  // 本地变量，避免 TOCTOU：检查后 channel 被置 null
        if (ch == null || !ch.isActive()) {
            log.debug("Raft [{}] drop message to peer {} (not connected): {}",
                owner.getNodeId(), nodeId, msg.split(" ", 2)[0]);
            return;
        }
        ch.writeAndFlush(msg + "\n").addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess() && !(f.cause() instanceof java.nio.channels.ClosedChannelException)) {
                // 忽略 ClosedChannelException：channel 在发送途中关闭是正常的断线场景
                log.warn("Raft [{}] failed to send to peer {}: {}", owner.getNodeId(), nodeId,
                    f.cause() != null ? f.cause().getMessage() : "unknown");
            }
        });
    }
}
