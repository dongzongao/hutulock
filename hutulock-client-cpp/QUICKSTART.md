# 快速开始指南

## 5 分钟上手 HutuLock C++ 客户端

### 1. 安装依赖

```bash
# macOS
brew install cmake boost

# Ubuntu/Debian
sudo apt-get install cmake libboost-system-dev
```

### 2. 编译

```bash
cd hutulock-client-cpp
mkdir build && cd build
cmake ..
make
```

### 3. 运行示例

```bash
# 确保 HutuLock 服务器正在运行
# 默认地址: 127.0.0.1:8881

./simple_lock_example
```

### 4. 在你的项目中使用

#### CMakeLists.txt

```cmake
find_package(Boost 1.70 REQUIRED COMPONENTS system)

add_executable(my_app main.cpp)
target_link_libraries(my_app hutulock-client Boost::system)
```

#### main.cpp

```cpp
#include "hutulock/hutulock_client.hpp"
#include <iostream>

int main() {
    boost::asio::io_context io_context;
    hutulock::HutuLockClient client(io_context);
    
    // 添加服务器节点
    client.add_node("127.0.0.1", 8881);
    
    // 连接
    client.connect();
    std::cout << "Connected! Session: " << client.get_session_id() << std::endl;
    
    // 获取锁
    if (client.lock("my-resource")) {
        std::cout << "Lock acquired!" << std::endl;
        
        // 你的业务逻辑
        // ...
        
        // 释放锁
        client.unlock("my-resource");
        std::cout << "Lock released!" << std::endl;
    } else {
        std::cout << "Failed to acquire lock" << std::endl;
    }
    
    client.disconnect();
    return 0;
}
```

#### 编译运行

```bash
g++ -std=c++17 main.cpp -lhutulock-client -lboost_system -pthread -o my_app
./my_app
```

## 常用场景

### 场景 1: 基本锁

```cpp
if (client.lock("order-123")) {
    // 处理订单
    process_order(123);
    client.unlock("order-123");
}
```

### 场景 2: 带超时的锁

```cpp
using namespace std::chrono_literals;

if (client.lock("resource", 5s)) {
    // 最多等待 5 秒
    do_work();
    client.unlock("resource");
}
```

### 场景 3: 乐观锁（计数器）

```cpp
client.optimistic_update("/counter", 5, 
    [](const std::vector<uint8_t>& data, int version) {
        int value = parse_int(data);
        return serialize_int(value + 1);
    }
);
```

### 场景 4: 多节点集群

```cpp
client.add_node("192.168.1.10", 8881);
client.add_node("192.168.1.11", 8881);
client.add_node("192.168.1.12", 8881);

client.connect(); // 自动选择最佳节点
```

### 场景 5: 自定义配置

```cpp
hutulock::HutuLockClient::Config config;
config.lock_timeout_s = 60;           // 锁超时 60 秒
config.watchdog_interval_ms = 10000;  // 看门狗间隔 10 秒
config.watchdog_ttl_ms = 30000;       // TTL 30 秒

hutulock::HutuLockClient client(io_context, config);
```

## 错误处理

```cpp
try {
    client.connect();
    
    if (client.lock("resource")) {
        // 业务逻辑
        client.unlock("resource");
    }
    
} catch (const std::exception& e) {
    std::cerr << "Error: " << e.what() << std::endl;
}
```

## 最佳实践

1. **总是释放锁**: 使用 RAII 或确保在异常情况下也能释放
2. **合理设置超时**: 避免死锁
3. **使用多节点**: 提高可用性
4. **监控心跳**: 及时发现网络问题
5. **乐观锁适用于读多写少**: 避免长时间持有锁

## RAII 锁包装器（推荐）

```cpp
class LockGuard {
    hutulock::HutuLockClient& client_;
    std::string lock_name_;
    bool locked_;
    
public:
    LockGuard(hutulock::HutuLockClient& client, const std::string& name)
        : client_(client), lock_name_(name) {
        locked_ = client_.lock(lock_name_);
    }
    
    ~LockGuard() {
        if (locked_) {
            client_.unlock(lock_name_);
        }
    }
    
    bool is_locked() const { return locked_; }
};

// 使用
{
    LockGuard guard(client, "my-lock");
    if (guard.is_locked()) {
        // 自动释放
    }
}
```

## 下一步

- 查看 [README.md](README.md) 了解完整 API
- 查看 [BUILD.md](BUILD.md) 了解构建选项
- 运行 `enhanced_lock_example` 查看高级特性
- 阅读 [CPP_CLIENT_SUMMARY.md](../CPP_CLIENT_SUMMARY.md) 了解实现细节

## 需要帮助？

- 查看示例代码: `examples/`
- 运行测试: `ctest`
- 查看 Java 客户端文档: `../docs/client-enhancements.md`
