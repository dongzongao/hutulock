#pragma once

#include <string>
#include <vector>
#include <memory>
#include <functional>
#include <chrono>
#include <boost/asio.hpp>

namespace hutulock {

// Forward declarations
class ConnectionManager;
class HeartbeatMonitor;
class RetryPolicy;
class LockContext;

/**
 * HutuLock 分布式锁 C++ 客户端
 * 
 * 核心特性:
 * - Session 会话管理
 * - 自动重连和故障转移
 * - 看门狗自动续期
 * - Watcher 事件驱动
 * - 乐观锁支持
 * 
 * 使用示例:
 * @code
 * HutuLockClient client;
 * client.add_node("127.0.0.1", 8881);
 * client.connect();
 * 
 * if (client.lock("order-lock")) {
 *     // 临界区
 *     client.unlock("order-lock");
 * }
 * @endcode
 */
class HutuLockClient {
public:
    struct Config {
        int connect_timeout_ms = 3000;
        int lock_timeout_s = 30;
        int watchdog_interval_ms = 9000;
        int watchdog_ttl_ms = 30000;
        int max_frame_length = 4096;
    };

    struct NodeInfo {
        std::string id;
        std::string host;
        int port;
    };

    /**
     * 构造函数
     * @param io_context Boost.Asio IO context
     * @param config 客户端配置
     */
    explicit HutuLockClient(
        boost::asio::io_context& io_context,
        const Config& config = Config{}
    );

    ~HutuLockClient();

    // 禁止拷贝
    HutuLockClient(const HutuLockClient&) = delete;
    HutuLockClient& operator=(const HutuLockClient&) = delete;

    /**
     * 添加服务器节点
     * @param host 主机地址
     * @param port 端口号
     */
    void add_node(const std::string& host, int port);

    /**
     * 连接到集群
     * @throws std::runtime_error 连接失败
     */
    void connect();

    /**
     * 断开连接
     */
    void disconnect();

    /**
     * 获取锁（简单 API）
     * @param lock_name 锁名称
     * @return true 获取成功，false 超时
     */
    bool lock(const std::string& lock_name);

    /**
     * 获取锁（带超时）
     * @param lock_name 锁名称
     * @param timeout 超时时间
     * @return true 获取成功，false 超时
     */
    bool lock(const std::string& lock_name, 
              std::chrono::milliseconds timeout);

    /**
     * 释放锁
     * @param lock_name 锁名称
     */
    void unlock(const std::string& lock_name);

    /**
     * 获取 session ID
     */
    std::string get_session_id() const { return session_id_; }

    /**
     * 检查是否已连接
     */
    bool is_connected() const;

    /**
     * 乐观锁：读取数据和版本
     * @param path ZNode 路径
     * @return pair<data, version>，失败返回空
     */
    std::pair<std::vector<uint8_t>, int> get_data(const std::string& path);

    /**
     * 乐观锁：写入数据（带版本检查）
     * @param path ZNode 路径
     * @param data 数据
     * @param version 期望的版本号
     * @return true 成功，false 版本冲突
     */
    bool set_data(const std::string& path, 
                  const std::vector<uint8_t>& data, 
                  int version);

    /**
     * 乐观锁：自动重试更新
     * @param path ZNode 路径
     * @param max_retries 最大重试次数
     * @param updater 更新函数
     * @return true 成功，false 失败
     */
    bool optimistic_update(
        const std::string& path,
        int max_retries,
        std::function<std::vector<uint8_t>(const std::vector<uint8_t>&, int)> updater
    );

private:
    boost::asio::io_context& io_context_;
    Config config_;
    std::vector<NodeInfo> nodes_;
    
    std::unique_ptr<boost::asio::ip::tcp::socket> socket_;
    std::string session_id_;
    
    std::unique_ptr<ConnectionManager> connection_manager_;
    std::unique_ptr<HeartbeatMonitor> heartbeat_monitor_;
    std::unique_ptr<RetryPolicy> retry_policy_;
    
    // 持有的锁上下文
    std::map<std::string, std::shared_ptr<LockContext>> held_locks_;
    std::mutex held_locks_mutex_;
    
    // 内部方法
    void do_connect(const std::string& host, int port);
    std::string establish_session(const std::string& existing_session_id = "");
    void handle_redirect(const std::string& leader_id);
    
    std::string send_command(const std::string& command);
    void start_watchdog(const std::string& lock_name);
    void stop_watchdog(const std::string& lock_name);
};

} // namespace hutulock
