# HutuLock 测试说明

## 测试结构

```
hutulock-model/src/test/
  com.hutulock.model.znode/
    ZNodePathTest.java          ZNode 路径校验、父路径、名称提取
  com.hutulock.model.protocol/
    MessageTest.java            协议解析、序列化、边界条件
  com.hutulock.model.session/
    SessionTest.java            会话状态机、心跳、过期检测

hutulock-server/src/test/
  com.hutulock.server.impl/
    DefaultZNodeTreeTest.java   ZNode 树创建/删除/查询/Watcher 触发
    LockIntegrationTest.java    锁获取/释放/竞争/会话过期完整流程
  com.hutulock.server.event/
    DefaultEventBusTest.java    事件订阅/发布/异常隔离/统计
  com.hutulock.server.security/
    TokenAuthenticatorTest.java Token 认证、时序攻击防护
    AclAuthorizerTest.java      ACL 规则匹配、通配符、默认策略
    TokenBucketRateLimiterTest  令牌桶限流、并发安全、令牌补充
  com.hutulock.server.stress/
    LockStressTest.java         压力测试（见下方）
```

---

## 运行测试

```bash
# 运行所有测试
mvn test -f hutulock/pom.xml

# 只运行 model 层测试
mvn test -pl hutulock-model -f hutulock/pom.xml

# 只运行 server 层测试
mvn test -pl hutulock-server -f hutulock/pom.xml

# 跳过压力测试（快速验证）
mvn test -pl hutulock-server -f hutulock/pom.xml \
  -Dtest='!LockStressTest'
```

---

## 单元测试覆盖

### ZNodePathTest（10 个用例）

| 测试 | 验证点 |
|------|--------|
| `validPaths` | 合法路径正常创建 |
| `invalidPath_noLeadingSlash` | 无 `/` 前缀抛异常 |
| `invalidPath_trailingSlash` | 尾部 `/` 抛异常 |
| `invalidPath_emptySegment` | 空路径段抛异常 |
| `parent` | 父路径逐级提取 |
| `rootHasNoParent` | 根路径无父路径 |
| `name` | 最后一段名称提取 |
| `childOf` | 子路径拼接 |
| `equality` | 路径相等性 |
| `normalizesDoubleSlash` | 双斜杠被拒绝（预期行为） |

### MessageTest（10 个用例）

| 测试 | 验证点 |
|------|--------|
| `parseConnect` | 无参数命令解析 |
| `parseLock` | 带参数命令解析 |
| `parseOk` | 服务端响应解析 |
| `parseUnknownCommand` | 未知命令抛异常 |
| `parseEmptyLine` | 空行/null 抛异常 |
| `missingArgThrows` | 参数不足抛异常 |
| `serialize` | 序列化为文本行 |
| `serializeNoArgs` | 无参数序列化 |
| `caseInsensitiveParse` | 大小写不敏感 |
| `roundTrip` | 序列化后反序列化一致 |

### SessionTest（6 个用例）

| 测试 | 验证点 |
|------|--------|
| `initialState` | 初始状态为 CONNECTING，sessionId 16 位 |
| `heartbeatUpdatesTimestamp` | 心跳更新时间戳 |
| `expiredWhenNoHeartbeat` | 50ms TTL 后过期 |
| `notExpiredAfterHeartbeat` | 续期后不过期 |
| `isAlive` | CONNECTED/RECONNECTING 为活跃 |
| `heartbeatIgnoredWhenExpired` | 过期后心跳无效 |

---

## 集成测试

### LockIntegrationTest（3 个用例）

**测试环境：** 内存 ZNode 树 + Mock Channel，无网络

| 测试 | 场景 |
|------|------|
| `singleClientAcquireAndRelease` | 单客户端获取锁 → 收到 OK → 释放 → 收到 RELEASED |
| `twoClientsCompeteForLock` | client-1 获锁 → client-2 等待 → client-1 释放 → client-2 recheck → 获锁 |
| `sessionExpireReleasesLock` | client-1 获锁 → 会话过期 → 清理临时节点 → client-2 recheck → 获锁