use thiserror::Error;

#[derive(Debug, Error)]
pub enum HutuLockError {
    #[error("connection error: {0}")]
    Connection(String),

    #[error("lock timeout: {lock_name}")]
    LockTimeout { lock_name: String },

    #[error("session expired")]
    SessionExpired,

    #[error("protocol error: {0}")]
    Protocol(String),

    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
}

pub type Result<T> = std::result::Result<T, HutuLockError>;
