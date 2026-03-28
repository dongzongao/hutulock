<div align="center">

# 🔒 HutuLock

**High-availability distributed lock service built on Raft consensus**

*Drop-in replacement for MySQL optimistic locking and Redis distributed locks*

[![Build](https://github.com/dongzongao/hutulock/actions/workflows/maven-publish.yml/badge.svg)](https://github.com/dongzongao/hutulock/actions)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-11%2B-orange.svg)](https://openjdk.org)
[![Version](https://img.shields.io/badge/version-1.0.1--SNAPSHOT-green.svg)](https://github.com/dongzongao/hutulock/packages)

</div>

---

## ✨ Why HutuLock

| Feature | MySQL optimistic lock | Redis SETNX | **HutuLock** |
|:--------|:---------------------:|:-----------:|:------------:|
| High availability | ❌ | ❌ | ✅ Raft 3/5 nodes |
| No single point of failure | ❌ | ❌ | ✅ |
| Optimistic locking | ✅ | ❌ | ✅ |
| Distributed lock | ❌ | ✅ | ✅ |
| Watchdog / auto-renew | ❌ | manual | ✅ |
| Multi-language SDK | ❌ | ✅ | ✅ |

---

## 🚀 Quick Start

```bash
# Single node
java -jar hutulock-server.jar node1 8881 9881

# 3-node cluster
./bin/cluster.sh
```

---

## 📖 Usage

**Distributed lock**

```java
HutuLockClient client = HutuLockClient.builder()
    .addNode("127.0.0.1", 8881).build();
client.connect();

client.lock("order-lock");
try {
    // critical section
} finally {
    client.unlock("order-lock");
}
```

**Optimistic lock — replaces MySQL `version` field**

```java
// Read (replaces: SELECT data, version FROM t WHERE id = ?)
VersionedData vd = client.getData("/resources/order-123");

// Write with version check, auto-retry on conflict
// (replaces: UPDATE t SET data=? WHERE id=? AND version=?)
boolean ok = client.optimisticUpdate("/resources/order-123", 3, current -> {
    Order order = deserialize(current.getData());
    order.setStatus("PAID");
    return serialize(order);
});
```

---

## 🏗 Architecture

```
Client SDK  ──TCP──►  LockServerHandler
                            │
                      Raft Consensus
                      (3 or 5 nodes)
                            │
                      ZNode Tree (in-memory)
                      + WAL + Snapshots
```

---

## 🌐 Multi-language SDKs

| Language | Dependency |
|:---------|:-----------|
| ☕ Java | `com.hutulock:hutulock-client:1.0.1-SNAPSHOT` |
| 🐍 Python | `pip install hutulock` |
| 🐹 Go | `go get github.com/hutulock/hutulock-go` |
| 🐘 PHP | `composer require hutulock/hutulock-php` |

---

## 🖥 Admin Console

```
http://localhost:9091   (admin / admin123)
```

Prometheus metrics: `http://localhost:9090/metrics`

---

## 📄 License

[Apache License 2.0](LICENSE) © 2026 HutuLock Authors
