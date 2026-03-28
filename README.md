# HutuLock

High-availability distributed lock service based on Raft consensus — a drop-in replacement for MySQL optimistic locking and Redis-based distributed locks.

## Quick Start

```bash
# Start single node
java -jar hutulock-server.jar node1 8881 9881

# 3-node cluster
./bin/cluster.sh
```

## Usage

**Distributed lock (Java)**
```java
HutuLockClient client = HutuLockClient.builder().addNode("127.0.0.1", 8881).build();
client.connect();
client.lock("order-lock");
try { /* critical section */ } finally { client.unlock("order-lock"); }
```

**Optimistic lock — replaces MySQL `version` field**
```java
// Read
VersionedData vd = client.getData("/resources/order-123");

// Write with version check (retries automatically on conflict)
boolean ok = client.optimisticUpdate("/resources/order-123", 3, current -> {
    Order order = deserialize(current.getData());
    order.setStatus("PAID");
    return serialize(order);
});
```

## Architecture

```
Client SDK  →  Netty TCP  →  LockServerHandler
                                  ↓
                            Raft Consensus (3/5 nodes)
                                  ↓
                            ZNode Tree (in-memory)
                            + WAL persistence
```

## SDKs

| Language | Install |
|----------|---------|
| Java     | Maven: `com.hutulock:hutulock-client` |
| Python   | `pip install hutulock` |
| Go       | `go get github.com/hutulock/hutulock-go` |
| PHP      | `composer require hutulock/hutulock-php` |

## Admin Console

```
http://localhost:9091   admin / admin123
```

Metrics: `http://localhost:9090/metrics` (Prometheus)
