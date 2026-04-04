#pragma once

#include <chrono>
#include <functional>
#include <mutex>

namespace hutulock {

/**
 * 心跳监控器状态
 */
enum class HeartbeatState {
    DISCONNECTED,
    HEALTHY,
    WARNING,
    CRITICAL
};

/**
 * 心跳监控器
 * 监控心跳延迟，触发预防性续期
 */
class HeartbeatMonitor {
public:
    struct Config {
        int warning_threshold = 500;
        int critical_threshold = 1000;
        double preemptive_renew_ratio = 0.7;
    };
    
    using StateChangeCallback = std::function<void(HeartbeatState, HeartbeatState)>;
    
    explicit HeartbeatMonitor(const Config& config = Config{});
    
    /**
     * 记录心跳成功
     */
    void record_success(std::chrono::milliseconds latency);
    
    /**
     * 记录心跳失败
     */
    void record_failure();
    
    /**
     * 获取当前状态
     */
    HeartbeatState get_state() const;
    
    /**
     * 获取平均延迟
     */
    double get_avg_latency_ms() const;
    
    /**
     * 是否需要预防性续期
     */
    bool should_preemptive_renew(int ttl_ms) const;
    
    /**
     * 设置状态变化回调
     */
    void set_state_change_callback(StateChangeCallback callback);
    
private:
    Config config_;
    HeartbeatState state_ = HeartbeatState::DISCONNECTED;
    double avg_latency_ms_ = 0.0;
    int success_count_ = 0;
    StateChangeCallback state_change_callback_;
    mutable std::mutex mutex_;
    
    void transition_to(HeartbeatState new_state);
};

} // namespace hutulock
