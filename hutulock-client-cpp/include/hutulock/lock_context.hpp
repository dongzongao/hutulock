#pragma once

#include <string>
#include <chrono>
#include <memory>
#include <functional>
#include <boost/asio.hpp>

namespace hutulock {

/**
 * 锁状态
 */
enum class LockState {
    ACQUIRING,
    ACQUIRED,
    RENEWING,
    EXPIRED,
    RELEASED
};

/**
 * 锁上下文
 * 管理单个锁的生命周期和看门狗
 */
class LockContext {
public:
    using WatchdogCallback = std::function<void(const std::string&)>;
    
    struct Config {
        std::string lock_name;
        int ttl_ms = 30000;
        int watchdog_interval_ms = 9000;
    };
    
    LockContext(boost::asio::io_context& io_context, const Config& config);
    ~LockContext();
    
    /**
     * 启动看门狗
     */
    void start_watchdog(WatchdogCallback callback);
    
    /**
     * 停止看门狗
     */
    void stop_watchdog();
    
    /**
     * 获取锁名称
     */
    std::string get_lock_name() const { return config_.lock_name; }
    
    /**
     * 获取状态
     */
    LockState get_state() const { return state_; }
    
    /**
     * 设置状态
     */
    void set_state(LockState state) { state_ = state; }
    
    /**
     * 获取获取时间
     */
    std::chrono::steady_clock::time_point get_acquired_at() const { 
        return acquired_at_; 
    }
    
    /**
     * 设置获取时间
     */
    void set_acquired_at(std::chrono::steady_clock::time_point time) {
        acquired_at_ = time;
    }
    
private:
    boost::asio::io_context& io_context_;
    Config config_;
    LockState state_ = LockState::ACQUIRING;
    std::chrono::steady_clock::time_point acquired_at_;
    
    std::unique_ptr<boost::asio::steady_timer> watchdog_timer_;
    WatchdogCallback watchdog_callback_;
    
    void schedule_watchdog();
};

} // namespace hutulock
