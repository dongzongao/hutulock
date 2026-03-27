package hutulock

import (
	"strings"
)

// 客户端请求命令
const (
	cmdConnect = "CONNECT"
	cmdLock    = "LOCK"
	cmdUnlock  = "UNLOCK"
	cmdRecheck = "RECHECK"
	cmdRenew   = "RENEW"
)

// 服务端响应命令
const (
	cmdConnected   = "CONNECTED"
	cmdOK          = "OK"
	cmdWait        = "WAIT"
	cmdReleased    = "RELEASED"
	cmdRenewed     = "RENEWED"
	cmdRedirect    = "REDIRECT"
	cmdWatchEvent  = "WATCH_EVENT"
	cmdError       = "ERROR"
)

// message 表示一条协议消息，格式：{TYPE} {arg0} {arg1} ...\n
type message struct {
	typ  string
	args []string
}

func parseMessage(line string) (message, error) {
	line = strings.TrimSpace(line)
	if line == "" {
		return message{}, &ErrProtocol{Msg: "empty message"}
	}
	parts := strings.SplitN(line, " ", 2)
	msg := message{typ: strings.ToUpper(parts[0])}
	if len(parts) > 1 && parts[1] != "" {
		msg.args = strings.Fields(parts[1])
	}
	return msg, nil
}

func (m message) serialize() string {
	if len(m.args) == 0 {
		return m.typ
	}
	return m.typ + " " + strings.Join(m.args, " ")
}

func (m message) arg(i int) string {
	if i < len(m.args) {
		return m.args[i]
	}
	return ""
}

func newMsg(typ string, args ...string) message {
	return message{typ: typ, args: args}
}
