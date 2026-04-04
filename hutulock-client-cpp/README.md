# HutuLock C++ Client

C++ 客户端库，用于连接 HutuLock 分布式锁服务。

## 特性

- ✅ Session 会话管理
- ✅ 自动重连和故障转移
- ✅ 看门狗自动续期
- ✅ 心跳监控和预防性续期
- ✅ 节点健康检查
- ✅ 乐观锁支持
- ✅ 指数退避重试策略
- ✅ 异步 I/O（Boost.Asio）

## 依赖

- C++17 或更高版本
- CMake 3.14+
- Boost 1.70+（仅需 Boost.Asio 和 Boost.System）

## 构建

### 安装依赖

**Ubuntu/Debian:**
```bash
sudo apt-get install cmake libboost-system-dev
```

**macOS:**
```bash
brew install cmake boost
```

**Windows:**
下载并安装 [Boost](https://www.boost.org/users/download/)

### 编译

```bash
cd hutulock-client-cpp
mkdir build && cd build
cmake ..
make
```

### 编译选项

```bash
# 不编译示例
cmake -DBUILD_EXAMPLES=OFF ..

# 不编译测试
cmake -DBUILD_TESTS=OFF ..
```

## 快速开始

### 基本用法

```cpp
#include "hutulock/hutulock_client.hpp"

int main() {
    boost::asio::io_context io_context;
    hutulock::HutuLockClient client(io_context);
    
    // 添加节点
    client.add_node("127.0.0.1", 8881);
    
    // 连接
    client.connect();
    
    // 获取锁
    if (client.lock("my-lock")) {
        // 临界区
        // ...
        
        // 释放锁
        client.unlock("my-lock");
    }
    
    client.disconnect();
    return 0;
}
```

### 自定义配置

```cpp
hutulock::HutuLockClient::Config config;
config.connect_timeout_ms = 5000;
config.lock_timeout_s = 60;
config.watchdog_interval_ms = 10000;
config.watchdog_ttl_ms = 30000;

hutulock::HutuLockClient client(io_context, config);
```

### 心跳监控

```cpp
hutulock::HeartbeatMonitor::Config hb_config;
hb_config.warning_threshold = 500;
hb_config.critical_threshold = 1000;
hb_config.preemptive_renew_ratio = 0.7;

hutulock::HeartbeatMonitor monitor(hb_config);

// 设置状态变化回调
monitor.set_state_change_callback([](auto old_state, auto new_state) {
    std::cout << "Heartbeat state changed" << std::endl;
});

// 记录心跳
monitor.record_success(std::chrono::milliseconds(100));
```

### 乐观锁

```cpp
// 自动重试更新
bool success = client.optimistic_update("/counter", 5,
    [](const std::vector<uint8_t>& data, int version) {
        // 读取当前值
        int current = parse_int(data);
        
        // 更新
        int new_value = current + 1;
        
        // 返回新数据
        return serialize_int(new_value);
    }
);
```

## 示例程序

编译后，示例程序位于 `build/` 目录：

```bash
# 简单示例
./simple_lock_example

# 增强示例（包含心跳监控、节点健康、乐观锁）
./enhanced_lock_example
```

## API 文档

### HutuLockClient

主客户端类。

**方法:**

- `void add_node(const std::string& host, int port)` - 添加服务器节点
- `void connect()` - 连接到集群
- `void disconnect()` - 断开连接
- `bool lock(const std::string& lock_name)` - 获取锁（使用默认超时）
- `bool lock(const std::string& lock_name, std::chrono::milliseconds timeout)` - 获取锁（自定义超时）
- `void unlock(const std::string& lock_name)` - 释放锁
- `std::string get_session_id() const` - 获取 session ID
- `bool is_connected() const` - 检查连接状态
- `std::pair<std::vector<uint8_t>, int> get_data(const std::string& path)` - 读取数据和版本
- `bool set_data(const std::string& path, const std::vector<uint8_t>& data, int version)` - 写入数据（带版本检查）
- `bool optimistic_update(...)` - 乐观锁自动重试更新

### HeartbeatMonitor

心跳监控器。

**方法:**

- `void record_success(std::chrono::milliseconds latency)` - 记录成功心跳
- `void record_failure()` - 记录失败心跳
- `HeartbeatState get_state() const` - 获取当前状态
- `double get_avg_latency_ms() const` - 获取平均延迟
- `bool should_preemptive_renew(int ttl_ms) const` - 是否需要预防性续期
- `void set_state_change_callback(StateChangeCallback)` - 设置状态变化回调

### ConnectionManager

连接管理器。

**方法:**

- `void add_node(const NodeInfo& node)` - 添加节点
- `NodeInfo select_node()` - 选择最佳节点
- `void on_request_success(const std::string& node_id, double latency_ms)` - 记录请求成功
- `void on_request_failure(const std::string& node_id)` - 记录请求失败
- `std::vector<NodeInfo> get_all_nodes() const` - 获取所有节点

## 架构

```
hutulock-client-cpp/
├── include/hutulock/          # 公共头文件
│   ├── hutulock_client.hpp    # 主客户端
│   ├── connection_manager.hpp # 连接管理
│   ├── heartbeat_monitor.hpp  # 心跳监控
│   ├── lock_context.hpp       # 锁上下文
│   ├── protocol.hpp           # 协议编解码
│   └── retry_policy.hpp       # 重试策略
├── src/                       # 实现文件
├── examples/                  # 示例程序
└── tests/                     # 单元测试
```

## 线程安全

- `HutuLockClient` 的所有公共方法都是线程安全的
- 可以在多个线程中同时调用不同的锁操作
- 内部使用互斥锁保护共享状态

## 性能

- 异步 I/O，低延迟
- 连接池和连接复用
- 智能节点选择（基于延迟和健康度）
- 预防性续期减少锁丢失风险

## 许可证

Apache License 2.0
