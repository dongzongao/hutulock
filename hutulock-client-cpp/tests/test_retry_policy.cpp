#include "hutulock/retry_policy.hpp"
#include <gtest/gtest.h>

using namespace hutulock;

TEST(RetryPolicyTest, RetryableErrors) {
    RetryPolicy policy;
    
    EXPECT_EQ(policy.should_retry("TIMEOUT", 1), RetryDecision::RETRY);
    EXPECT_EQ(policy.should_retry("CONNECTION_LOST", 1), RetryDecision::RETRY);
    EXPECT_EQ(policy.should_retry("NETWORK_ERROR", 1), RetryDecision::RETRY);
}

TEST(RetryPolicyTest, RedirectErrors) {
    RetryPolicy policy;
    
    EXPECT_EQ(policy.should_retry("NOT_LEADER", 1), RetryDecision::REDIRECT);
    EXPECT_EQ(policy.should_retry("REDIRECT", 1), RetryDecision::REDIRECT);
}

TEST(RetryPolicyTest, NonRetryableErrors) {
    RetryPolicy policy;
    
    EXPECT_EQ(policy.should_retry("INVALID_REQUEST", 1), RetryDecision::FAIL);
    EXPECT_EQ(policy.should_retry("PERMISSION_DENIED", 1), RetryDecision::FAIL);
}

TEST(RetryPolicyTest, MaxAttempts) {
    RetryPolicy::Config config;
    config.max_attempts = 3;
    
    RetryPolicy policy(config);
    
    EXPECT_EQ(policy.should_retry("TIMEOUT", 1), RetryDecision::RETRY);
    EXPECT_EQ(policy.should_retry("TIMEOUT", 2), RetryDecision::RETRY);
    EXPECT_EQ(policy.should_retry("TIMEOUT", 3), RetryDecision::FAIL);
}

TEST(RetryPolicyTest, ExponentialBackoff) {
    RetryPolicy::Config config;
    config.initial_delay_ms = 100;
    config.backoff_multiplier = 2.0;
    config.jitter_factor = 0.0; // 禁用抖动以便测试
    
    RetryPolicy policy(config);
    
    auto delay1 = policy.calculate_delay(1);
    auto delay2 = policy.calculate_delay(2);
    auto delay3 = policy.calculate_delay(3);
    
    EXPECT_NEAR(delay1.count(), 100, 10);
    EXPECT_NEAR(delay2.count(), 200, 20);
    EXPECT_NEAR(delay3.count(), 400, 40);
}

TEST(RetryPolicyTest, MaxDelay) {
    RetryPolicy::Config config;
    config.initial_delay_ms = 100;
    config.backoff_multiplier = 2.0;
    config.max_delay_ms = 500;
    config.jitter_factor = 0.0;
    
    RetryPolicy policy(config);
    
    auto delay10 = policy.calculate_delay(10);
    
    // 100 * 2^9 = 51200, 但限制为 500
    EXPECT_LE(delay10.count(), 500);
}
