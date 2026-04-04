#include "hutulock/lock_context.hpp"

namespace hutulock {

LockContext::LockContext(boost::asio::io_context& io_context, const Config& config)
    : io_context_(io_context), config_(config) {}

LockContext::~LockContext() {
    stop_watchdog();
}

void LockContext::start_watchdog(WatchdogCallback callback) {
    watchdog_callback_ = callback;
    watchdog_timer_ = std::make_unique<boost::asio::steady_timer>(io_context_);
    schedule_watchdog();
}

void LockContext::stop_watchdog() {
    if (watchdog_timer_) {
        watchdog_timer_->cancel();
        watchdog_timer_.reset();
    }
}

void LockContext::schedule_watchdog() {
    if (!watchdog_timer_ || !watchdog_callback_) {
        return;
    }
    
    watchdog_timer_->expires_after(
        std::chrono::milliseconds(config_.watchdog_interval_ms)
    );
    
    watchdog_timer_->async_wait([this](const boost::system::error_code& ec) {
        if (ec) {
            return; // 定时器被取消
        }
        
        // 调用续期回调
        if (watchdog_callback_) {
            watchdog_callback_(config_.lock_name);
        }
        
        // 重新调度
        schedule_watchdog();
    });
}

} // namespace hutulock
