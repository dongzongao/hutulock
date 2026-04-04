# HutuLock Client 安全漏洞扫描报告

**扫描日期**: 2026-04-04  
**扫描范围**: hutulock-client 模块  
**严重程度**: 🔴 高危 | 🟠 中危 | 🟡 低危 | ℹ️ 信息

---

## 📋 执行摘要

扫描发现 **12 个安全问题**：
- 🔴 高危: 3 个
- 🟠 中危: 5 个
- 🟡 低危: 3 个
- ℹ️ 信息: 1 个

---

## 🔴 高危漏洞

### 1. 资源泄漏 - Watcher 内存泄漏风险
**文件**: `LockClientHandler.java`  
**位置**: Line 95-96, 113-114  
**严重程度**: 🔴 高危

**问题描述**:
```java
// waitForLock() 中的超时处理
handler.unregisterWatcher(watchPath);  // 仅在超时时清理
```

当客户端异常退出或连接断开时，已注册但未触发的 Watcher 回调会永久保留在 `watcherCallbacks` Map 中，导致内存泄漏。

**影响**:
- 长时间运行的客户端可能累积大量未清理的 Watcher
- 每个 Watcher 持有 CompletableFuture 和回调函数引用
- 可能导致 OutOfMemoryError

**修复建议**:
```java
// 在 channelInactive() 中清理所有 Watcher
@Override
public void channelInactive(ChannelHandlerContext ctx) {
    log.warn("Connection lost");
    // 清理所有待处理的请求
    pendingRequests.forEach((k, pending) ->
        pending.future.completeExceptionally(new RuntimeException("connection lost")));
    pendingRequests.clear();
    pendingOrder.clear();
    
    // 🔴 添加: 清理所有 Watcher，防止内存泄漏
    watcherCallbacks.forEach((path, callback) -> {
        callback.accept(WatchEvent.sessionExpired(path));
    });
    watcherCallbacks.clear();
}
```

---

### 2. 线程安全问题 - 竞态条件
**文件**: `HutuLockClient.java`  
**位置**: Line 195-210 (handleRedirect)  
**严重程度**: 🔴 高危

**问题描述**:
```java
private synchronized void handleRedirect(String leaderId) {
    // ...
    if (channel != null) channel.close().sync();
    // ...
    this.sessionId = establishSession(this.sessionId);  // 🔴 可能被其他线程读取到 null
}
```

`handleRedirect()` 虽然是 synchronized，但 `sessionId` 字段是 volatile，在重连过程中可能被其他线程读取到中间状态。

**影响**:
- 并发的 lock() 调用可能使用 null sessionId
- 导致服务端拒绝请求或创建新会话

**修复建议**:
```java
private synchronized void handleRedirect(String leaderId) {
    log.info("Redirected to leader: {}, reconnecting...", leaderId);
    try {
        String oldSessionId = this.sessionId;  // 保存旧值
        if (channel != null) channel.close().sync();
        List<String[]> shuffled = new ArrayList<>(nodes);
        Collections.shuffle(shuffled);
        connectToAny(shuffled);
        this.sessionId = establishSession(oldSessionId);  // 原子更新
    } catch (Exception e) {
        log.error("Reconnect failed", e);
        // 🔴 添加: 恢复旧 sessionId 或标记为不可用
        throw new RuntimeException("Reconnect failed", e);
    }
}
```

---

### 3. 未验证的输入 - Base64 解码漏洞
**文件**: `HutuLockClient.java`  
**位置**: Line 289-292  
**严重程度**: 🔴 高危

**问题描述**:
```java
public VersionedData getData(String path) throws Exception {
    // ...
    byte[] data = resp.optArg(2)
        .map(s -> java.util.Base64.getDecoder().decode(s))  // 🔴 未捕获 IllegalArgumentException
        .orElse(new byte[0]);
    return new VersionedData(path, data, version);
}
```

恶意服务端可以发送非法 Base64 字符串，导致客户端抛出未捕获的 `IllegalArgumentException`。

**影响**:
- 客户端崩溃或进入不一致状态
- 可能被用于 DoS 攻击

**修复建议**:
```java
public VersionedData getData(String path) throws Exception {
    CompletableFuture<Message> future = handler.registerRequest("GET_DATA:" + path, true);
    channel.writeAndFlush(
        Message.of(CommandType.GET_DATA, path, sessionId).serialize() + "\n");

    Message resp = future.get(10, TimeUnit.SECONDS);
    if (resp.getType() == CommandType.ERROR) return null;

    int version = Integer.parseInt(resp.arg(1));
    byte[] data = resp.optArg(2)
        .map(s -> {
            try {
                return java.util.Base64.getDecoder().decode(s);
            } catch (IllegalArgumentException e) {
                log.error("Invalid Base64 data from server for path: {}", path, e);
                return new byte[0];  // 🔴 添加: 安全降级
            }
        })
        .orElse(new byte[0]);
    return new VersionedData(path, data, version);
}
```

