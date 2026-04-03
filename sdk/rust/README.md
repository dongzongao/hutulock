# hutulock-rust

HutuLock 分布式锁 Rust 客户端，基于 Tokio 异步运行时。

## 依赖

```toml
[dependencies]
hutulock = { path = "." }
tokio = { version = "1", features = ["full"] }
```

## 用法

```rust
use hutulock::{Client, Config};
use std::time::Duration;

#[tokio::main]
async fn main() -> hutulock::Result<()> {
    let client = Client::new(Config {
        nodes: vec!["127.0.0.1:8881".into(), "127.0.0.1:8882".into()],
        lock_timeout: Duration::from_secs(30),
        watchdog_interval: Duration::from_secs(10),
        ..Default::default()
    }).await?;

    // 获取锁
    let token = client.lock("order-lock").await?;

    // do work ...

    // 释放锁
    client.unlock(token).await?;

    Ok(())
}
```

## 运行测试

```bash
cargo test
```
