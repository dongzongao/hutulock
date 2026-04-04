#include "hutulock/connection_manager.hpp"
#include <algorithm>
#include <limits>

namespace hutulock {

ConnectionManager::ConnectionManager(const Config& config) : config_(config) {}

void ConnectionManager::add_node(const NodeInfo& node) {
    std::lock_guard<std::mutex> lock(mutex_);
    nodes_.push_back(node);
}

NodeInfo ConnectionManager::select_node() {
    std::lock_guard<std::mutex> lock(mutex_);
    
    if (nodes_.empty()) {
        return NodeInfo();
    }
    
    // 优先选择当前节点（如果健康）
    if (!current_node_id_.empty()) {
        auto it = std::find_if(nodes_.begin(), nodes_.end(),
            [this](const NodeInfo& n) { return n.id == current_node_id_; });
        
        if (it != nodes_.end() && it->health != NodeHealth::UNHEALTHY) {
            return *it;
        }
    }
    
    // 选择得分最高的健康节点
    NodeInfo* best = nullptr;
    double best_score = -std::numeric_limits<double>::infinity();
    
    for (auto& node : nodes_) {
        if (node.health == NodeHealth::UNHEALTHY) {
            continue;
        }
        
        double score = calculate_score(node);
        if (score > best_score) {
            best_score = score;
            best = &node;
        }
    }
    
    if (best) {
        current_node_id_ = best->id;
        return *best;
    }
    
    // 所有节点都不健康，返回第一个
    current_node_id_ = nodes_[0].id;
    return nodes_[0];
}

void ConnectionManager::update_node_health(const std::string& node_id, 
                                           bool success, 
                                           double latency_ms) {
    std::lock_guard<std::mutex> lock(mutex_);
    
    auto it = std::find_if(nodes_.begin(), nodes_.end(),
        [&node_id](const NodeInfo& n) { return n.id == node_id; });
    
    if (it == nodes_.end()) {
        return;
    }
    
    if (success) {
        // 更新平均延迟
        if (it->avg_latency_ms == 0.0) {
            it->avg_latency_ms = latency_ms;
        } else {
            it->avg_latency_ms = it->avg_latency_ms * 0.7 + latency_ms * 0.3;
        }
        it->consecutive_failures = 0;
    } else {
        it->consecutive_failures++;
    }
    
    update_health_status(*it);
}

void ConnectionManager::on_request_success(const std::string& node_id, double latency_ms) {
    update_node_health(node_id, true, latency_ms);
}

void ConnectionManager::on_request_failure(const std::string& node_id) {
    update_node_health(node_id, false, 0.0);
}

std::vector<NodeInfo> ConnectionManager::get_all_nodes() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return nodes_;
}

NodeInfo* ConnectionManager::find_node(const std::string& node_id) {
    std::lock_guard<std::mutex> lock(mutex_);
    
    auto it = std::find_if(nodes_.begin(), nodes_.end(),
        [&node_id](const NodeInfo& n) { return n.id == node_id; });
    
    return it != nodes_.end() ? &(*it) : nullptr;
}

void ConnectionManager::update_health_status(NodeInfo& node) {
    NodeHealth old_health = node.health;
    
    if (node.consecutive_failures >= config_.unhealthy_threshold) {
        node.health = NodeHealth::UNHEALTHY;
    } else if (node.consecutive_failures >= 2) {
        node.health = NodeHealth::DEGRADED;
    } else if (node.consecutive_failures == 0) {
        node.health = NodeHealth::HEALTHY;
    }
}

double ConnectionManager::calculate_score(const NodeInfo& node) const {
    double score = 100.0;
    
    // 健康状态权重
    switch (node.health) {
        case NodeHealth::HEALTHY:
            score += 50.0;
            break;
        case NodeHealth::DEGRADED:
            score += 20.0;
            break;
        case NodeHealth::UNKNOWN:
            score += 30.0;
            break;
        case NodeHealth::UNHEALTHY:
            score = 0.0;
            break;
    }
    
    // 延迟惩罚
    if (node.avg_latency_ms > 0) {
        score -= node.avg_latency_ms * config_.latency_weight;
    }
    
    return score;
}

} // namespace hutulock
