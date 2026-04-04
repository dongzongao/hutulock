#include "hutulock/hutulock_client.hpp"
#include <iostream>
#include <thread>
#include <chrono>

using namespace hutulock;

int main() {
    try {
        // 创建 IO context
        boost::asio::io_context io_context;
        
        // 创建客户端
        HutuLockClient client(io_context);
        
        // 添加节点
        client.add_node("127.0.0.1", 8881);
        client.add_node("127.0.0.1", 8882);
        client.add_node("127.0.0.1", 8883);
        
        // 连接
        std::cout << "Connecting to cluster..." << std::endl;
        client.connect();
        std::cout << "Connected! Session ID: " << client.get_session_id() << std::endl;
        
        // 获取锁
        std::cout << "\nAcquiring lock 'order-lock'..." << std::endl;
        if (client.lock("order-lock")) {
            std::cout << "Lock acquired!" << std::endl;
            
            // 模拟业务处理
            std::cout << "Processing order..." << std::endl;
            std::this_thread::sleep_for(std::chrono::seconds(5));
            
            // 释放锁
            std::cout << "Releasing lock..." << std::endl;
            client.unlock("order-lock");
            std::cout << "Lock released!" << std::endl;
        } else {
            std::cout << "Failed to acquire lock (timeout)" << std::endl;
        }
        
        // 断开连接
        client.disconnect();
        std::cout << "\nDisconnected." << std::endl;
        
    } catch (const std::exception& e) {
        std::cerr << "Error: " << e.what() << std::endl;
        return 1;
    }
    
    return 0;
}
