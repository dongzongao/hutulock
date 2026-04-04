#include "hutulock/heartbeat_monitor.hpp"

namespace hutulock {

HeartbeatMonitor::HeartbeatMonitor(const Config& config) : config_(config) {}

void HeartbeatMonitor::record_success(std::chrono::milliseconds latency) {
    std::lock_guard<std::mutex> lock(mutex_);
    
    double latency_ms = latency.count();
    
    // 更新平均延迟（指数移动平均）
    if (success_count_ == 0) {
        avg_latency_ms_ = latency_ms;
    } else {
        avg_latency_ms_ = avg_latency_ms_ * 0.7 + latency_ms * 0.3;
    }
    success_count_++;
    
    // 状态转换
    HeartbeatState new_state;
    if (avg_latency_ms_ >= config_.critical_threshold) {
        new_state = HeartbeatState::CRITICAL;
    } else if (avg_latency_ms_ >= config_.warning_threshold) {
        new_state = HeartbeatState::WARNING;
    } else {
        new_state = HeartbeatState::HEALTHY;
    }
    
    if (new_state != state_) {
        transition_to(new_state);
    }
}

void HeartbeatMonitor::record_failure() {
    std::lock_guard<std::mutex> lock(mutex_);
    
    if (state_ != HeartbeatState::DISCONNECTED) {
        transition_to(HeartbeatState::DISCONNECTED);
    }
}

HeartbeatState HeartbeatMonitor::get_state() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return state_;
}

double HeartbeatMonitor::get_avg_latency_ms() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return avg_latency_ms_;
}

bool HeartbeatMonitor::should_preemptive_renew(int ttl_ms) const {
    std::lock_guard<std::mutex> lock(mutex_);
    
    if (state_ == HeartbeatState::DISCONNECTED) {
        return false;
    }
    
    // 如果延迟超过 TTL 的阈值比例，触发预防性续期
    return avg_latency_ms_ >= ttl_ms * config_.preemptive_renew_ratio;
}

void HeartbeatMonitor::set_state_change_callback(StateChangeCallback callback) {
    std::lock_guard<std::mutex> lock(mutex_);
    state_change_callback_ = callback;
}

void HeartbeatMonitor::transition_to(HeartbeatState new_state) {
    HeartbeatState old_state = state_;
    state_ = new_state;
    
    if (state_change_callback_) {
        state_change_callback_(old_state, new_state);
    }
}

} // namespace hutulock
