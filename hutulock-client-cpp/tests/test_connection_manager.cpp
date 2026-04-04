#include "hutulock/connection_manager.hpp"
#include <gtest/gtest.h>

using namespace hutulock;

TEST(ConnectionManagerTest, AddNode) {
    ConnectionManager manager;
    
    NodeInfo node("node1", "127.0.0.1", 8881);
    manager.add_node(node);
    
    auto nodes = manager.get_all_nodes();
    EXPECT_EQ(nodes.size(), 1);
    EXPECT_EQ(nodes[0].id, "node1");
}

TEST(ConnectionManagerTest, SelectNode) {
    ConnectionManager manager;
    
    manager.add_node(NodeInfo("node1", "127.0.0.1", 8881));
    manager.add_node(NodeInfo("node2", "127.0.0.1", 8882));
    
    NodeInfo selected = manager.select_node();
    EXPECT_FALSE(selected.id.empty());
}

TEST(ConnectionManagerTest, HealthTracking) {
    ConnectionManager::Config config;
    config.unhealthy_threshold = 3;
    
    ConnectionManager manager(config);
    manager.add_node(NodeInfo("node1", "127.0.0.1", 8881));
    
    // 成功请求
    manager.on_request_success("node1", 50.0);
    
    auto nodes = manager.get_all_nodes();
    EXPECT_EQ(nodes[0].health, NodeHealth::HEALTHY);
    EXPECT_EQ(nodes[0].consecutive_failures, 0);
    
    // 失败请求
    manager.on_request_failure("node1");
    manager.on_request_failure("node1");
    
    nodes = manager.get_all_nodes();
    EXPECT_EQ(nodes[0].health, NodeHealth::DEGRADED);
    EXPECT_EQ(nodes[0].consecutive_failures, 2);
    
    // 达到不健康阈值
    manager.on_request_failure("node1");
    
    nodes = manager.get_all_nodes();
    EXPECT_EQ(nodes[0].health, NodeHealth::UNHEALTHY);
    EXPECT_EQ(nodes[0].consecutive_failures, 3);
}

TEST(ConnectionManagerTest, LatencyTracking) {
    ConnectionManager manager;
    manager.add_node(NodeInfo("node1", "127.0.0.1", 8881));
    
    manager.on_request_success("node1", 100.0);
    
    auto nodes = manager.get_all_nodes();
    EXPECT_NEAR(nodes[0].avg_latency_ms, 100.0, 1.0);
    
    manager.on_request_success("node1", 200.0);
    
    nodes = manager.get_all_nodes();
    // 指数移动平均: 100 * 0.7 + 200 * 0.3 = 130
    EXPECT_NEAR(nodes[0].avg_latency_ms, 130.0, 5.0);
}

TEST(ConnectionManagerTest, FindNode) {
    ConnectionManager manager;
    manager.add_node(NodeInfo("node1", "127.0.0.1", 8881));
    manager.add_node(NodeInfo("node2", "127.0.0.1", 8882));
    
    NodeInfo* found = manager.find_node("node1");
    ASSERT_NE(found, nullptr);
    EXPECT_EQ(found->id, "node1");
    
    NodeInfo* not_found = manager.find_node("node999");
    EXPECT_EQ(not_found, nullptr);
}
