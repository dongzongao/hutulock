#include "hutulock/retry_policy.hpp"
#include <random>
#include <algorithm>

namespace hutulock {

RetryPolicy::RetryPolicy(const Config& config) : config_(config) {}

RetryDecision RetryPolicy::should_retry(const std::string& error_code, int attempt) const {
    if (attempt >= config_.max_attempts) {
        return RetryDecision::FAIL;
    }
    
    if (error_code == "NOT_LEADER" || error_code == "REDIRECT") {
        return RetryDecision::REDIRECT;
    }
    
    if (is_retryable_error(error_code)) {
        return RetryDecision::RETRY;
    }
    
    return RetryDecision::FAIL;
}

std::chrono::milliseconds RetryPolicy::calculate_delay(int attempt) const {
    if (attempt <= 0) {
        return std::chrono::milliseconds(0);
    }
    
    // 指数退避
    double delay = config_.initial_delay_ms;
    for (int i = 1; i < attempt; ++i) {
        delay *= config_.backoff_multiplier;
    }
    
    // 限制最大延迟
    delay = std::min(delay, static_cast<double>(config_.max_delay_ms));
    
    // 添加抖动
    static std::random_device rd;
    static std::mt19937 gen(rd());
    std::uniform_real_distribution<> dis(
        1.0 - config_.jitter_factor,
        1.0 + config_.jitter_factor
    );
    delay *= dis(gen);
    
    return std::chrono::milliseconds(static_cast<int>(delay));
}

bool RetryPolicy::is_retryable_error(const std::string& error_code) const {
    return error_code == "TIMEOUT" ||
           error_code == "CONNECTION_LOST" ||
           error_code == "NETWORK_ERROR" ||
           error_code == "TEMPORARY_FAILURE";
}

} // namespace hutulock
