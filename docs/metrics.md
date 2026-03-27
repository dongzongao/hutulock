# HutuLock 监控指南

## Prometheus 集成

服务端启动后自动暴露 HTTP 端点：

```
GET http://localhost:9090/metrics   # Prometheus scrape
GET http://localhost:9090/health    # 健康检查
GET http://localhost:9091/          # Web 管理控制台（需登录）
```

**端口说明：**

| 端口 | 用途 | 配置项 |
|------|------|--------|
| 8881 | 客户端连接 | 启动参数 `clientPort` |
| 9881 | Raft 节点间通信 | 启动参数 `raftPort` |
| 9090 | Prometheus Metrics | `metrics.port` |
| 9091 | Web 管理控制台 | `admin.port` |

**Prometheus 配置：**

```yaml
scrape_configs:
  - job_name: 'hutulock'
    static_configs:
      - targets:
          - 'node1:9090'
          - 'node2:9091'
          - 'node3:9092'
```

---

## 指标列表

### 锁指标

| 指标名 | 类型 | 标签 | 说明 |
|--------|------|------|------|
| `hutulock_lock_acquire_total` | Counter | `result=ok\|wait\|granted_from_queue`, `node` | 锁获取次数 |
| `hutulock_lock_release_total` | Counter | `node` | 锁释放次数 |
| `hutulock_lock_expire_total` | Counter | `node` | 锁超时强制释放次数 |
| `hutulock_lock_acquire_duration` | Timer | `node` | 锁获取耗时（从请求到成功） |
| `hutulock_lock_held_current` | Gauge | `node` | 当前持锁数 |
| `hutulock_lock_waiting_current` | Gauge | `node` | 当前等待锁的请求数 |

### 会话指标

| 指标名 | 类型 | 说明 |
|--------|------|------|
| `hutulock_session_active` | Gauge | 当前活跃会话数 |
| `hutulock_session_created_total` | Counter | 会话创建总次数 |
| `hutulock_session_expired_total` | Counter | 会话过期总次数 |
| `hutulock_session_closed_total` | Counter | 会话主动关闭总次数 |
| `hutulock_session_reconnected_total` | Counter | 会话重连成功总次数 |

### ZNode 指标

| 指标名 | 类型 | 说明 |
|--------|------|------|
| `hutulock_znode_total` | Gauge | 当前 ZNode 总数 |
| `hutulock_znode_created_total` | Counter | ZNode 创建总次数 |
| `hutulock_znode_deleted_total` | Counter | ZNode 删除总次数 |
| `hutulock_watcher_registered_total` | Counter | Watcher 注册总次数 |
| `hutulock_watcher_fired_total` | Counter | Watcher 触发总次数 |

### Raft 指标

| 指标名 | 类型 | 说明 |
|--------|------|------|
| `hutulock_raft_election_total` | Counter | 发起选举次数 |
| `hutulock_raft_leader_change_total` | Counter | Leader 切换次数 |
| `hutulock_raft_propose_total` | Counter | `result=ok\|timeout\|rejected` |
| `hutulock_raft_propose_duration` | Timer | Propose 端到端延迟 |

### JVM 指标（自动注册）

- `jvm_memory_used_bytes`
- `jvm_gc_pause_seconds`
- `jvm_threads_live_threads`
- `system_cpu_usage`

---

## Grafana 告警建议

```yaml
# 锁获取成功率下降
- alert: LockAcquireSuccessRateLow
  expr: rate(hutulock_lock_acquire_total{result="ok"}[5m]) /
        rate(hutulock_lock_acquire_total[5m]) < 0.8
  for: 2m

# 锁超时过多（可能有客户端崩溃）
- alert: LockExpireTooMany
  expr: rate(hutulock_lock_expire_total[5m]) > 10
  for: 1m

# Raft Leader 频繁切换
- alert: RaftLeaderChangeTooFrequent
  expr: rate(hutulock_raft_leader_change_total[5m]) > 1
  for: 2m

# 会话过期过多（可能有网络问题）
- alert: SessionExpireTooMany
  expr: rate(hutulock_session_expired_total[5m]) > 5
  for: 2m
```
