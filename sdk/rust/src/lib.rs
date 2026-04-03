//! HutuLock 分布式锁 Rust 客户端
//!
//! 协议：纯文本行（UTF-8），与 Java/Go/Python 客户端完全兼容。
//!
//! # 用法
//!
//! ```rust,no_run
//! use hutulock::{Client, Config};
//!
//! #[tokio::main]
//! async fn main() -> hutulock::Result<()> {
//!     let client = Client::new(Config {
//!         nodes: vec!["127.0.0.1:8881".into()],
//!         ..Default::default()
//!     }).await?;
//!
//!     let token = client.lock("order-lock").await?;
//!     // do work ...
//!     client.unlock(token).await?;
//!
//!     Ok(())
//! }
//! ```

mod conn;
mod error;
mod protocol;
mod tests;

pub use error::{HutuLockError, Result};

use conn::Conn;
use protocol::*;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::Mutex;
use tokio::time::{sleep, timeout};

/// 客户端配置
#[derive(Debug, Clone)]
pub struct Config {
    /// 集群节点地址列表，格式 "host:port"
    pub nodes: Vec<String>,
    /// 连接超时，默认 5s
    pub connect_timeout: Duration,
    /// 获取锁超时，默认 30s
    pub lock_timeout: Duration,
    /// 看门狗心跳间隔，默认 10s
    pub watchdog_interval: Duration,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            nodes: vec![],
            connect_timeout: Duration::from_secs(5),
            lock_timeout: Duration::from_secs(30),
            watchdog_interval: Duration::from_secs(10),
        }
    }
}

/// 持有锁的凭证，由 [`Client::lock`] 返回，传给 [`Client::unlock`] 使用。
#[derive(Debug)]
pub struct LockToken {
    pub lock_name: String,
    pub seq_node_path: String,
    pub session_id: String,
    watchdog_stop: Option<tokio::sync::watch::Sender<bool>>,
}

impl LockToken {
    fn stop_watchdog(&mut self) {
        if let Some(tx) = self.watchdog_stop.take() {
            let _ = tx.send(true);
        }
    }
}

/// HutuLock 分布式锁客户端（async，基于 Tokio）
pub struct Client {
    cfg: Config,
    inner: Arc<Mutex<Inner>>,
}

struct Inner {
    conn: Conn,
    session_id: String,
}

impl Client {
    /// 创建并连接客户端。
    pub async fn new(cfg: Config) -> Result<Self> {
        if cfg.nodes.is_empty() {
            return Err(HutuLockError::Connection("at least one node required".into()));
        }
        let (conn, session_id) = connect_any(&cfg).await?;
        Ok(Self {
            cfg,
            inner: Arc::new(Mutex::new(Inner { conn, session_id })),
        })
    }

    /// 获取锁，返回 [`LockToken`]。超时抛出 [`HutuLockError::LockTimeout`]。
    pub async fn lock(&self, lock_name: &str) -> Result<LockToken> {
        let deadline = tokio::time::Instant::now() + self.cfg.lock_timeout;
        let mut token = self.do_acquire(lock_name, deadline).await?;
        self.start_watchdog(&mut token);
        Ok(token)
    }

    /// 释放锁，停止看门狗。
    pub async fn unlock(&self, mut token: LockToken) -> Result<()> {
        token.stop_watchdog();
        let inner = self.inner.lock().await;
        let msg = Message::new(CMD_UNLOCK, &[&token.seq_node_path, &token.session_id]);
        let key = format!("UNLOCK:{}", token.lock_name);
        match timeout(Duration::from_secs(10), inner.conn.request(&key, &msg)).await {
            Ok(Ok(_)) => {}
            _ => {
                // 超时静默处理，服务端 TTL 会清理
            }
        }
        Ok(())
    }

    /// 返回当前会话 ID。
    pub async fn session_id(&self) -> String {
        self.inner.lock().await.session_id.clone()
    }

    // ---- 内部：锁操作 ----

    async fn do_acquire(&self, lock_name: &str, deadline: tokio::time::Instant) -> Result<LockToken> {
        let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
        if remaining.is_zero() {
            return Err(HutuLockError::LockTimeout { lock_name: lock_name.into() });
        }

        let (resp, session_id) = {
            let inner = self.inner.lock().await;
            let msg = Message::new(CMD_LOCK, &[lock_name, &inner.session_id]);
            let key = format!("LOCK:{}", lock_name);
            let resp = timeout(remaining, inner.conn.request(&key, &msg))
                .await
                .map_err(|_| HutuLockError::LockTimeout { lock_name: lock_name.into() })??;
            (resp, inner.session_id.clone())
        };

        match resp.typ.as_str() {
            "REDIRECT" => {
                self.handle_redirect().await?;
                return self.do_acquire(lock_name, deadline).await;
            }
            "OK" => {
                return Ok(LockToken {
                    lock_name: lock_name.into(),
                    seq_node_path: resp.arg(1).unwrap_or("").into(),
                    session_id,
                    watchdog_stop: None,
                });
            }
            "WAIT" => {
                let seq_path = resp.arg(1).unwrap_or("").to_string();
                return self.wait_for_lock(lock_name, &seq_path, deadline).await;
            }
            _ => {
                return Err(HutuLockError::Protocol(format!(
                    "unexpected response to LOCK: {}",
                    resp.serialize()
                )));
            }
        }
    }

