#include "hutulock/heartbeat_monitor.hpp"
#include <gtest/gtest.h>

using namespace hutulock;

TEST(HeartbeatMonitorTest, InitialState) {
    HeartbeatMonitor monitor;
    EXPECT_EQ(monitor.get_state(), HeartbeatState::DISCONNECTED);
    EXPECT_EQ(monitor.get_avg_latency_ms(), 0.0);
}

TEST(HeartbeatMonitorTest, SuccessTransition) {
    HeartbeatMonitor monitor;
    
    monitor.record_success(std::chrono::milliseconds(100));
    EXPECT_EQ(monitor.get_state(), HeartbeatState::HEALTHY);
    EXPECT_NEAR(monitor.get_avg_latency_ms(), 100.0, 1.0);
}

TEST(HeartbeatMonitorTest, WarningState) {
    HeartbeatMonitor::Config config;
    config.warning_threshold = 500;
    config.critical_threshold = 1000;
    
    HeartbeatMonitor monitor(config);
    
    monitor.record_success(std::chrono::milliseconds(600));
    EXPECT_EQ(monitor.get_state(), HeartbeatState::WARNING);
}

TEST(HeartbeatMonitorTest, CriticalState) {
    HeartbeatMonitor::Config config;
    config.warning_threshold = 500;
    config.critical_threshold = 1000;
    
    HeartbeatMonitor monitor(config);
    
    monitor.record_success(std::chrono::milliseconds(1200));
    EXPECT_EQ(monitor.get_state(), HeartbeatState::CRITICAL);
}

TEST(HeartbeatMonitorTest, FailureTransition) {
    HeartbeatMonitor monitor;
    
    monitor.record_success(std::chrono::milliseconds(100));
    EXPECT_EQ(monitor.get_state(), HeartbeatState::HEALTHY);
    
    monitor.record_failure();
    EXPECT_EQ(monitor.get_state(), HeartbeatState::DISCONNECTED);
}

TEST(HeartbeatMonitorTest, PreemptiveRenew) {
    HeartbeatMonitor::Config config;
    config.preemptive_renew_ratio = 0.7;
    
    HeartbeatMonitor monitor(config);
    
    monitor.record_success(std::chrono::milliseconds(100));
    
    // 100ms < 30000ms * 0.7 = 21000ms
    EXPECT_FALSE(monitor.should_preemptive_renew(30000));
    
    monitor.record_success(std::chrono::milliseconds(25000));
    
    // 25000ms > 21000ms
    EXPECT_TRUE(monitor.should_preemptive_renew(30000));
}

TEST(HeartbeatMonitorTest, StateChangeCallback) {
    HeartbeatMonitor monitor;
    
    bool callback_called = false;
    HeartbeatState old_state, new_state;
    
    monitor.set_state_change_callback([&](HeartbeatState o, HeartbeatState n) {
        callback_called = true;
        old_state = o;
        new_state = n;
    });
    
    monitor.record_success(std::chrono::milliseconds(100));
    
    EXPECT_TRUE(callback_called);
    EXPECT_EQ(old_state, HeartbeatState::DISCONNECTED);
    EXPECT_EQ(new_state, HeartbeatState::HEALTHY);
}
