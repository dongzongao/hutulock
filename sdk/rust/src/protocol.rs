//! HutuLock 文本协议（行分隔，UTF-8）
//!
//! 格式：`{TYPE} {arg0} {arg1} ...\n`

// 客户端请求命令
pub const CMD_CONNECT: &str = "CONNECT";
pub const CMD_LOCK: &str = "LOCK";
pub const CMD_UNLOCK: &str = "UNLOCK";
pub const CMD_RECHECK: &str = "RECHECK";
pub const CMD_RENEW: &str = "RENEW";

// 服务端响应命令
pub const CMD_CONNECTED: &str = "CONNECTED";
pub const CMD_OK: &str = "OK";
pub const CMD_WAIT: &str = "WAIT";
pub const CMD_RELEASED: &str = "RELEASED";
pub const CMD_RENEWED: &str = "RENEWED";
pub const CMD_REDIRECT: &str = "REDIRECT";
pub const CMD_WATCH_EVENT: &str = "WATCH_EVENT";
pub const CMD_ERROR: &str = "ERROR";

/// 一条协议消息
#[derive(Debug, Clone)]
pub struct Message {
    pub typ: String,
    pub args: Vec<String>,
}

impl Message {
    pub fn new(typ: &str, args: &[&str]) -> Self {
        Self {
            typ: typ.to_string(),
            args: args.iter().map(|s| s.to_string()).collect(),
        }
    }

    pub fn parse(line: &str) -> crate::Result<Self> {
        let line = line.trim();
        if line.is_empty() {
            return Err(crate::HutuLockError::Protocol("empty message".into()));
        }
        let mut parts = line.splitn(2, ' ');
        let typ = parts.next().unwrap().to_uppercase();
        let args: Vec<String> = parts
            .next()
            .map(|rest| rest.split_whitespace().map(String::from).collect())
            .unwrap_or_default();
        Ok(Self { typ, args })
    }

    pub fn serialize(&self) -> String {
        if self.args.is_empty() {
            self.typ.clone()
        } else {
            format!("{} {}", self.typ, self.args.join(" "))
        }
    }

    pub fn arg(&self, i: usize) -> Option<&str> {
        self.args.get(i).map(String::as_str)
    }
}
