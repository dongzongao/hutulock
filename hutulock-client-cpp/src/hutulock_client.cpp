#include "hutulock/hutulock_client.hpp"
#include "hutulock/connection_manager.hpp"
#include "hutulock/heartbeat_monitor.hpp"
#include "hutulock/retry_policy.hpp"
#include "hutulock/lock_context.hpp"
#include "hutulock/protocol.hpp"
#include <stdexcept>
#include <thread>

namespace hutulock {

HutuLockClient::HutuLockClient(boost::asio::io_context& io_context, const Config& config)
    : io_context_(io_context), config_(config) {
    
    connection_manager_ = std::make_unique<ConnectionManager>();
    heartbeat_monitor_ = std::make_unique<HeartbeatMonitor>();
    retry_policy_ = std::make_unique<RetryPolicy>();
}

HutuLockClient::~HutuLockClient() {
    disconnect();
}

void HutuLockClient::add_node(const std::string& host, int port) {
    std::string node_id = host + ":" + std::to_string(port);
    NodeInfo node(node_id, host, port);
    nodes_.push_back(node);
    connection_manager_->add_node(node);
}

void HutuLockClient::connect() {
    if (nodes_.empty()) {
        throw std::runtime_error("No nodes configured");
    }
    
    // 选择节点
    NodeInfo node = connection_manager_->select_node();
    if (node.id.empty()) {
        throw std::runtime_error("No available nodes");
    }
    
    // 连接
    do_connect(node.host, node.port);
    
    // 建立 session
    session_id_ = establish_session("");
}

void HutuLockClient::disconnect() {
    // 停止所有看门狗
    std::lock_guard<std::mutex> lock(held_locks_mutex_);
    for (auto& [name, ctx] : held_locks_) {
        ctx->stop_watchdog();
    }
    held_locks_.clear();
    
    // 关闭连接
    if (socket_ && socket_->is_open()) {
        boost::system::error_code ec;
        socket_->close(ec);
    }
    socket_.reset();
    session_id_.clear();
}

bool HutuLockClient::lock(const std::string& lock_name) {
    return lock(lock_name, std::chrono::seconds(config_.lock_timeout_s));
}

bool HutuLockClient::lock(const std::string& lock_name, std::chrono::milliseconds timeout) {
    Message msg(MessageType::LOCK);
    msg.request_id = Protocol::generate_request_id();
    msg.set_header("lockName", lock_name);
    msg.set_header("sessionId", session_id_);
    msg.set_header("ttl", std::to_string(config_.watchdog_ttl_ms));
    
    std::string response = send_command(Protocol::encode(msg));
    
    // 解析响应
    // 简化实现：假设成功返回 "OK"
    if (response == "OK") {
        // 创建锁上下文并启动看门狗
        LockContext::Config ctx_config;
        ctx_config.lock_name = lock_name;
        ctx_config.ttl_ms = config_.watchdog_ttl_ms;
        ctx_config.watchdog_interval_ms = config_.watchdog_interval_ms;
        
        auto ctx = std::make_shared<LockContext>(io_context_, ctx_config);
        ctx->set_state(LockState::ACQUIRED);
        ctx->set_acquired_at(std::chrono::steady_clock::now());
        
        // 启动看门狗
        ctx->start_watchdog([this](const std::string& name) {
            // 续期
            Message renew_msg(MessageType::LOCK);
            renew_msg.request_id = Protocol::generate_request_id();
            renew_msg.set_header("lockName", name);
            renew_msg.set_header("sessionId", session_id_);
            renew_msg.set_header("ttl", std::to_string(config_.watchdog_ttl_ms));
            renew_msg.set_header("renew", "true");
            
            try {
                send_command(Protocol::encode(renew_msg));
            } catch (...) {
                // 续期失败，标记为过期
                std::lock_guard<std::mutex> lock(held_locks_mutex_);
                auto it = held_locks_.find(name);
                if (it != held_locks_.end()) {
                    it->second->set_state(LockState::EXPIRED);
                }
            }
        });
        
        std::lock_guard<std::mutex> lock(held_locks_mutex_);
        held_locks_[lock_name] = ctx;
        
        return true;
    }
    
    return false;
}

void HutuLockClient::unlock(const std::string& lock_name) {
    // 停止看门狗
    {
        std::lock_guard<std::mutex> lock(held_locks_mutex_);
        auto it = held_locks_.find(lock_name);
        if (it != held_locks_.end()) {
            it->second->stop_watchdog();
        }
    }
    
    // 发送 unlock 命令
    Message msg(MessageType::UNLOCK);
    msg.request_id = Protocol::generate_request_id();
    msg.set_header("lockName", lock_name);
    msg.set_header("sessionId", session_id_);
    
    try {
        send_command(Protocol::encode(msg));
        
        // 成功后移除
        std::lock_guard<std::mutex> lock(held_locks_mutex_);
        held_locks_.erase(lock_name);
    } catch (...) {
        // 失败时标记为过期
        std::lock_guard<std::mutex> lock(held_locks_mutex_);
        auto it = held_locks_.find(lock_name);
        if (it != held_locks_.end()) {
            it->second->set_state(LockState::EXPIRED);
        }
    }
}

bool HutuLockClient::is_connected() const {
    return socket_ && socket_->is_open() && !session_id_.empty();
}

std::pair<std::vector<uint8_t>, int> HutuLockClient::get_data(const std::string& path) {
    Message msg(MessageType::GET_DATA);
    msg.request_id = Protocol::generate_request_id();
    msg.set_header("path", path);
    msg.set_header("sessionId", session_id_);
    
    std::string response = send_command(Protocol::encode(msg));
    
    // 简化实现：解析响应
    // 实际应该解析 Message 并提取 data 和 version
    return {{}, -1};
}

bool HutuLockClient::set_data(const std::string& path, 
                              const std::vector<uint8_t>& data, 
                              int version) {
    Message msg(MessageType::SET_DATA);
    msg.request_id = Protocol::generate_request_id();
    msg.set_header("path", path);
    msg.set_header("sessionId", session_id_);
    msg.set_header("version", std::to_string(version));
    msg.body = data;
    
    std::string response = send_command(Protocol::encode(msg));
    return response == "OK";
}

bool HutuLockClient::optimistic_update(
    const std::string& path,
    int max_retries,
    std::function<std::vector<uint8_t>(const std::vector<uint8_t>&, int)> updater) {
    
    for (int attempt = 1; attempt <= max_retries; ++attempt) {
        // 读取当前数据
        auto [data, version] = get_data(path);
        if (version < 0) {
            return false;
        }
        
        // 应用更新
        std::vector<uint8_t> new_data = updater(data, version);
        
        // 尝试写入
        if (set_data(path, new_data, version)) {
            return true;
        }
        
        // 版本冲突，延迟后重试
        if (attempt < max_retries) {
            auto delay = retry_policy_->calculate_delay(attempt);
            std::this_thread::sleep_for(delay);
        }
    }
    
    return false;
}

void HutuLockClient::do_connect(const std::string& host, int port) {
    socket_ = std::make_unique<boost::asio::ip::tcp::socket>(io_context_);
    
    boost::asio::ip::tcp::resolver resolver(io_context_);
    auto endpoints = resolver.resolve(host, std::to_string(port));
    
    boost::asio::connect(*socket_, endpoints);
}

std::string HutuLockClient::establish_session(const std::string& existing_session_id) {
    Message msg(MessageType::CONNECT);
    msg.request_id = Protocol::generate_request_id();
    if (!existing_session_id.empty()) {
        msg.set_header("sessionId", existing_session_id);
    }
    
    std::string response = send_command(Protocol::encode(msg));
    
    // 简化实现：假设响应就是 session ID
    return response;
}

void HutuLockClient::handle_redirect(const std::string& leader_id) {
    // 保存当前 session
    std::string saved_session = session_id_;
    
    // 查找 leader 节点
    NodeInfo* leader = connection_manager_->find_node(leader_id);
    if (!leader) {
        throw std::runtime_error("Leader node not found: " + leader_id);
    }
    
    // 重新连接到 leader
    disconnect();
    do_connect(leader->host, leader->port);
    
    // 恢复 session
    session_id_ = establish_session(saved_session);
}

std::string HutuLockClient::send_command(const std::string& command) {
    if (!socket_ || !socket_->is_open()) {
        throw std::runtime_error("Not connected");
    }
    
    // 发送命令
    boost::asio::write(*socket_, boost::asio::buffer(command));
    
    // 读取响应
    std::vector<uint8_t> response_buf(config_.max_frame_length);
    size_t len = socket_->read_some(boost::asio::buffer(response_buf));
    
    return std::string(response_buf.begin(), response_buf.begin() + len);
}

void HutuLockClient::start_watchdog(const std::string& lock_name) {
    // 已在 lock() 方法中实现
}

void HutuLockClient::stop_watchdog(const std::string& lock_name) {
    std::lock_guard<std::mutex> lock(held_locks_mutex_);
    auto it = held_locks_.find(lock_name);
    if (it != held_locks_.end()) {
        it->second->stop_watchdog();
    }
}

} // namespace hutulock
