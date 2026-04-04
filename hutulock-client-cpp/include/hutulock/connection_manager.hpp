#pragma once

#include <string>
#include <vector>
#include <map>
#include <mutex>
#include <chrono>

namespace hutulock {

/**
 * 节点健康状态
 */
enum class NodeHealth {
    UNKNOWN,
    HEALTHY,
    DEGRADED,
    UNHEALTHY
};

/**
 * 节点信息
 */
struct NodeInfo {
    std::string id;
    std::string host;
    int port;
    NodeHealth health = NodeHealth::UNKNOWN;
    double avg_latency_ms = 0.0;
    int consecutive_failures = 0;
    
    NodeInfo() = default;
    NodeInfo(const std::string& id_, const std::string& host_, int port_)
        : id(id_), host(host_), port(port_) {}
};

/**
 * 连接管理器
 * 负责节点选择、健康检查、故障转移
 */
class ConnectionManager {
public:
    struct Config {
        int unhealthy_threshold = 3;
        int healthy_threshold = 2;
        int circuit_breaker_timeout_ms = 5000;
        double latency_weight = 0.3;
    };
    
    explicit ConnectionManager(const Config& config = Config{});
    
    /**
     * 添加节点
     */
    void add_node(const NodeInfo& node);
    
    /**
     * 选择最佳节点
     */
    NodeInfo select_node();
    
    /**
     * 更新节点健康状态
     */
    void update_node_health(const std::string& node_id, bool success, double latency_ms);
    
    /**
     * 记录请求成功
     */
    void on_request_success(const std::string& node_id, double latency_ms);
    
    /**
     * 记录请求失败
     */
    void on_request_failure(const std::string& node_id);
    
    /**
     * 获取所有节点
     */
    std::vector<NodeInfo> get_all_nodes() const;
    
    /**
     * 根据 ID 查找节点
     */
    NodeInfo* find_node(const std::string& node_id);
    
private:
    Config config_;
    std::vector<NodeInfo> nodes_;
    std::string current_node_id_;
    mutable std::mutex mutex_;
    
    void update_health_status(NodeInfo& node);
    double calculate_score(const NodeInfo& node) const;
};

} // namespace hutulock
