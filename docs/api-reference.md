# HutuLock API 参考

## 客户端 API

### HutuLockClient

```java
// 构建客户端
HutuLockClient client = HutuLockClient.builder()
    .addNode("127.0.0.1", 8881)   // 添加集群节点（可多个）
    .addNode("127.0.0.1", 8882)
    .config(ClientProperties.builder()
        .connectTimeout(3000)
        .lockTimeout(30)
        .watchdogTtl(30_000)
        .watchdogInterval(9_000)
        .build())
    .build();

// 连接（建立 Session）
client.connect();

// 获取 sessionId
String sessionId = client.getSessionId();

// 简单获取锁（默认配置）
boolean held = client.lock("order-lock");

// 简单释放锁
client.unlock("order-lock");

// 关闭客户端
client.close();
```

### LockContext

```java
LockContext ctx = LockContext.builder("order-lock", sessionId)
    .ttl(30, TimeUnit.SECONDS)              // 服务端 TTL
    .watchdogInterval(9, TimeUnit.SECONDS)  // 心跳间隔（< ttl/3）
    .onExpired(lockName -> {                // 过期回调
        log.warn("Lock {} expired!", lockName);
        abortWork.set(true);
    })
    .scheduler(sharedScheduler)             // 可选：共享调度器
    .build();

// 带超时获取锁
boolean acquired = client.lock(ctx, 30, TimeUnit.SECONDS);

// 检查锁状态
LockContext.State state = ctx.getState(); // IDLE/HELD/EXPIRED/RELEASED

// 获取顺序节点路径
String seqPath = ctx.getSeqNodePath(); // /locks/order-lock/seq-0000000001

// 释放锁
client.unlock(ctx);
```

---

## 服务端 API

### HutuLockServer

```java
// 构建服务端
HutuLockServer server = new HutuLockServer(
    "node1",          // nodeId（集群内唯一）
    8881,             // 客户端端口
    9881,             // Raft 端口
    new YamlConfigProvider()  // 配置提供者
);

// 添加集群节点
server.addPeer("node2", "127.0.0.1", 9882);
server.addPeer("node3", "127.0.0.1", 9883);

// 启动（阻塞）
server.start();
```

---

## SPI 扩展接口

### 自定义认证器

```java
public class JwtAuthenticator implements Authenticator {
    @Override
    public AuthResult authenticate(AuthToken token) {
        // 验证 JWT token
        String jwt = token.getCredential();
        try {
            Claims claims = Jwts.parser()
                .setSigningKey(secretKey)
                .parseClaimsJws(jwt)
                .getBody();
            return AuthResult.success(claims.getSubject());
        } catch (Exception e) {
            return AuthResult.failure("invalid JWT: " + e.getMessage());
        }
    }
}
```

### 自定义授权器

```java
public class DatabaseAuthorizer implements Authorizer {
    @Override
    public boolean isPermitted(String clientId, String lockName, Permission permission) {
        // 从数据库查询权限
        return permissionRepository.exists(clientId, lockName, permission.name());
    }
}
```

### 自定义 Metrics 收集器

```java
public class JmxMetricsCollector implements MetricsCollector {
    @Override
    public void onLockAcquired(String lockName) {
        // 上报到 JMX
        lockAcquiredCounter.increment();
    }
    // ... 其他方法
}
```

### 自定义事件监听器

```java
// 订阅锁事件
eventBus.subscribe(LockEvent.class, event -> {
    if (event.getType() == LockEvent.Type.EXPIRED) {
        alertService.send("Lock expired: " + event.getLockName()
            + " held by " + event.getSessionId()
            + " for " + event.getHeldDurationMs() + "ms");
    }
});

// 订阅所有事件（用于审计）
eventBus.subscribe(HutuEvent.class, event -> {
    auditLog.write(event.getSourceNodeId(), event.getEventType(),
        event.getTimestamp());
});
```

---

## 错误码参考

| 错误码 | 值 | 说明 |
|--------|-----|------|
| INVALID_COMMAND | 1001 | 命令格式不合法 |
| UNKNOWN_COMMAND | 1002 | 未知命令类型 |
| MISSING_ARGUMENT | 1003 | 缺少必要参数 |
| LOCK_TIMEOUT | 2001 | 获取锁超时 |
| LOCK_NOT_HELD | 2002 | 锁未被当前会话持有 |
| SESSION_EXPIRED | 3001 | 会话已过期 |
| SESSION_NOT_FOUND | 3002 | 会话不存在 |
| NOT_LEADER | 4001 | 当前节点非 Leader |
| LEADER_CHANGED | 4002 | Leader 切换 |
| PROPOSE_TIMEOUT | 4003 | Raft Propose 超时 |
| SERVER_UNAVAILABLE | 4004 | 集群无可用 Leader |
| NODE_NOT_FOUND | 5001 | ZNode 不存在 |
| NODE_ALREADY_EXISTS | 5002 | ZNode 已存在 |
| PARENT_NOT_FOUND | 5003 | 父节点不存在 |
| VERSION_MISMATCH | 5004 | ZNode 版本不匹配 |
| CONNECTION_FAILED | 9001 | 连接失败 |
