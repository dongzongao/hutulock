// Package hutulock 提供 HutuLock 分布式锁 Go 客户端。
//
// 协议：纯文本行（UTF-8），与 Java/Python 客户端完全兼容。
//
// 用法：
//
//	client, err := hutulock.New(hutulock.Config{
//	    Nodes: []string{"127.0.0.1:8881", "127.0.0.1:8882"},
//	})
//	if err != nil { ... }
//	defer client.Close()
//
//	// 上下文管理
//	token, err := client.Lock(ctx, "order-lock")
//	if err != nil { ... }
//	defer client.Unlock(ctx, token)
package hutulock

import (
	"bufio"
	"context"
	"fmt"
	"log/slog"
	"math/rand"
	"net"
	"strings"
	"sync"
	"time"
)

// Config 客户端配置
type Config struct {
	// Nodes 集群节点地址列表，格式 "host:port"
	Nodes []string
	// ConnectTimeout 连接超时，默认 5s
	ConnectTimeout time.Duration
	// LockTimeout 获取锁超时，默认 30s
	LockTimeout time.Duration
	// WatchdogInterval 看门狗心跳间隔，默认 10s
	WatchdogInterval time.Duration
}

func (c *Config) setDefaults() {
	if c.ConnectTimeout == 0 {
		c.ConnectTimeout = 5 * time.Second
	}
	if c.LockTimeout == 0 {
		c.LockTimeout = 30 * time.Second
	}
	if c.WatchdogInterval == 0 {
		c.WatchdogInterval = 10 * time.Second
	}
}

// LockToken 持有锁的凭证，由 Lock 返回，传给 Unlock 使用。
type LockToken struct {
	LockName    string
	SeqNodePath string
	SessionID   string

	stopWatchdog func()
}

// Client HutuLock 分布式锁客户端，并发安全。
type Client struct {
	cfg       Config
	conn      *conn
	sessionID string
	mu        sync.Mutex // 保护 conn 和 sessionID
}

// New 创建并连接客户端。
func New(cfg Config) (*Client, error) {
	cfg.setDefaults()
	if len(cfg.Nodes) == 0 {
		return nil, fmt.Errorf("hutulock: at least one node required")
	}
	c := &Client{cfg: cfg}
	if err := c.connect(); err != nil {
		return nil, err
	}
	return c, nil
}

// Lock 获取锁，返回 LockToken。超时或 ctx 取消时返回错误。
func (c *Client) Lock(ctx context.Context, lockName string) (*LockToken, error) {
	deadline, ok := ctx.Deadline()
	if !ok {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, c.cfg.LockTimeout)
		defer cancel()
		deadline, _ = ctx.Deadline()
	}

	token, err := c.doAcquire(ctx, lockName, deadline)
	if err != nil {
		return nil, err
	}
	c.startWatchdog(token)
	slog.Debug("hutulock: lock acquired", "lock", lockName, "seq", token.SeqNodePath)
	return token, nil
}

// Unlock 释放锁，停止看门狗。
func (c *Client) Unlock(ctx context.Context, token *LockToken) error {
	if token.stopWatchdog != nil {
		token.stopWatchdog()
	}

	c.mu.Lock()
	cn := c.conn
	sid := c.sessionID
	c.mu.Unlock()

	resp, err := cn.request(ctx, "UNLOCK:"+token.LockName,
		newMsg(cmdUnlock, token.SeqNodePath, sid))
	if err != nil {
		slog.Warn("hutulock: unlock timeout, session expiry will clean up", "lock", token.LockName)
		return nil // 超时静默处理，服务端 TTL 会清理
	}
	if resp.typ == cmdReleased {
		slog.Debug("hutulock: lock released", "lock", token.LockName)
	}
	return nil
}

// Close 关闭客户端连接。
func (c *Client) Close() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.conn != nil {
		c.conn.close()
		c.conn = nil
	}
}

// SessionID 返回当前会话 ID。
func (c *Client) SessionID() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.sessionID
}

// ---- 内部：连接与会话 ----

func (c *Client) connect() error {
	nodes := make([]string, len(c.cfg.Nodes))
	copy(nodes, c.cfg.Nodes)
	rand.Shuffle(len(nodes), func(i, j int) { nodes[i], nodes[j] = nodes[j], nodes[i] })

	var lastErr error
	for _, addr := range nodes {
		cn, err := dial(addr, c.cfg.ConnectTimeout)
		if err != nil {
			lastErr = err
			slog.Warn("hutulock: failed to connect", "addr", addr, "err", err)
			continue
		}
		sid, err := c.establishSession(cn, "")
		if err != nil {
			cn.close()
			lastErr = err
			continue
		}
		c.conn = cn
		c.sessionID = sid
		slog.Info("hutulock: connected", "addr", addr, "session", sid)
		return nil
	}
	return fmt.Errorf("hutulock: all nodes unreachable: %w", lastErr)
}

