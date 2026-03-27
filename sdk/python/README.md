# HutuLock Python SDK

Python 3.8+ 客户端，纯标准库，无第三方依赖。

## 安装

```bash
pip install hutulock
```

或直接从源码：

```bash
pip install -e hutulock/sdk/python
```

## 快速上手

```python
from hutulock import HutuLockClient

# 上下文管理器（推荐）
with HutuLockClient(nodes=[("127.0.0.1", 8881)]) as client:
    with client.lock("order-lock"):
        # 临界区
        process_order()

# 手动管理
client = HutuLockClient(
    nodes=[("127.0.0.1", 8881), ("127.0.0.1", 8882)],
    lock_timeout=30.0,
    watchdog_interval=10.0,
)
client.connect()

token = client.acquire("order-lock")
try:
    process_order()
finally:
    client.release(token)

client.close()
```

## 协议兼容性

与 Java 服务端完全兼容，使用相同的文本行协议（UTF-8）：

| 客户端发送 | 服务端响应 |
|-----------|-----------|
| `CONNECT` | `CONNECTED {sessionId}` |
| `LOCK {name} {sessionId}` | `OK {name} {seqPath}` 或 `WAIT {name} {seqPath}` |
| `RECHECK {name} {seqPath} {sessionId}` | `OK` 或 `WAIT` |
| `UNLOCK {seqPath} {sessionId}` | `RELEASED {name}` |
| `RENEW {name} {sessionId}` | `RENEWED {name}` |

## 运行测试

```bash
python3 -m unittest discover -s tests -v
```
