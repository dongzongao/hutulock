package hutulock

import "fmt"

// ErrConnection 连接失败或断开
type ErrConnection struct{ Msg string }

func (e *ErrConnection) Error() string { return "hutulock: connection error: " + e.Msg }

// ErrLockTimeout 获取锁超时
type ErrLockTimeout struct{ LockName string }

func (e *ErrLockTimeout) Error() string {
	return fmt.Sprintf("hutulock: lock timeout: %s", e.LockName)
}

// ErrNotLeader 当前节点不是 Leader
type ErrNotLeader struct{ LeaderID string }

func (e *ErrNotLeader) Error() string {
	return fmt.Sprintf("hutulock: not leader, redirect to: %s", e.LeaderID)
}

// ErrSessionExpired 会话已过期
type ErrSessionExpired struct{}

func (e *ErrSessionExpired) Error() string { return "hutulock: session expired" }

// ErrProtocol 协议解析错误
type ErrProtocol struct{ Msg string }

func (e *ErrProtocol) Error() string { return "hutulock: protocol error: " + e.Msg }
