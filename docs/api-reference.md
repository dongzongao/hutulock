# HutuLock API 参考

## 客户端 API

### HutuLockClient

```java
// 构建客户端
HutuLockClient client = HutuLockClient.builder()
    .addNode("127.0.0.1", 8881)
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

## CLI API

### 启动方式

```bash
# 交互模式
java -jar hutulock-cli.jar

# 启动时自动连接
java -jar hutulock-cli.jar 127.0.0.1:8881 127.0.0.1:8882
```

### CliContext（编程方式使用 CLI 逻辑）

```java
try (CliContext ctx = new CliContext()) {
    ctx.connect(List.of("127.0.0.1:8881", "127.0.0.1:8882"));

    boolean acquired = ctx.lock("order-lock", 30);
    if (acquired) {
        System.out.println(ctx.getStatus());
        ctx.unlock("order-lock");
    }
}
```

---

## SPI 扩展接口

### 自定义认证器

```java
public class JwtAuthenticator implements Authenticator {
    @Override
    public AuthResult authenticate(AuthToken token) {
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
        return permissionRepository.exists(clientId, lockName, permission.name());
    }
}
```

### 自定义 Metrics 收集器

```java
public class JmxMetricsCollector implements MetricsCollector {
    @Override
    public void onLockAcquired(String lockName) {
        lockAcquiredCounter.increment();
    }
    // ... 其他方法
}
```

### 订阅内部事件

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
eventBus.subscribe(HutuEvent.class, event ->
    auditLog.write(event.getSourceNodeId(), event.getEventType(), event.getTimestamp()));
```

### 注册持久 Watcher

```java
// One-shot Watcher（默认，触发一次后自动注销）
zNodeStorage.watch(path, channel);

// 持久 Watcher（触发后不注销，参考 ZooKeeper 3.6 addWatch PERSISTENT）
// 适合需要持续监听锁队列变化的场景
watcherRegistry.registerPersistent(ZNodePath.of("/locks/order-lock"), channel);
```

---

## IoC 容器 API

### ApplicationContext

```java
// 创建容器并注册 Bean
ApplicationContext ctx = new ApplicationContext();
ctx.register(BeanDefinition.of("metrics", MetricsCollector.class,
    () -> new PrometheusMetricsCollector("node1")));
ctx.register(BeanDefinition.of("storage", ZNodeStorage.class,
    () -> new DefaultZNodeTree(
        ctx.getBean(WatcherRegistry.class),
        ctx.getBean(MetricsCollector.class),
        ctx.getBean(EventBus.class))));

// 启动（按注册顺序调用 Lifecycle.start()）
ctx.start();

// 按类型获取 Bean
MetricsCollector metrics = ctx.getBean(MetricsCollector.class);

// 按名称获取 Bean
ZNodeStorage storage = ctx.getBean("storage");

// 关闭（按注册逆序调用 Lifecycle.shutdown()）
ctx.close();
```

### ServerBeanFactory

```java
// 一行注册所有服务端 Bean
ServerBeanFactory.register(ctx, "node1", 9881, new YamlConfigProvider());

// 覆盖默认安全上下文（在 start() 之前调用）
server.withSecurity(SecurityContext.builder()
    .authenticator(new TokenAuthenticator().addClient("svc", "token"))
    .build());
```

### 自定义 Bean 注册

```java
// 替换默认 EventBus 实现（如接入 Kafka）
ctx.register(BeanDefinition.of("eventBus", EventBus.class,
    () -> new KafkaEventBus("localhost:9092")));
// 同名 Bean 会覆盖之前的注册
```

---

## 代理模块 API

### ProxyBuilder

```java
// 日志代理
ZNodeStorage logged = ProxyBuilder.wrap(ZNodeStorage.class, realImpl)
    .withLogging()
    .build();

// 日志 + 指标双层代理
ZNodeStorage proxied = ProxyBuilder.wrap(ZNodeStorage.class, realImpl)
    .withLogging()
    .withMetrics()
    .build();

// 带指标快照引用
ProxyBuilder.MetricsHolder<ZNodeStorage> holder = new ProxyBuilder.MetricsHolder<>();
ZNodeStorage proxied = ProxyBuilder.wrap(ZNodeStorage.class, realImpl)
    .withMetrics(holder)
    .build();
Map<String, String> stats = holder.snapshot();
// {"ZNodeStorage.create" → "calls=42 errors=0 avgMs=3", ...}

// 重试代理（LockService 推荐配置）
LockService retried = ProxyBuilder.wrap(LockService.class, lockService)
    .withLogging()
    .withRetry(
        3,                                    // 最大重试次数
        500L,                                 // 退避间隔 ms
        Set.of("release", "shutdown"),        // 不重试的方法
        RuntimeException.class)               // 可重试异常类型
    .build();
```

### ProxyCatalog

```java
// 查询某接口支持的代理类型
EnumSet<ProxyType> types = ProxyCatalog.supportedTypes(ZNodeStorage.class);
// → [LOGGING, METRICS]

// 判断是否支持某代理类型
boolean canRetry = ProxyCatalog.supports(LockService.class, ProxyType.RETRY);
// → true

// 获取所有可代理接口
Set<Class<?>> proxiable = ProxyCatalog.proxiableInterfaces();

// 打印代理目录（启动日志）
log.info(ProxyCatalog.describe());
```

---

## Admin 控制台 API

### 认证

```bash
# 登录，获取 token
curl -X POST http://localhost:9091/api/admin/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
# → {"token":"xxx","username":"admin"}

# 注销
curl -X POST http://localhost:9091/api/admin/logout \
  -H "Authorization: Bearer xxx"
```

### 集群状态

```bash
curl http://localhost:9091/api/admin/cluster \
  -H "Authorization: Bearer xxx"
```

```json
{
  "nodeId": "node1",
  "role": "LEADER",
  "leaderId": "node1",
  "configPhase": "NORMAL",
  "members": ["node1", "node2", "node3"],
  "peers": [
    {"nodeId":"node2","host":"127.0.0.1","port":9882,"nextIndex":5,"matchIndex":4,"inFlight":false},
    {"nodeId":"node3","host":"127.0.0.1","port":9883,"nextIndex":5,"matchIndex":4,"inFlight":false}
  ],
  "membershipChangePending": false
}
```

### 动态成员变更

```bash
# 添加成员（触发 Joint Consensus 两阶段变更）
curl -X POST http://localhost:9091/api/admin/members/add \
  -H "Authorization: Bearer xxx" \
  -H "Content-Type: application/json" \
  -d '{"nodeId":"node4","host":"127.0.0.1","port":9884}'
# → {"status":"accepted","nodeId":"node4"}

# 移除成员
curl -X POST http://localhost:9091/api/admin/members/remove \
  -H "Authorization: Bearer xxx" \
  -H "Content-Type: application/json" \
  -d '{"nodeId":"node3"}'
# → {"status":"accepted","nodeId":"node3"}
```

### 编程方式调用成员变更

```java
// 添加成员（返回 CompletableFuture，变更完成后 complete）
CompletableFuture<Void> f = raftNode.addMember("node4", "127.0.0.1", 9884);
f.get(30, TimeUnit.SECONDS); // 等待 Joint Consensus 完成

// 移除成员
raftNode.removeMember("node3").get(30, TimeUnit.SECONDS);
```

---

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
