# HutuLock

[![Build](https://github.com/dongzongao/hutulock/actions/workflows/maven-publish.yml/badge.svg)](https://github.com/dongzongao/hutulock/actions)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-11%2B-orange.svg)](https://openjdk.org)
[![Maven](https://img.shields.io/badge/Maven-1.0.1--SNAPSHOT-green.svg)](https://github.com/dongzongao/hutulock/packages)

**Distributed lock service built on Raft consensus.** Drop-in replacement for MySQL optimistic locking and Redis distributed locks — with high availability, automatic failover, and multi-language SDKs.

> Replace `SELECT ... WHERE version = ?` / `UPDATE ... WHERE version = ?` with a single API call.

## Why HutuLock

| | MySQL optimistic lock | Redis SETNX | HutuLock |
|---|---|---|---|
| HA / auto-failover | ❌ | ❌ | ✅ Raft 3/5 nodes |
| No single point of failure | ❌ | ❌ | ✅ |
| Optimistic locking | ✅ | ❌ | ✅ |
| Distributed lock | ❌ | ✅ | ✅ |
| Watchdog / auto-renew | ❌ | manual | ✅ |
| Multi-language SDK | ❌ | ✅ | ✅ Java · Python · Go · PHP |

## Quick Start

```bash
# Single node
java -jar hutulock-server.jar node1 8881 9881

# 3-node cluster
./bin/cluster.sh
```

## Usage

**Distributed lock**
```java
HutuLockClient client = HutuLockClient.builder()
    .addNode("127.0.0.1", 8881).build();
client.connect();

client.lock("order-lock");
try { /* critical section */ } finally { client.unlock("order-lock"); }
```

**Optimistic lock — replaces MySQL `version` field**
```java
// Read data + version (replaces: SELECT data, version FROM t WHERE id = ?)
VersionedData vd = client.getData("/resources/order-123");

// Write with version check, auto-retry on conflict
// (replaces: UPDATE t SET data=? WHERE id=? AND version=?)
boolean ok = client.optimisticUpdate("/resources/order-123", 3, current -> {
    Order order = deserialize(current.getData());
    order.setStatus("PAID");
    return serialize(order);
});
```

## Architecture

```
Client SDK  ──TCP──►  LockServerHandler
                            │
                      Raft Consensus
                      (3 or 5 nodes)
                            │
                      ZNode Tree (in-memory)
                      + WAL + Snapshots
```

## SDKs

| Language | |
|----------|-|
| Java | `com.hutulock:hutulock-client:1.0.1-SNAPSHOT` |
| Python | `pip install hutulock` |
| Go | `go get github.com/hutulock/hutulock-go` |
| PHP | `composer require hutulock/hutulock-php` |

## Admin Console

```
http://localhost:9091   (admin / admin123)
```

Prometheus metrics: `http://localhost:9090/metrics`

## License

Apache 2.0
