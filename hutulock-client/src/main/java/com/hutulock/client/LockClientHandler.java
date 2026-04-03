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

import com.hutulock.model.protocol.CommandType;
import com.hutulock.model.protocol.Message;
import com.hutulock.model.watcher.WatchEvent;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 客户端响应处理器（Netty ChannelHandler）
 *
 * <p>职责：
 * <ol>
 *   <li>将服务端响应路由到对应的 {@link CompletableFuture}（同步等待）</li>
 *   <li>处理 {@code WATCH_EVENT} 推送，触发注册的 Watcher 回调（异步事件驱动）</li>
 *   <li>处理 {@code REDIRECT}，通知上层重连</li>
 * </ol>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class LockClientHandler extends SimpleChannelInboundHandler<String> {

    private static final Logger log = LoggerFactory.getLogger(LockClientHandler.class);

    /** 请求 key → pending future。key 格式：{@code TYPE:lockName} 或 {@code TYPE:seqPath} */
    private final Map<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    /** 记录请求注册顺序，用于 ERROR 响应的兜底匹配。 */
    private final Queue<String> pendingOrder = new ConcurrentLinkedQueue<>();

    /** seqNodePath → Watcher 回调（One-shot，触发后自动移除） */
    private final Map<String, Consumer<WatchEvent>> watcherCallbacks = new ConcurrentHashMap<>();

    /** REDIRECT 监听器 */
    private volatile Consumer<String> redirectListener;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String raw) {
        log.debug("Received: {}", raw);

        // WATCH_EVENT 单独处理（格式不同于 CommandType）
        if (raw.startsWith("WATCH_EVENT")) {
            handleWatchEvent(raw);
            return;
        }

        Message msg;
        try {
            msg = Message.parse(raw);
        } catch (Exception e) {
            log.error("Failed to parse server message: {}", raw, e);
            return;
        }

        switch (msg.getType()) {
            case REDIRECT:  handleRedirect(msg);  break;
            case CONNECTED: complete("CONNECT", msg); break;
            case OK:
                // Schema 保证 OK 有 1-2 个参数，直接用 arg(0) / optArg(1)
                complete("LOCK:" + msg.arg(0), msg);
                complete("RECHECK:" + msg.optArg(1).orElse(""), msg);
                complete("SET_DATA:" + msg.arg(0), msg);
                break;
            case WAIT:
                complete("LOCK:" + msg.arg(0), msg);
                complete("RECHECK:" + msg.arg(1), msg);
                break;
            case RELEASED:
                complete("UNLOCK:" + msg.arg(0), msg);
                break;
            case RENEWED:
                complete("RENEW:" + msg.arg(0), msg);
                break;
            case DATA:
                complete("GET_DATA:" + msg.arg(0), msg);
                break;
            case VERSION_MISMATCH:
                complete("SET_DATA:" + msg.arg(0), msg);
                break;
            case ERROR:
                completeError(msg);
                break;
            default:
                log.warn("Unhandled message type: {}", msg.getType());
        }
    }

    // ==================== Watcher 事件 ====================

    private void handleWatchEvent(String raw) {
        try {
            WatchEvent event = WatchEvent.parse(raw);
            log.debug("Watch event: {}", event);
            Consumer<WatchEvent> cb = watcherCallbacks.remove(event.getPath().value());
            if (cb != null) cb.accept(event);
        } catch (Exception e) {
            log.error("Failed to parse watch event: {}", raw, e);
        }
    }

    private void handleRedirect(Message msg) {
        String leaderId = msg.arg(0);  // Schema 保证 REDIRECT 有 1 个参数
        log.info("Redirected to leader: {}", leaderId);
        pendingRequests.forEach((k, pending) ->
            pending.future.complete(Message.of(CommandType.REDIRECT, leaderId)));
        pendingRequests.clear();
        pendingOrder.clear();
        if (redirectListener != null) redirectListener.accept(leaderId);
    }

    // ==================== Future 管理 ====================

    public CompletableFuture<Message> registerRequest(String key) {
        return registerRequest(key, false);
    }

    public CompletableFuture<Message> registerRequest(String key, boolean completeWithErrorResponse) {
        CompletableFuture<Message> future = new CompletableFuture<>();
        PendingRequest pending = new PendingRequest(future, completeWithErrorResponse);
        PendingRequest previous = pendingRequests.putIfAbsent(key, pending);
        if (previous != null) {
            throw new IllegalStateException("duplicate pending request: " + key);
        }
        pendingOrder.offer(key);
        return future;
    }

    /** 注册 Watcher 回调（One-shot），key 为被监听的 ZNode 路径。 */
    public void registerWatcher(String path, Consumer<WatchEvent> callback) {
        watcherCallbacks.put(path, callback);
    }

    /** 注销 Watcher 回调（超时或取消时调用，防止内存泄漏）。 */
    public void unregisterWatcher(String path) {
        watcherCallbacks.remove(path);
    }

    public void setRedirectListener(Consumer<String> listener) {
        this.redirectListener = listener;
    }

    private void complete(String key, Message msg) {
        PendingRequest pending = pendingRequests.remove(key);
        if (pending != null) {
            pendingOrder.remove(key);
            pending.future.complete(msg);
        }
    }

    private void completeError(Message msg) {
        String errMsg = msg.optArg(0).orElse("unknown error");
        while (true) {
            String key = pendingOrder.poll();
            if (key == null) {
                log.warn("Received ERROR with no pending request: {}", errMsg);
                return;
            }

            PendingRequest pending = pendingRequests.remove(key);
            if (pending == null) {
                continue;
            }

            if (pending.completeWithErrorResponse) {
                pending.future.complete(msg);
            } else {
                pending.future.completeExceptionally(new RuntimeException(errMsg));
            }
            return;
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.warn("Connection lost");
        pendingRequests.forEach((k, pending) ->
            pending.future.completeExceptionally(new RuntimeException("connection lost")));
        pendingRequests.clear();
        pendingOrder.clear();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Channel error: {}", cause.getMessage());
        ctx.close();
    }

    private static final class PendingRequest {
        private final CompletableFuture<Message> future;
        private final boolean completeWithErrorResponse;

        private PendingRequest(CompletableFuture<Message> future, boolean completeWithErrorResponse) {
            this.future = future;
            this.completeWithErrorResponse = completeWithErrorResponse;
        }
    }
}
