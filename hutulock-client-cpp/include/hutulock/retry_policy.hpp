#pragma once

#include <chrono>
#include <string>

namespace hutulock {

/**
 * 重试决策
 */
enum class RetryDecision {
    RETRY,
    FAIL,
    REDIRECT
};

/**
 * 重试策略
 * 指数退避 + 抖动
 */
class RetryPolicy {
public:
    struct Config {
        int max_attempts = 3;
        int initial_delay_ms = 100;
        double backoff_multiplier = 2.0;
        int max_delay_ms = 5000;
        double jitter_factor = 0.1;
    };
    
    explicit RetryPolicy(const Config& config = Config{});
    
    /**
     * 判断是否应该重试
     * @param error_code 错误码
     * @param attempt 当前尝试次数（从 1 开始）
     * @return 重试决策
     */
    RetryDecision should_retry(const std::string& error_code, int attempt) const;
    
    /**
     * 计算延迟时间
     * @param attempt 当前尝试次数（从 1 开始）
     * @return 延迟时间（毫秒）
     */
    std::chrono::milliseconds calculate_delay(int attempt) const;
    
    /**
     * 获取最大尝试次数
     */
    int get_max_attempts() const { return config_.max_attempts; }
    
private:
    Config config_;
    
    bool is_retryable_error(const std::string& error_code) const;
};

} // namespace hutulock
