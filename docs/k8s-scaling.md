# HutuLock Kubernetes 半自动伸缩

## 目标

HutuLock 的伸缩必须同时满足两件事：

1. Kubernetes 创建或回收 Pod
2. Raft 集群完成成员变更

只修改 StatefulSet 的 `replicas` 不够，因为 Raft 成员列表不会自动跟着 Kubernetes 副本数变化。

## 当前推荐方式

- StatefulSet 负责 Pod 生命周期
- `POST /api/admin/members/add` / `POST /api/admin/members/remove` 负责成员变更
- `k8s/reconcile-members.sh` 负责把两者串起来

脚本行为：

- 扩容：
  1. 先把 StatefulSet 扩到目标副本数
  2. 等新 Pod Ready
  3. 调 Leader 的 `/api/admin/members/add`
  4. 等待 `membershipChangePending=false`

- 缩容：
  1. 先调 Leader 的 `/api/admin/members/remove`
  2. 等待成员变更完成
  3. 再缩 StatefulSet

## 前置要求

- K8s 中需要暴露 Admin API Service：`hutulock-admin-api`
- 服务端 Pod 需要固定 Raft 端口 `9881`
- 初始集群通过 `BOOTSTRAP_REPLICAS` 建立最小引导连接
- 成员变更日志会携带新增成员地址，节点在 apply 时自动补齐 peer 连接

## 运行脚本

```bash
cd k8s
RUN_ONCE=true TARGET_REPLICAS=5 ./reconcile-members.sh
```

常用变量：

- `NAMESPACE`
- `STATEFULSET`
- `HEADLESS_SERVICE`
- `ADMIN_SERVICE`
- `TARGET_REPLICAS`
- `ADMIN_USERNAME`
- `ADMIN_PASSWORD`
- `RUN_ONCE`

如果不设置 `TARGET_REPLICAS`，脚本会把当前 StatefulSet 的 `spec.replicas` 视为目标值，循环持续对齐。

## 指标触发建议

建议把以下指标作为扩容信号，而不是只看 CPU：

- `hutulock_lock_waiting_current`
- `hutulock_lock_acquire_duration`
- `hutulock_raft_propose_duration`
- `hutulock_lock_held_current`
- `system_cpu_usage`

建议把以下条件同时满足一段时间后再考虑缩容：

- `hutulock_lock_waiting_current` 接近 0
- `hutulock_lock_acquire_duration` P95/P99 稳定
- `hutulock_raft_leader_change_total` 没有异常增长
- CPU 与内存连续低位

## KEDA 边界

不建议把 KEDA 或 HPA 直接绑定到 HutuLock StatefulSet：

- KEDA/HPA 只能改副本数
- HutuLock 还需要串行执行 Raft 成员变更
- 二者直接绑定会出现 Pod 已创建但成员未加入，或者 Pod 已被删但成员未移除

更安全的做法：

1. Prometheus 告警或外部策略决定目标成员数
2. 把目标成员数写入控制平面，例如 `TARGET_REPLICAS`
3. 由 `reconcile-members.sh` 或后续的专用 controller 串行执行扩缩容

## 后续升级方向

- 将 `reconcile-members.sh` 升级为专用 Deployment/Operator
- 使用 CRD 表达期望成员数，而不是直接写 StatefulSet replicas
- 把成员变更状态暴露成独立指标，方便自动控制器观测
