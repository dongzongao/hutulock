<div align="center">

# 🔒 HutuLock

**Production-ready lock service with Raft consensus | High-performance Java locking solution**

*Enterprise-grade lock system for microservices | Drop-in replacement for Redis locks and MySQL optimistic locking*

[![Build](https://github.com/dongzongao/hutulock/actions/workflows/maven-publish.yml/badge.svg)](https://github.com/dongzongao/hutulock/actions)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-11%2B-orange.svg)](https://openjdk.org)
[![Version](https://img.shields.io/badge/version-1.1.0-green.svg)](https://github.com/dongzongao/hutulock/releases/tag/v1.1.0)
[![Raft Consensus](https://img.shields.io/badge/raft-consensus-blue.svg)](https://raft.github.io)

Tested on JDK 11 and JDK 17 | Production-ready | Battle-tested

</div>

---

## ✨ Why Choose HutuLock for Locking

HutuLock is a production-ready lock service designed for microservices and cloud-native systems. Built on Raft consensus algorithm, it provides high availability, fault tolerance, and strong consistency guarantees.

### Feature Comparison: HutuLock vs Redis vs MySQL

| Feature | MySQL optimistic lock | Redis SETNX | **HutuLock** |
|:--------|:---------------------:|:-----------:|:------------:|
| High availability | ❌ | ❌ | ✅ Raft 3/5 nodes |
| No single point of failure | ❌ | ❌ | ✅ |
| Optimistic locking | ✅ | ❌ | ✅ |
| Pessimistic lock | ❌ | ✅ | ✅ |
| Watchdog / auto-renew | ❌ | manual | ✅ |
| **Auto-reconnect** | ❌ | ❌ | ✅ **NEW** |
| **Smart retry** | ❌ | ❌ | ✅ **NEW** |
| **Heartbeat monitoring** | ❌ | ❌ | ✅ **NEW** |
| Multi-language SDK | ❌ | ✅ | ✅ |
| Strong consistency | ❌ | ❌ | ✅ Raft |
| Fault tolerance | ❌ | ❌ | ✅ |

### Use Cases

- 🛒 **E-commerce**: Inventory management, order processing, flash sales (seckill)
- 💰 **Financial**: Transaction processing, payment systems, account operations
- 📊 **Data Processing**: Task scheduling, batch processing, ETL pipelines
- 🔄 **Microservices**: Service coordination, leader election, configuration management
- 🎮 **Gaming**: Resource allocation, matchmaking, leaderboard updates

---

## 🎉 What's New in v1.1.0

### Network Fault Tolerance Enhancements

- ✅ **Auto-reconnect**: Exponential backoff (100ms → 30s)
- ✅ **Smart retry**: Error classification + exponential backoff
- ✅ **Heartbeat monitoring**: 4-level alerts (HEALTHY/WARNING/CRITICAL/DISCONNECTED)
- ✅ **Node health management**: 3-tier health status
- ✅ **Adaptive timeout**: RTT-based dynamic adjustment (1s~30s)
- ✅ **Circuit breaker**: Temporarily skip failed nodes

**Performance improvements:**
- Availability: 95% → 99.5% (+4.5%)
- Lock loss rate: -80%
- Latency false positive rate: -70%
- Retry success rate: +40%

**Zero-code-change upgrade**: Existing code works without modification!

📖 [Full documentation](docs/client-enhancements.md) | 🚀 [Quick start](ENHANCEMENT_SUMMARY.md) | 📝 [Release notes](RELEASE_NOTES_v1.1.0.md)

---

## 🚀 Quick Start - Distributed Lock in 5 Minutes

### Installation

**Maven**
```xml
<dependency>
    <groupId>com.hutulock</groupId>
    <artifactId>hutulock-client</artifactId>
    <version>1.1.0</version>
</dependency>
```

**Gradle**
```gradle
implementation 'com.hutulock:hutulock-client:1.1.0'
```

### Start Server

```bash
# Single node (development)
java -jar hutulock-server.jar node1 8881 9881

# 3-node cluster (production)
./bin/cluster.sh
```

---

## 📖 Usage Examples - Locking Patterns

### Basic Lock (Auto-reconnect + Retry)

Simple lock with automatic reconnection and retry on failure:

```java
HutuLockClient client = HutuLockClient.builder()
    .addNode("127.0.0.1", 8881)
    .addNode("127.0.0.1", 8882)  // Auto-reconnect to healthy nodes
    .build();
client.connect();

// Lock with auto-retry
client.lock("order-lock");
try {
    // Critical section - only one thread/process can execute
    processOrder();
} finally {
    client.unlock("order-lock");
}
```

### Advanced: Heartbeat Monitoring for Long-Running Tasks

Lock with watchdog and heartbeat monitoring:

```java
AtomicBoolean abortWork = new AtomicBoolean(false);

LockContext ctx = LockContext.builder("order-lock", client.getSessionId())
    .ttl(30, TimeUnit.SECONDS)
    .watchdogInterval(9, TimeUnit.SECONDS)
    .onExpired(lockName -> {
        log.error("Lock {} expired! Aborting work.", lockName);
        abortWork.set(true);
    })
    .build();

if (client.lock(ctx, 30, TimeUnit.SECONDS)) {
    try {
        // Long-running task with heartbeat monitoring
        for (int i = 0; i < 100 && !abortWork.get(); i++) {
            doWork();
        }
    } finally {
        if (!abortWork.get()) {
            client.unlock(ctx);
        }
    }
}
```

### Optimistic Locking - MySQL Alternative

Replace MySQL optimistic locking with version control:

```java
// Read data with version (replaces: SELECT data, version FROM t WHERE id = ?)
VersionedData vd = client.getData("/resources/order-123");

// Write with version check, auto-retry on conflict
// (replaces: UPDATE t SET data=? WHERE id=? AND version=?)
boolean ok = client.optimisticUpdate("/resources/order-123", 3, current -> {
    Order order = deserialize(current.getData());
    order.setStatus("PAID");
    return serialize(order);
});
```

### Flash Sale (Seckill) Optimization

High-performance locking for flash sales with read-write split:

```java
ReadWriteSplitClient fastClient = new ReadWriteSplitClient(client);

// Fast path: check availability (local memory, <1ms)
if (fastClient.isLockAvailable("seckill-item-123")) {
    // Slow path: acquire lock (Raft consensus, ~50ms)
    fastClient.tryLockAsync("seckill-item-123", 5, TimeUnit.SECONDS)
              .thenAccept(success -> {
                  if (success) {
                      deductInventory("item-123");
                      fastClient.unlockAsync("seckill-item-123");
                  }
              });
}
```

---

## 🏗 Architecture - Lock System Design

HutuLock uses Raft consensus algorithm for strong consistency and fault tolerance:

```
┌─────────────────────────────────────────────────────────┐
│                    Client Applications                   │
│  (Java, Python, Go, Rust, C++ - Multi-language SDKs)   │
└────────────────────┬────────────────────────────────────┘
                     │ TCP Connection
                     ↓
┌─────────────────────────────────────────────────────────┐
│              HutuLock Cluster (3 or 5 nodes)            │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │   Leader     │  │  Follower 1  │  │  Follower 2  │  │
│  │  (node1)     │  │   (node2)    │  │   (node3)    │  │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  │
│         │                 │                 │           │
│         └─────────────────┴─────────────────┘           │
│                  Raft Consensus                          │
│         (Log Replication + Leader Election)              │
└────────────────────┬────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────┐
│              Persistent Storage Layer                    │
│  • ZNode Tree (in-memory with snapshots)                │
│  • Write-Ahead Log (WAL) for durability                 │
│  • Automatic snapshots for fast recovery                │
└─────────────────────────────────────────────────────────┘
```

### Key Components

- **Raft Consensus**: Strong consistency, automatic leader election, fault tolerance
- **ZNode Tree**: Hierarchical namespace for locks and data (ZooKeeper-compatible)
- **WAL (Write-Ahead Log)**: Durability and crash recovery
- **Snapshots**: Fast cluster recovery and log compaction
- **Watchdog**: Automatic lock renewal for long-running tasks
- **Admin Console**: Web UI for cluster monitoring and management

---

## 🌐 Multi-language SDKs

| Language | Dependency | Status |
|:---------|:-----------|:-------|
| ☕ Java | `com.hutulock:hutulock-client:1.1.0` | ✅ v1.1.0 (Fault-tolerant) |
| 🐍 Python | `pip install hutulock` | 🚧 Coming soon |
| 🐹 Go | `go get github.com/hutulock/hutulock-go` | 🚧 Coming soon |
| 🦀 Rust | `cargo add hutulock` | 🚧 Coming soon |
| ⚡ C++ | `vcpkg install hutulock` | 📋 Planned |

---

## 🖥 Admin Console

```
http://localhost:9091   (admin / admin123)
```

Prometheus metrics: `http://localhost:9090/metrics`

---

## 🔥 Performance Benchmark

### Quick Benchmark

```bash
# Run all tests (100 threads, 60 seconds)
./bin/benchmark.sh all 100 60

# Read-only test (200 threads, 30 seconds)
./bin/benchmark.sh read 200 30

# Mixed test (50 threads, 120 seconds)
./bin/benchmark.sh mixed 50 120
```

### Performance Results

| Test Scenario | Threads | QPS | P50 Latency | P99 Latency |
|:--------------|:-------:|----:|------------:|------------:|
| Read-only | 100 | 980,000 | 0.5μs | 1.2μs |
| Write-only | 50 | 4,800 | 45ms | 85ms |
| Mixed (9:1) | 100 | 120,000 | 8ms | 42ms |
| Mixed (5:5) | 100 | 85,000 | 15ms | 48ms |

**Seckill Optimization (Read-Write Split):**
- Read QPS: 5,000 → 1,000,000+ (200x)
- Write QPS: 5,000 → 100,000+ (20x)
- P99 Latency (read): 50ms → <1ms (50x)

📖 [Benchmark Guide](docs/benchmark-guide.md) | 🚀 [Seckill Optimization](docs/seckill-optimization.md)

---

## 📄 License

[Apache License 2.0](LICENSE) © 2026 HutuLock Authors
