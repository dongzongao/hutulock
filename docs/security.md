# HutuLock 安全指南

## 安全架构

```
客户端请求
    ↓
TLS Handler（可选，加密传输）
    ↓
SecurityChannelHandler（认证 + 限流 + 授权 + 审计）
    ↓
LockServerHandler（业务逻辑）
```

---

## 认证（Authentication）

### Token 认证

适用于内部微服务，静态预共享 Token。

**服务端配置：**

```java
SecurityContext security = SecurityContext.builder()
    .authenticator(new TokenAuthenticator()
        .addClient("order-service", "my-32-char-secret-token-here!!!")
        .addClient("payment-service", "another-32-char-secret-token!!!"))
    .build();
```

**客户端连接：**

```
CONNECT TOKEN:my-32-char-secret-token-here!!!
```

**安全特性：**
- 使用常量时间比较（`constantTimeEquals`），防止时序攻击（Timing Attack）
- Token 最短 16 字符

### HMAC-SHA256 认证

适用于高安全场景，基于签名防重放。

**签名算法：**

```
message   = clientId + ":" + timestamp（毫秒）
signature = Base64(HMAC-SHA256(secretKey, message))
```

**服务端配置：**

```java
SecurityContext security = SecurityContext.builder()
    .authenticator(new HmacAuthenticator(5 * 60 * 1000L) // 5分钟防重放窗口
        .addClient("order-service", "32-byte-secret-key-here-padding!!"))
    .build();
```

**客户端连接：**

```
CONNECT HMAC:1700000000000:Base64EncodedSignature
```

**防重放：** 时间戳与服务端当前时间差超过 `replayWindowMs`（默认 5 分钟）时拒绝。

---

## 授权（Authorization）

### ACL 规则

基于有序规则列表，支持精确匹配和通配符。

```java
Authorizer authz = new AclAuthorizer()
    // order-service 可以操作所有 order-* 锁
    .allow("order-service", "order-*", Permission.LOCK)
    .allow("order-service", "order-*", Permission.UNLOCK)
    // payment-service 只能操作 payment-lock
    .allow("payment-service", "payment-lock", Permission.LOCK)
    .allow("payment-service", "payment-lock", Permission.UNLOCK)
    // admin 可以做任何操作
    .allow("admin", "*", Permission.ADMIN)
    // 默认拒绝所有（推荐）
    .denyAll();
```

### 通配符规则

| 模式 | 匹配示例 |
|------|---------|
| `*` | 匹配所有 |
| `order-*` | `order-lock`、`order-payment` |
| `order-lock` | 仅 `order-lock` |

### 权限类型

| 权限 | 对应命令 |
|------|---------|
| LOCK | LOCK 命令 |
| UNLOCK | UNLOCK 命令 |
| ADMIN | 管理操作（强制释放等） |

---

## 传输安全（TLS）

### 生产环境（PEM 证书）

```java
// 服务端
SslContext sslCtx = TlsContextFactory.serverContext(
    new File("/etc/hutulock/server.crt"),
    new File("/etc/hutulock/server.key")
);

// 客户端
SslContext clientSsl = TlsContextFactory.clientContext(
    new File("/etc/hutulock/ca.crt")
);
```

### 开发环境（自签名证书）

```java
SslContext sslCtx = TlsContextFactory.serverContextSelfSigned();
```

> ⚠️ 自签名证书仅用于开发/测试，禁止用于生产环境。

### 配置文件启用 TLS

```yaml
hutulock:
  server:
    security:
      tls:
        enabled: true
        certFile: /etc/hutulock/server.crt
        keyFile: /etc/hutulock/server.key
```

---

## 限流（Rate Limiting）

基于令牌桶算法，按 clientId 独立限流。

```java
SecurityContext security = SecurityContext.builder()
    .rateLimiter(new TokenBucketRateLimiter(
        100,  // 每秒 100 个请求
        200   // 允许突发 200 个
    ))
    .build();
```

**算法：**
- 令牌桶容量 = `bucketCapacity`
- 每秒补充 `tokensPerSecond` 个令牌
- 请求到来时消耗 1 个令牌，桶空则拒绝

---

## 审计日志（Audit）

所有安全事件输出到专用 `hutulock.audit` logger。

```java
SecurityContext security = SecurityContext.builder()
    .auditLogger(new Slf4jAuditLogger())
    .build();
```

**logback.xml 配置（独立审计文件）：**

```xml
<appender name="AUDIT_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>logs/hutulock-audit.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
        <fileNamePattern>logs/hutulock-audit.%d{yyyy-MM-dd}.log.gz</fileNamePattern>
        <maxHistory>365</maxHistory>
    </rollingPolicy>
    <encoder>
        <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %msg%n</pattern>
    </encoder>
</appender>

<logger name="hutulock.audit" level="INFO" additivity="false">
    <appender-ref ref="AUDIT_FILE"/>
</logger>
```

**审计日志格式：**

```
[AUDIT] type=AUTH client=order-service addr=127.0.0.1:12345 success=true
[AUDIT] type=AUTH client=unknown addr=10.0.0.1:9999 success=false reason=invalid token
[AUDIT] type=AUTHZ client=order-service lock=order-lock perm=LOCK permitted=true
[AUDIT] type=LOCK_OP client=order-service lock=order-lock op=LOCK success=true
```

---

## 完整安全配置示例

```java
HutuLockServer server = new HutuLockServer(nodeId, clientPort, raftPort,
    new CodeConfigProvider(
        ServerProperties.builder()
            .securityEnabled(true)
            .tlsEnabled(true)
            .tlsCertFile("/etc/hutulock/server.crt")
            .tlsKeyFile("/etc/hutulock/server.key")
            .rateLimit(100, 200)
            .build(),
        ClientProperties.defaults()
    )
);
```

> 注：自定义 `Authenticator`/`Authorizer` 通过 `SecurityContext.builder()` 注入，
> 当前版本需在代码中配置，后续版本将支持配置文件声明。