---

## 🟠 中危漏洞

### 4. 资源泄漏 - EventLoopGroup 未正确关闭
**文件**: `HutuLockClient.java`  
**位置**: Line 186-194 (close 方法)  
**严重程度**: 🟠 中危

**问题描述**:
```java
@Override
public void close() {
    // ...
    if (channel != null) channel.close();  // 🟠 未等待关闭完成
    group.shutdownGracefully(0, 3, TimeUnit.SECONDS);  // 🟠 未等待完成
}
```

`close()` 方法没有等待 channel 和 EventLoopGroup 完全关闭，可能导致资源泄漏。

**修复建议**:
```java
@Override
public void close() {
    heldContexts.values().forEach(LockContext::stopWatchdog);
    watchdogScheduler.shutdown();
    try {
        if (!watchdogScheduler.awaitTermination(3, TimeUnit.SECONDS)) {
            watchdogScheduler.shutdownNow();
        }
    } catch (InterruptedException e) {
        watchdogScheduler.shutdownNow();
        Thread.currentThread().interrupt();
    }
    
    // 🟠 修复: 等待 channel 关闭
    if (channel != null) {
        try {
            channel.close().sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    // 🟠 修复: 等待 EventLoopGroup 关闭
    try {
        group.shutdownGracefully(0, 3, TimeUnit.SECONDS).sync();
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
}
```

---

### 5. 无限重试风险 - optimisticUpdate 可能死循环
**文件**: `HutuLockClient.java`  
**位置**: Line 330-345  
**严重程度**: 🟠 中危

**问题描述**:
```java
public boolean optimisticUpdate(String path, int maxRetries,
                                java.util.function.Function<VersionedData, byte[]> updater)
        throws Exception {
    for (int attempt = 0; attempt <= maxRetries; attempt++) {
        // ...
        Thread.sleep(10L * (1 << Math.min(attempt, 5)));  // 🟠 最大延迟仅 320ms
    }
    // ...
}
```

在高并发场景下，指数退避的最大延迟仅 320ms，可能导致频繁重试消耗大量 CPU。

**修复建议**:
```java
public boolean optimisticUpdate(String path, int maxRetries,
                                java.util.function.Function<VersionedData, byte[]> updater)
        throws Exception {
    for (int attempt = 0; attempt <= maxRetries; attempt++) {
        VersionedData current = getData(path);
        if (current == null) return false;

        byte[] newData = updater.apply(current);
        if (setData(path, newData, current.getVersion())) return true;

        if (attempt < maxRetries) {
            log.debug("Version conflict on {}, retry {}/{}", path, attempt + 1, maxRetries);
            // 🟠 修复: 增加最大延迟到 5 秒，添加随机抖动
            long baseDelay = 10L * (1 << Math.min(attempt, 9));  // 最大 5120ms
            long jitter = ThreadLocalRandom.current().nextLong(baseDelay / 2);
            Thread.sleep(baseDelay + jitter);
        }
    }
    log.warn("optimisticUpdate failed after {} retries on {}", maxRetries, path);
    return false;
}
```

---

### 6. 超时配置不合理 - 固定 10 秒超时
**文件**: `HutuLockClient.java`  
**位置**: Line 287, 308, 318  
**严重程度**: 🟠 中危

**问题描述**:
所有数据操作（getData, setData, unlock）都使用硬编码的 10 秒超时，不可配置。

**影响**:
- 网络延迟高时可能频繁超时
- 无法根据业务需求调整

**修复建议**:
在 `ClientProperties` 中添加可配置的超时参数，并在所有操作中使用。

---

### 7. 状态不一致 - unlock 超时后状态未更新
**文件**: `HutuLockClient.java`  
**位置**: Line 274-282  
**严重程度**: 🟠 中危

**问题描述**:
```java
public void unlock(LockContext ctx) throws Exception {
    ctx.stopWatchdog();
    heldContexts.remove(ctx.getLockName());  // 🟠 立即移除，但 unlock 可能失败

    // ...
    try {
        future.get(10, TimeUnit.SECONDS);
        ctx.markReleased();
        log.info("Lock released: {}", ctx.getLockName());
    } catch (TimeoutException e) {
        log.warn("Unlock timeout for [{}], session expiry will clean up", ctx.getLockName());
        // 🟠 ctx 状态仍然是 HELD，但已从 heldContexts 移除
    }
}
```

