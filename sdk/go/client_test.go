package hutulock

import (
	"bufio"
	"context"
	"net"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeServer 模拟服务端，通过 net.Pipe 与客户端通信。
type fakeServer struct {
	conn   net.Conn
	reader *bufio.Reader
	writer *bufio.Writer
	mu     sync.Mutex
}

func newFakeServer(t *testing.T) (*fakeServer, *conn) {
	t.Helper()
	serverConn, clientConn := net.Pipe()
	srv := &fakeServer{
		conn:   serverConn,
		reader: bufio.NewReader(serverConn),
		writer: bufio.NewWriter(serverConn),
	}
	cn := &conn{
		nc:       clientConn,
		scanner:  bufio.NewScanner(clientConn),
		writer:   bufio.NewWriter(clientConn),
		pending:  make(map[string]chan message),
		watchers: make(map[string]chan string),
		closeCh:  make(chan struct{}),
	}
	go cn.readLoop()
	return srv, cn
}

func (s *fakeServer) recv() string {
	line, _ := s.reader.ReadString('\n')
	return strings.TrimRight(line, "\n")
}

func (s *fakeServer) send(line string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.writer.WriteString(line + "\n")
	s.writer.Flush()
}

func (s *fakeServer) close() { s.conn.Close() }

// ---- 协议测试 ----

func TestParseMessage(t *testing.T) {
	tests := []struct {
		input    string
		wantType string
		wantArgs []string
	}{
		{"CONNECTED sess-abc", "CONNECTED", []string{"sess-abc"}},
		{"CONNECT", "CONNECT", nil},
		{"OK order-lock /locks/order-lock/seq-0000000001", "OK", []string{"order-lock", "/locks/order-lock/seq-0000000001"}},
		{"WAIT order-lock /locks/order-lock/seq-0000000002", "WAIT", []string{"order-lock", "/locks/order-lock/seq-0000000002"}},
	}
	for _, tt := range tests {
		msg, err := parseMessage(tt.input)
		if err != nil {
			t.Fatalf("parseMessage(%q) error: %v", tt.input, err)
		}
		if msg.typ != tt.wantType {
			t.Errorf("type: got %q, want %q", msg.typ, tt.wantType)
		}
		for i, want := range tt.wantArgs {
			if msg.arg(i) != want {
				t.Errorf("arg[%d]: got %q, want %q", i, msg.arg(i), want)
			}
		}
	}
}

func TestMessageSerialize(t *testing.T) {
	msg := newMsg(cmdLock, "order-lock", "sess-1")
	got := msg.serialize()
	want := "LOCK order-lock sess-1"
	if got != want {
		t.Errorf("serialize: got %q, want %q", got, want)
	}
}

func TestArgOutOfRange(t *testing.T) {
	msg := newMsg(cmdOK, "lock-name")
	if got := msg.arg(5); got != "" {
		t.Errorf("arg(5) should return empty string, got %q", got)
	}
}

// ---- conn 测试 ----

func TestConnectResponse(t *testing.T) {
	srv, cn := newFakeServer(t)
	defer srv.close()

	go func() {
		srv.recv() // CONNECT
		srv.send("CONNECTED sess-xyz")
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	resp, err := cn.request(ctx, "CONNECT", newMsg(cmdConnect))
	if err != nil {
		t.Fatalf("request error: %v", err)
	}
	if resp.typ != cmdConnected {
		t.Errorf("type: got %q, want %q", resp.typ, cmdConnected)
	}
	if resp.arg(0) != "sess-xyz" {
		t.Errorf("session: got %q, want %q", resp.arg(0), "sess-xyz")
	}
}

func TestLockOK(t *testing.T) {
	srv, cn := newFakeServer(t)
	defer srv.close()

	go func() {
		srv.recv() // LOCK
		srv.send("OK order-lock /locks/order-lock/seq-0000000001")
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	resp, err := cn.request(ctx, "LOCK:order-lock", newMsg(cmdLock, "order-lock", "sess-1"))
	if err != nil {
		t.Fatalf("request error: %v", err)
	}
	if resp.typ != cmdOK {
		t.Errorf("type: got %q, want %q", resp.typ, cmdOK)
	}
	if resp.arg(1) != "/locks/order-lock/seq-0000000001" {
		t.Errorf("seq: got %q", resp.arg(1))
	}
}

func TestLockWaitThenWatchEvent(t *testing.T) {
	srv, cn := newFakeServer(t)
	defer srv.close()

	go func() {
		srv.recv() // LOCK
		srv.send("WAIT order-lock /locks/order-lock/seq-0000000002")
		time.Sleep(100 * time.Millisecond) // 等客户端注册 watcher
		srv.send("WATCH_EVENT NODE_DELETED /locks/order-lock/seq-0000000002")
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	resp, err := cn.request(ctx, "LOCK:order-lock", newMsg(cmdLock, "order-lock", "sess-1"))
	if err != nil {
		t.Fatalf("request error: %v", err)
	}
	if resp.typ != cmdWait {
		t.Fatalf("expected WAIT, got %q", resp.typ)
	}

	watchCh := cn.registerWatcher(resp.arg(1))
	select {
	case eventType := <-watchCh:
		if eventType != "NODE_DELETED" {
			t.Errorf("event type: got %q, want NODE_DELETED", eventType)
		}
	case <-ctx.Done():
		t.Fatal("timeout waiting for watch event")
	}
}

func TestRequestTimeout(t *testing.T) {
	srv, cn := newFakeServer(t)
	defer srv.close()

	go func() { srv.recv() }() // 读取但不响应

	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()

	_, err := cn.request(ctx, "LOCK:no-reply", newMsg(cmdLock, "no-reply", "sess-1"))
	if err == nil {
		t.Fatal("expected timeout error, got nil")
	}
}

func TestErrorResponse(t *testing.T) {
	srv, cn := newFakeServer(t)
	defer srv.close()

	go func() {
		srv.recv()
		srv.send("ERROR PERMISSION_DENIED")
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	resp, err := cn.request(ctx, "LOCK:order-lock", newMsg(cmdLock, "order-lock", "sess-1"))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if resp.typ != cmdError {
		t.Errorf("expected ERROR response, got %q", resp.typ)
	}
}

// ---- Client 高层 API 测试 ----

func makeTestClient(t *testing.T) (*Client, *fakeServer) {
	t.Helper()
	srv, cn := newFakeServer(t)
	client := &Client{
		cfg: Config{
			ConnectTimeout:   5 * time.Second,
			LockTimeout:      5 * time.Second,
			WatchdogInterval: 60 * time.Second, // 测试中不触发
		},
		conn:      cn,
		sessionID: "sess-test",
	}
	return client, srv
}

func TestAcquireAndRelease(t *testing.T) {
	client, srv := makeTestClient(t)
	defer srv.close()

	go func() {
		srv.recv() // LOCK
		srv.send("OK order-lock /locks/order-lock/seq-0000000001")
		srv.recv() // UNLOCK
		srv.send("RELEASED order-lock")
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	token, err := client.Lock(ctx, "order-lock")
	if err != nil {
		t.Fatalf("Lock error: %v", err)
	}
	if token.LockName != "order-lock" {
		t.Errorf("LockName: got %q", token.LockName)
	}
	if token.SeqNodePath != "/locks/order-lock/seq-0000000001" {
		t.Errorf("SeqNodePath: got %q", token.SeqNodePath)
	}

	if err := client.Unlock(ctx, token); err != nil {
		t.Fatalf("Unlock error: %v", err)
	}
}

func TestAcquireWaitThenGranted(t *testing.T) {
	client, srv := makeTestClient(t)
	defer srv.close()

	go func() {
		srv.recv() // LOCK
		srv.send("WAIT order-lock /locks/order-lock/seq-0000000002")
		time.Sleep(100 * time.Millisecond)
		srv.send("WATCH_EVENT NODE_DELETED /locks/order-lock/seq-0000000002")
		srv.recv() // RECHECK
		srv.send("OK order-lock /locks/order-lock/seq-0000000002")
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	token, err := client.Lock(ctx, "order-lock")
	if err != nil {
		t.Fatalf("Lock error: %v", err)
	}
	if token.SeqNodePath != "/locks/order-lock/seq-0000000002" {
		t.Errorf("SeqNodePath: got %q", token.SeqNodePath)
	}
}

func TestAcquireTimeout(t *testing.T) {
	client, srv := makeTestClient(t)
	client.cfg.LockTimeout = 100 * time.Millisecond
	defer srv.close()

	go func() { srv.recv() }() // 读取但不响应

	ctx := context.Background()
	_, err := client.Lock(ctx, "order-lock")
	if err == nil {
		t.Fatal("expected timeout error, got nil")
	}
}

func TestUnlockTimeoutSilent(t *testing.T) {
	client, srv := makeTestClient(t)
	defer srv.close()

	go func() { srv.recv() }() // 读取 UNLOCK 但不响应

	token := &LockToken{
		LockName:    "ghost-lock",
		SeqNodePath: "/locks/ghost-lock/seq-1",
		SessionID:   "sess-test",
	}

	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()

	// 超时应静默处理，不返回错误
	if err := client.Unlock(ctx, token); err != nil {
		t.Fatalf("Unlock should not return error on timeout, got: %v", err)
	}
}