func (c *Client) establishSession(cn *conn, existingID string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), c.cfg.ConnectTimeout)
	defer cancel()

	var msg message
	if existingID != "" {
		msg = newMsg(cmdConnect, existingID)
	} else {
		msg = newMsg(cmdConnect)
	}

	resp, err := cn.request(ctx, "CONNECT", msg)
	if err != nil {
		return "", err
	}
	if resp.typ != cmdConnected {
		return "", &ErrProtocol{Msg: "unexpected response to CONNECT: " + resp.serialize()}
	}
	return resp.arg(0), nil
}

func (c *Client) handleRedirect(leaderID string) error {
	slog.Info("hutulock: redirected", "leader", leaderID)
	c.mu.Lock()
	oldConn := c.conn
	oldSID := c.sessionID
	c.mu.Unlock()

	if oldConn != nil {
		oldConn.close()
	}

	nodes := make([]string, len(c.cfg.Nodes))
	copy(nodes, c.cfg.Nodes)
	rand.Shuffle(len(nodes), func(i, j int) { nodes[i], nodes[j] = nodes[j], nodes[i] })

	var lastErr error
	for _, addr := range nodes {
		cn, err := dial(addr, c.cfg.ConnectTimeout)
		if err != nil {
			lastErr = err
			continue
		}
		sid, err := c.establishSession(cn, oldSID)
		if err != nil {
			cn.close()
			lastErr = err
			continue
		}
		c.mu.Lock()
		c.conn = cn
		c.sessionID = sid
		c.mu.Unlock()
		return nil
	}
	return fmt.Errorf("hutulock: reconnect failed: %w", lastErr)
}

// ---- 内部：锁操作 ----

func (c *Client) doAcquire(ctx context.Context, lockName string, deadline time.Time) (*LockToken, error) {
	if time.Now().After(deadline) {
		return nil, &ErrLockTimeout{LockName: lockName}
	}

	c.mu.Lock()
	cn := c.conn
	sid := c.sessionID
	c.mu.Unlock()

	resp, err := cn.request(ctx, "LOCK:"+lockName, newMsg(cmdLock, lockName, sid))
	if err != nil {
		return nil, err
	}

	switch resp.typ {
	case cmdRedirect:
		if rerr := c.handleRedirect(resp.arg(0)); rerr != nil {
			return nil, rerr
		}
		return c.doAcquire(ctx, lockName, deadline)

	case cmdOK:
		return &LockToken{
			LockName:    lockName,
			SeqNodePath: resp.arg(1),
			SessionID:   sid,
		}, nil

	case cmdWait:
		seqPath := resp.arg(1)
		return c.waitForLock(ctx, lockName, seqPath, deadline)

	default:
		return nil, &ErrProtocol{Msg: "unexpected response to LOCK: " + resp.serialize()}
	}
}

func (c *Client) waitForLock(ctx context.Context, lockName, seqPath string, deadline time.Time) (*LockToken, error) {
	if time.Now().After(deadline) {
		return nil, &ErrLockTimeout{LockName: lockName}
	}

	c.mu.Lock()
	cn := c.conn
	c.mu.Unlock()

	watchCh := cn.registerWatcher(seqPath)

	select {
	case <-ctx.Done():
		return nil, &ErrLockTimeout{LockName: lockName}
	case eventType, ok := <-watchCh:
		if !ok {
			return nil, &ErrConnection{Msg: "connection closed while waiting for watch event"}
		}
		if eventType == "SESSION_EXPIRED" {
			return nil, &ErrSessionExpired{}
		}
	}

	return c.recheck(ctx, lockName, seqPath, deadline)
}

func (c *Client) recheck(ctx context.Context, lockName, seqPath string, deadline time.Time) (*LockToken, error) {
	if time.Now().After(deadline) {
		return nil, &ErrLockTimeout{LockName: lockName}
	}

	c.mu.Lock()
	cn := c.conn
	sid := c.sessionID
	c.mu.Unlock()

	resp, err := cn.request(ctx, "RECHECK:"+seqPath, newMsg(cmdRecheck, lockName, seqPath, sid))
	if err != nil {
		return nil, err
	}

	switch resp.typ {
	case cmdOK:
		return &LockToken{
			LockName:    lockName,
			SeqNodePath: resp.arg(1),
			SessionID:   sid,
		}, nil
	case cmdWait:
		newSeq := resp.arg(1)
		return c.waitForLock(ctx, lockName, newSeq, deadline)
	default:
		return nil, &ErrProtocol{Msg: "unexpected response to RECHECK: " + resp.serialize()}
	}
}

// ---- 内部：看门狗 ----

func (c *Client) startWatchdog(token *LockToken) {
	stopCh := make(chan struct{})
	token.stopWatchdog = sync.OnceFunc(func() { close(stopCh) })

	go func() {
		ticker := time.NewTicker(c.cfg.WatchdogInterval)
		defer ticker.Stop()
		for {
			select {
			case <-stopCh:
				return
			case <-ticker.C:
				c.mu.Lock()
				cn := c.conn
				sid := c.sessionID
				c.mu.Unlock()

				ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
				_, err := cn.request(ctx, "RENEW:"+token.LockName,
					newMsg(cmdRenew, token.LockName, sid))
				cancel()
				if err != nil {
					slog.Warn("hutulock: watchdog renew failed", "lock", token.LockName, "err", err)
				}
			}
		}
	}()
}