**修复建议**:
```java
public void unlock(LockContext ctx) throws Exception {
    ctx.stopWatchdog();
    
    CompletableFuture<Message> future =
        handler.registerRequest("UNLOCK:" + ctx.getLockName());
    channel.writeAndFlush(
        Message.of(CommandType.UNLOCK, ctx.getSeqNodePath(), sessionId).serialize() + "\n");

    try {
        future.get(10, TimeUnit.SECONDS);
        ctx.markReleased();
        heldContexts.remove(ctx.getLockName());  // 🟠 修复: 成功后才移除
        log.info("Lock released: {}", ctx.getLockName());
    } catch (TimeoutException e) {
        log.warn("Unlock timeout for [{}], session expiry will clean up", ctx.getLockName());
        ctx.markExpired();  // 🟠 修复: 标记为过期
        heldContexts.remove(ctx.getLockName());
    }
}
```

---

### 8. 并发问题 - heldContexts 的非原子操作
**文件**: `HutuLockClient.java`  
**位置**: Line 248, 268, 274  
**严重程度**: 🟠 中危

**问题描述**:
```java
// lock() 中
ctx.startWatchdog(channel);
heldContexts.put(ctx.getLockName(), ctx);  // 🟠 非原子操作

// unlock() 中
ctx.stopWatchdog();
heldContexts.remove(ctx.getLockName());  // 🟠 非原子操作
```

在并发场景下，可能出现竞态条件。

**修复建议**:
使用 `computeIfAbsent` 和 `compute` 确保原子性。

---

## 🟡 低危漏洞

### 9. 日志注入风险
**文件**: 多个文件  
**严重程度**: 🟡 低危

**问题描述**:
多处日志直接输出用户输入，可能被注入恶意内容：
```java
log.info("Lock acquired: lock={}, seq={}", ctx.getLockName(), ctx.getSeqNodePath());
```

**修复建议**:
对用户输入进行清理或使用参数化日志（已使用，但需注意特殊字符）。

---

### 10. 异常信息泄露
**文件**: `LockClientHandler.java`  
**位置**: Line 134  
**严重程度**: 🟡 低危

**问题描述**:
```java
private void completeError(Message msg) {
    String errMsg = msg.optArg(0).orElse("unknown error");
    // ...
    pending.future.completeExceptionally(new RuntimeException(errMsg));  // 🟡 直接暴露服务端错误
}
```

**修复建议**:
对服务端错误消息进行清理，避免泄露内部信息。

---

### 11. 缺少输入验证
**文件**: `HutuLockClient.java`  
**位置**: Builder 类  
**严重程度**: 🟡 低危

**问题描述**:
Builder 缺少对 host/port 的验证：
```java
public Builder addNode(String host, int port) {
    nodes.add(new String[]{host, String.valueOf(port)});  // 🟡 未验证
    return this;
}
```

**修复建议**:
```java
public Builder addNode(String host, int port) {
    if (host == null || host.trim().isEmpty()) {
        throw new IllegalArgumentException("host cannot be null or empty");
    }
    if (port < 1 || port > 65535) {
        throw new IllegalArgumentException("port must be between 1 and 65535");
    }
    nodes.add(new String[]{host, String.valueOf(port)});
    return this;
}
```

---

## ℹ️ 信息级问题

### 12. 缺少防御性编程
**文件**: `ConnectionManager.java`  
**严重程度**: ℹ️ 信息

**问题描述**:
多处假设输入有效，缺少 null 检查和边界检查。

**修复建议**:
添加 `Objects.requireNonNull()` 和参数验证。

---

## 🎯 优先修复建议

### 立即修复（高危）:
1. ✅ Watcher 内存泄漏 - 在 `channelInactive()` 中清理所有 Watcher
2. ✅ 竞态条件 - 修复 `handleRedirect()` 的线程安全问题
3. ✅ Base64 解码 - 添加异常处理

### 近期修复（中危）:
4. EventLoopGroup 关闭 - 等待资源完全释放
5. optimisticUpdate 重试 - 增加最大延迟和随机抖动
6. unlock 状态 - 修复超时后的状态不一致

### 长期改进（低危）:
7. 添加输入验证
8. 清理异常信息
9. 增强日志安全

---

## 📊 风险评估

| 类别 | 数量 | 风险等级 |
|------|------|----------|
| 内存泄漏 | 2 | 高 |
| 线程安全 | 2 | 高 |
| 资源管理 | 2 | 中 |
| 输入验证 | 2 | 中-低 |
| 状态一致性 | 2 | 中 |
| 信息泄露 | 2 | 低 |

**总体风险**: 🟠 中等偏高

---

## 🔧 修复验证清单

- [ ] 运行所有单元测试
- [ ] 进行压力测试（模拟连接断开、重连）
- [ ] 检查内存泄漏（使用 JProfiler/VisualVM）
- [ ] 并发测试（多线程同时 lock/unlock）
- [ ] 异常场景测试（恶意服务端响应）

---

**报告生成时间**: 2026-04-04 12:55:00  
**扫描工具**: 人工代码审查 + 静态分析
