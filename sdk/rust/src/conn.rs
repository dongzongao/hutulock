//! 单条 TCP 连接，负责发送/接收文本行，并将响应分发给等待者。

use std::collections::HashMap;
use std::sync::Arc;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::TcpStream;
use tokio::sync::{mpsc, oneshot, Mutex};

use crate::protocol::{Message, CMD_WATCH_EVENT};
use crate::{HutuLockError, Result};

type PendingMap = HashMap<String, oneshot::Sender<Message>>;
type WatcherMap = HashMap<String, oneshot::Sender<String>>;

pub struct Conn {
    writer: Arc<Mutex<tokio::io::WriteHalf<TcpStream>>>,
    pending: Arc<Mutex<PendingMap>>,
    watchers: Arc<Mutex<WatcherMap>>,
}

impl Conn {
    /// 连接到指定地址，启动后台读取任务。
    pub async fn connect(addr: &str) -> Result<Self> {
        let stream = TcpStream::connect(addr)
            .await
            .map_err(|e| HutuLockError::Connection(e.to_string()))?;

        let (reader, writer) = tokio::io::split(stream);
        let pending: Arc<Mutex<PendingMap>> = Arc::new(Mutex::new(HashMap::new()));
        let watchers: Arc<Mutex<WatcherMap>> = Arc::new(Mutex::new(HashMap::new()));

        // 启动后台读取任务
        let pending_clone = Arc::clone(&pending);
        let watchers_clone = Arc::clone(&watchers);
        tokio::spawn(async move {
            read_loop(reader, pending_clone, watchers_clone).await;
        });

        Ok(Self {
            writer: Arc::new(Mutex::new(writer)),
            pending,
            watchers,
        })
    }

    /// 发送消息并等待对应 key 的响应。
    pub async fn request(&self, key: &str, msg: &Message) -> Result<Message> {
        let (tx, rx) = oneshot::channel();
        {
            let mut p = self.pending.lock().await;
            p.insert(key.to_string(), tx);
        }

        self.send(msg).await?;

        rx.await.map_err(|_| HutuLockError::Connection("connection closed".into()))
    }

    /// 注册一次性 watcher，返回接收事件类型的 oneshot receiver。
    pub async fn register_watcher(&self, seq_path: &str) -> oneshot::Receiver<String> {
        let (tx, rx) = oneshot::channel();
        let mut w = self.watchers.lock().await;
        w.insert(seq_path.to_string(), tx);
        rx
    }

    async fn send(&self, msg: &Message) -> Result<()> {
        let line = msg.serialize() + "\n";
        let mut w = self.writer.lock().await;
        w.write_all(line.as_bytes())
            .await
            .map_err(|e| HutuLockError::Connection(e.to_string()))
    }
}

async fn read_loop(
    reader: tokio::io::ReadHalf<TcpStream>,
    pending: Arc<Mutex<PendingMap>>,
    watchers: Arc<Mutex<WatcherMap>>,
) {
    let mut lines = BufReader::new(reader).lines();
    while let Ok(Some(line)) = lines.next_line().await {
        if line.is_empty() {
            continue;
        }
        dispatch(&line, &pending, &watchers).await;
    }
    // 连接断开，通知所有等待者
    let mut p = pending.lock().await;
    p.clear();
}

async fn dispatch(line: &str, pending: &Arc<Mutex<PendingMap>>, watchers: &Arc<Mutex<WatcherMap>>) {
    if line.starts_with(CMD_WATCH_EVENT) {
        handle_watch(line, watchers).await;
        return;
    }

    let msg = match Message::parse(line) {
        Ok(m) => m,
        Err(e) => {
            eprintln!("hutulock: parse error: {} — {}", line, e);
            return;
        }
    };

    let mut p = pending.lock().await;
    match msg.typ.as_str() {
        "CONNECTED" => complete(&mut p, "CONNECT", msg),
        "OK" => {
            let lock_name = msg.arg(0).unwrap_or("").to_string();
            let seq_path  = msg.arg(1).unwrap_or("").to_string();
            let key1 = format!("LOCK:{}", lock_name);
            let key2 = format!("RECHECK:{}", seq_path);
            // 只能发给一个等待者（克隆 msg）
            if p.contains_key(&key1) {
                complete(&mut p, &key1, msg.clone());
            } else {
                complete(&mut p, &key2, msg);
            }
        }
        "WAIT" => {
            let lock_name = msg.arg(0).unwrap_or("").to_string();
            complete(&mut p, &format!("LOCK:{}", lock_name), msg);
        }
        "RELEASED" => {
            let lock_name = msg.arg(0).unwrap_or("").to_string();
            complete(&mut p, &format!("UNLOCK:{}", lock_name), msg);
        }
        "RENEWED" => {
            let lock_name = msg.arg(0).unwrap_or("").to_string();
            complete(&mut p, &format!("RENEW:{}", lock_name), msg);
        }
        "REDIRECT" | "ERROR" => {
            // 广播给所有等待者
            let keys: Vec<String> = p.keys().cloned().collect();
            for key in keys {
                if let Some(tx) = p.remove(&key) {
                    let _ = tx.send(msg.clone());
                }
            }
        }
        _ => {}
    }
}

async fn handle_watch(line: &str, watchers: &Arc<Mutex<WatcherMap>>) {
    // 格式：WATCH_EVENT {type} {path}
    let parts: Vec<&str> = line.splitn(3, ' ').collect();
    if parts.len() < 3 {
        return;
    }
    let event_type = parts[1].to_string();
    let path = parts[2].to_string();

    let mut w = watchers.lock().await;
    if let Some(tx) = w.remove(&path) {
        let _ = tx.send(event_type);
    }
}

fn complete(pending: &mut PendingMap, key: &str, msg: Message) {
    if let Some(tx) = pending.remove(key) {
        let _ = tx.send(msg);
    }
}