// ============================================================
// conn：单条 TCP 连接，goroutine 安全
// ============================================================

type pending struct {
	ch  chan message
	key string
}

type conn struct {
	nc      net.Conn
	scanner *bufio.Scanner
	writer  *bufio.Writer
	mu      sync.Mutex // 保护 writer 和 pending/watchers

	pending  map[string]chan message // key → response channel
	watchers map[string]chan string  // seqPath → watch event channel
	closeCh  chan struct{}
	once     sync.Once
}

func dial(addr string, timeout time.Duration) (*conn, error) {
	nc, err := net.DialTimeout("tcp", addr, timeout)
	if err != nil {
		return nil, &ErrConnection{Msg: err.Error()}
	}
	c := &conn{
		nc:       nc,
		scanner:  bufio.NewScanner(nc),
		writer:   bufio.NewWriter(nc),
		pending:  make(map[string]chan message),
		watchers: make(map[string]chan string),
		closeCh:  make(chan struct{}),
	}
	go c.readLoop()
	return c, nil
}

func (c *conn) send(msg message) error {
	line := msg.serialize() + "\n"
	c.mu.Lock()
	defer c.mu.Unlock()
	_, err := c.writer.WriteString(line)
	if err != nil {
		return err
	}
	return c.writer.Flush()
}

// request 发送消息并等待对应 key 的响应。
func (c *conn) request(ctx context.Context, key string, msg message) (message, error) {
	ch := make(chan message, 1)
	c.mu.Lock()
	c.pending[key] = ch
	c.mu.Unlock()

	if err := c.send(msg); err != nil {
		c.mu.Lock()
		delete(c.pending, key)
		c.mu.Unlock()
		return message{}, &ErrConnection{Msg: err.Error()}
	}

	select {
	case <-ctx.Done():
		c.mu.Lock()
		delete(c.pending, key)
		c.mu.Unlock()
		return message{}, &ErrLockTimeout{}
	case <-c.closeCh:
		return message{}, &ErrConnection{Msg: "connection closed"}
	case resp := <-ch:
		return resp, nil
	}
}

// registerWatcher 注册一次性 watcher，返回接收事件类型的 channel。
func (c *conn) registerWatcher(seqPath string) <-chan string {
	ch := make(chan string, 1)
	c.mu.Lock()
	c.watchers[seqPath] = ch
	c.mu.Unlock()
	return ch
}

func (c *conn) readLoop() {
	defer c.close()
	for c.scanner.Scan() {
		line := c.scanner.Text()
		if line == "" {
			continue
		}
		c.dispatch(line)
	}
}

func (c *conn) dispatch(line string) {
	if strings.HasPrefix(line, cmdWatchEvent) {
		c.handleWatchEvent(line)
		return
	}

	msg, err := parseMessage(line)
	if err != nil {
		slog.Error("hutulock: parse error", "line", line, "err", err)
		return
	}

	c.mu.Lock()
	defer c.mu.Unlock()

	switch msg.typ {
	case cmdConnected:
		c.complete("CONNECT", msg)
	case cmdOK:
		lockName := msg.arg(0)
		seqPath := msg.arg(1)
		c.complete("LOCK:"+lockName, msg)
		c.complete("RECHECK:"+seqPath, msg)
	case cmdWait:
		c.complete("LOCK:"+msg.arg(0), msg)
	case cmdReleased:
		c.complete("UNLOCK:"+msg.arg(0), msg)
	case cmdRenewed:
		c.complete("RENEW:"+msg.arg(0), msg)
	case cmdRedirect:
		// 广播给所有等待者
		leaderID := msg.arg(0)
		for key, ch := range c.pending {
			ch <- newMsg(cmdRedirect, leaderID)
			delete(c.pending, key)
		}
	case cmdError:
		errMsg := msg.arg(0)
		for key, ch := range c.pending {
			ch <- newMsg(cmdError, errMsg)
			delete(c.pending, key)
		}
	}
}

func (c *conn) handleWatchEvent(line string) {
	// 格式：WATCH_EVENT {type} {path}
	parts := strings.SplitN(line, " ", 3)
	if len(parts) < 3 {
		return
	}
	eventType, path := parts[1], parts[2]

	c.mu.Lock()
	ch, ok := c.watchers[path]
	if ok {
		delete(c.watchers, path)
	}
	c.mu.Unlock()

	if ok {
		ch <- eventType
	}
}

func (c *conn) complete(key string, msg message) {
	// 调用前须持有 c.mu
	if ch, ok := c.pending[key]; ok {
		ch <- msg
		delete(c.pending, key)
	}
}

func (c *conn) close() {
	c.once.Do(func() {
		close(c.closeCh)
		c.nc.Close()
		// 通知所有等待者连接已关闭
		c.mu.Lock()
		for _, ch := range c.watchers {
			close(ch)
		}
		c.watchers = make(map[string]chan string)
		c.mu.Unlock()
	})
}