    async fn wait_for_lock(
        &self,
        lock_name: &str,
        seq_path: &str,
        deadline: tokio::time::Instant,
    ) -> Result<LockToken> {
        let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
        if remaining.is_zero() {
            return Err(HutuLockError::LockTimeout { lock_name: lock_name.into() });
        }

        let watch_rx = {
            let inner = self.inner.lock().await;
            inner.conn.register_watcher(seq_path).await
        };

        let event_type = timeout(remaining, watch_rx)
            .await
            .map_err(|_| HutuLockError::LockTimeout { lock_name: lock_name.into() })?
            .map_err(|_| HutuLockError::Connection("watcher channel closed".into()))?;

        if event_type == "SESSION_EXPIRED" {
            return Err(HutuLockError::SessionExpired);
        }

        self.recheck(lock_name, seq_path, deadline).await
    }

    async fn recheck(
        &self,
        lock_name: &str,
        seq_path: &str,
        deadline: tokio::time::Instant,
    ) -> Result<LockToken> {
        let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
        if remaining.is_zero() {
            return Err(HutuLockError::LockTimeout { lock_name: lock_name.into() });
        }

        let (resp, session_id) = {
            let inner = self.inner.lock().await;
            let msg = Message::new(CMD_RECHECK, &[lock_name, seq_path, &inner.session_id]);
            let key = format!("RECHECK:{}", seq_path);
            let resp = timeout(remaining, inner.conn.request(&key, &msg))
                .await
                .map_err(|_| HutuLockError::LockTimeout { lock_name: lock_name.into() })??;
            (resp, inner.session_id.clone())
        };

        match resp.typ.as_str() {
            "OK" => Ok(LockToken {
                lock_name: lock_name.into(),
                seq_node_path: resp.arg(1).unwrap_or("").into(),
                session_id,
                watchdog_stop: None,
            }),
            "WAIT" => {
                let new_seq = resp.arg(1).unwrap_or("").to_string();
                self.wait_for_lock(lock_name, &new_seq, deadline).await
            }
            _ => Err(HutuLockError::Protocol(format!(
                "unexpected response to RECHECK: {}",
                resp.serialize()
            ))),
        }
    }

    // ---- 内部：看门狗 ----

    fn start_watchdog(&self, token: &mut LockToken) {
        let (stop_tx, mut stop_rx) = tokio::sync::watch::channel(false);
        token.watchdog_stop = Some(stop_tx);

        let inner = Arc::clone(&self.inner);
        let lock_name = token.lock_name.clone();
        let interval = self.cfg.watchdog_interval;

        tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = sleep(interval) => {}
                    _ = stop_rx.changed() => break,
                }
                let guard = inner.lock().await;
                let msg = Message::new(CMD_RENEW, &[&lock_name, &guard.session_id]);
                let key = format!("RENEW:{}", lock_name);
                if let Err(e) = timeout(Duration::from_secs(5), guard.conn.request(&key, &msg)).await {
                    eprintln!("hutulock: watchdog renew failed: {} — {}", lock_name, e);
                }
            }
        });
    }

    // ---- 内部：重定向 ----

    async fn handle_redirect(&self) -> Result<()> {
        let (new_conn, new_sid) = connect_any(&self.cfg).await?;
        let mut inner = self.inner.lock().await;
        inner.conn = new_conn;
        inner.session_id = new_sid;
        Ok(())
    }
}

// ---- 连接辅助 ----

async fn connect_any(cfg: &Config) -> Result<(Conn, String)> {
    let mut last_err = HutuLockError::Connection("no nodes".into());
    for addr in &cfg.nodes {
        match timeout(cfg.connect_timeout, Conn::connect(addr)).await {
            Ok(Ok(conn)) => {
                match establish_session(&conn, None, cfg.connect_timeout).await {
                    Ok(sid) => return Ok((conn, sid)),
                    Err(e) => last_err = e,
                }
            }
            Ok(Err(e)) => last_err = e,
            Err(_) => last_err = HutuLockError::Connection(format!("connect timeout: {}", addr)),
        }
    }
    Err(last_err)
}

async fn establish_session(
    conn: &Conn,
    existing_id: Option<&str>,
    connect_timeout: Duration,
) -> Result<String> {
    let msg = match existing_id {
        Some(id) => Message::new(CMD_CONNECT, &[id]),
        None => Message::new(CMD_CONNECT, &[]),
    };
    let resp = timeout(connect_timeout, conn.request("CONNECT", &msg))
        .await
        .map_err(|_| HutuLockError::Connection("connect timeout".into()))??;

    if resp.typ != CMD_CONNECTED {
        return Err(HutuLockError::Protocol(format!(
            "unexpected response to CONNECT: {}",
            resp.serialize()
        )));
    }
    Ok(resp.arg(0).unwrap_or("").to_string())
}
