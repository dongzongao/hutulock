#include "hutulock/hutulock_client.hpp"
#include "hutulock/heartbeat_monitor.hpp"
#include "hutulock/connection_manager.hpp"
#include <iostream>
#include <thread>
#include <chrono>
#include <vector>

using namespace hutulock;

void basic_example() {
    std::cout << "=== Basic Lock Example ===" << std::endl;
    
    boost::asio::io_context io_context;
    HutuLockClient client(io_context);
    
    client.add_node("127.0.0.1", 8881);
    client.connect();
    
    if (client.lock("resource-1")) {
        std::cout << "Lock acquired, doing work..." << std::endl;
        std::this_thread::sleep_for(std::chrono::seconds(2));
        client.unlock("resource-1");
        std::cout << "Lock released" << std::endl;
    }
    
    client.disconnect();
}

void heartbeat_monitoring_example() {
    std::cout << "\n=== Heartbeat Monitoring Example ===" << std::endl;
    
    HeartbeatMonitor::Config config;
    config.warning_threshold = 500;
    config.critical_threshold = 1000;
    config.preemptive_renew_ratio = 0.7;
    
    HeartbeatMonitor monitor(config);
    
    // 设置状态变化回调
    monitor.set_state_change_callback([](HeartbeatState old_state, HeartbeatState new_state) {
        std::cout << "State changed: ";
        
        auto print_state = [](HeartbeatState s) {
            switch (s) {
                case HeartbeatState::DISCONNECTED: return "DISCONNECTED";
                case HeartbeatState::HEALTHY: return "HEALTHY";
                case HeartbeatState::WARNING: return "WARNING";
                case HeartbeatState::CRITICAL: return "CRITICAL";
            }
            return "UNKNOWN";
        };
        
        std::cout << print_state(old_state) << " -> " << print_state(new_state) << std::endl;
    });
    
    // 模拟心跳
    std::vector<int> latencies = {50, 100, 150, 600, 800, 1200, 1500};
    
    for (int latency : latencies) {
        monitor.record_success(std::chrono::milliseconds(latency));
        std::cout << "Latency: " << latency << "ms, Avg: " 
                  << monitor.get_avg_latency_ms() << "ms" << std::endl;
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
    
    // 检查是否需要预防性续期
    int ttl_ms = 30000;
    if (monitor.should_preemptive_renew(ttl_ms)) {
        std::cout << "Preemptive renewal recommended!" << std::endl;
    }
}

void node_health_example() {
    std::cout << "\n=== Node Health Tracking Example ===" << std::endl;
    
    ConnectionManager::Config config;
    config.unhealthy_threshold = 3;
    config.healthy_threshold = 2;
    
    ConnectionManager manager(config);
    
    // 添加节点
    manager.add_node(NodeInfo("node1", "127.0.0.1", 8881));
    manager.add_node(NodeInfo("node2", "127.0.0.1", 8882));
    manager.add_node(NodeInfo("node3", "127.0.0.1", 8883));
    
    // 模拟请求
    std::cout << "Simulating requests..." << std::endl;
    
    // node1: 成功
    manager.on_request_success("node1", 50.0);
    manager.on_request_success("node1", 60.0);
    
    // node2: 部分失败
    manager.on_request_success("node2", 100.0);
    manager.on_request_failure("node2");
    manager.on_request_failure("node2");
    
    // node3: 全部失败
    manager.on_request_failure("node3");
    manager.on_request_failure("node3");
    manager.on_request_failure("node3");
    
    // 打印节点状态
    auto nodes = manager.get_all_nodes();
    for (const auto& node : nodes) {
        std::cout << "Node " << node.id << ": ";
        
        switch (node.health) {
            case NodeHealth::HEALTHY:
                std::cout << "HEALTHY";
                break;
            case NodeHealth::DEGRADED:
                std::cout << "DEGRADED";
                break;
            case NodeHealth::UNHEALTHY:
                std::cout << "UNHEALTHY";
                break;
            case NodeHealth::UNKNOWN:
                std::cout << "UNKNOWN";
                break;
        }
        
        std::cout << ", Avg Latency: " << node.avg_latency_ms << "ms"
                  << ", Failures: " << node.consecutive_failures << std::endl;
    }
    
    // 选择最佳节点
    NodeInfo best = manager.select_node();
    std::cout << "\nBest node selected: " << best.id << std::endl;
}

void optimistic_lock_example() {
    std::cout << "\n=== Optimistic Lock Example ===" << std::endl;
    
    boost::asio::io_context io_context;
    HutuLockClient client(io_context);
    
    client.add_node("127.0.0.1", 8881);
    client.connect();
    
    std::string path = "/counter";
    
    // 乐观锁更新
    bool success = client.optimistic_update(path, 5, 
        [](const std::vector<uint8_t>& data, int version) {
            // 解析当前值
            int current = 0;
            if (!data.empty()) {
                current = *reinterpret_cast<const int*>(data.data());
            }
            
            // 递增
            int new_value = current + 1;
            std::cout << "Updating counter: " << current << " -> " << new_value 
                      << " (version " << version << ")" << std::endl;
            
            // 返回新数据
            std::vector<uint8_t> new_data(sizeof(int));
            *reinterpret_cast<int*>(new_data.data()) = new_value;
            return new_data;
        }
    );
    
    if (success) {
        std::cout << "Counter updated successfully!" << std::endl;
    } else {
        std::cout << "Failed to update counter (version conflict)" << std::endl;
    }
    
    client.disconnect();
}

int main() {
    try {
        basic_example();
        heartbeat_monitoring_example();
        node_health_example();
        optimistic_lock_example();
        
        std::cout << "\n=== All examples completed ===" << std::endl;
        
    } catch (const std::exception& e) {
        std::cerr << "Error: " << e.what() << std::endl;
        return 1;
    }
    
    return 0;
}
