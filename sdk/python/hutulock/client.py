"""HutuLock Python 客户端

协议：纯文本行（UTF-8），与 Java 客户端完全兼容。

用法::

    client = HutuLockClient(nodes=[("127.0.0.1", 8881)])
    client.connect()

    # 简单 API
    with client.lock("order-lock"):
        do_work()

    # 手动 API
    token = client.acquire("order-lock", timeout=10)
    try:
        do_work()
    finally:
        client.release(token)

    client.close()
"""
from __future__ import annotations

import logging
import socket
import threading
import time
from contextlib import contextmanager
from dataclasses import dataclass, field
from typing import Callable, Dict, Generator, List, Optional, Tuple

from .exceptions import (
    ConnectionError,
    LockTimeoutError,
    NotLeaderError,
    ProtocolError,
    SessionExpiredError,
)
from .protocol import (
    CMD_CONNECT, CMD_CONNECTED, CMD_ERROR, CMD_LOCK, CMD_OK,
    CMD_RECHECK, CMD_REDIRECT, CMD_RELEASED, CMD_RENEW, CMD_RENEWED,
    CMD_UNLOCK, CMD_WAIT, CMD_WATCH_EVENT,
    Message,
)

log = logging.getLogger(__name__)

_SENTINEL = object()


# ---------------------------------------------------------------------------
# 内部：同步 TCP 连接 + 行读取
# ---------------------------------------------------------------------------

class _Connection:
    """单条 TCP 连接，负责发送/接收文本行，并将响应分发给等待者。"""

    def __init__(self, host: str, port: int, timeout: float = 5.0):
        self._sock = socket.create_connection((host, port), timeout=timeout)
        self._sock.settimeout(None)          # 读取改为阻塞
        self._file = self._sock.makefile("r", encoding="utf-8", newline="\n")
        self._lock = threading.Lock()

        # key → Event + 结果槽
        self._pending: Dict[str, Tuple[threading.Event, list]] = {}
        # seqNodePath → callback(event_type: str)
        self._watchers: Dict[str, Callable[[str], None]] = {}

        self._closed = False
        self._reader = threading.Thread(target=self._read_loop, daemon=True,
                                        name="hutulock-reader")
        self._reader.start()

    # ---- 发送 ----

    def send(self, msg: Message) -> None:
        with self._lock:
            if self._closed:
                raise ConnectionError("connection closed")
            self._sock.sendall((msg.serialize() + "\n").encode("utf-8"))

    # ---- 请求/响应 ----

    def request(self, key: str, msg: Message, timeout: float = 10.0) -> Message:
        """发送消息并等待对应 key 的响应。"""
        event = threading.Event()
        slot: list = []
        with self._lock:
            self._pending[key] = (event, slot)
        self.send(msg)
        if not event.wait(timeout):
            with self._lock:
                self._pending.pop(key, None)
            raise LockTimeoutError(f"request timeout: key={key}")
        result = slot[0]
        if isinstance(result, Exception):
            raise result
        return result

    # ---- Watcher ----

    def register_watcher(self, seq_path: str, callback: Callable[[str], None]) -> None:
        with self._lock:
            self._watchers[seq_path] = callback

    # ---- 读取循环 ----

    def _read_loop(self) -> None:
        try:
            for raw in self._file:
                raw = raw.rstrip("\n")
                if not raw:
                    continue
                log.debug("recv: %s", raw)
                self._dispatch(raw)
        except Exception as e:
            if not self._closed:
                log.warning("read loop error: %s", e)
            self._fail_all(ConnectionError(str(e)))

    def _dispatch(self, raw: str) -> None:
        if raw.startswith(CMD_WATCH_EVENT):
            self._handle_watch(raw)
            return
        try:
            msg = Message.parse(raw)
        except Exception as e:
            log.error("parse error: %s — %s", raw, e)
            return

        t = msg.type
        with self._lock:
            if t == CMD_CONNECTED:
                self._complete("CONNECT", msg)
            elif t == CMD_OK:
                lock_name = msg.arg(0) if msg.args else ""
                seq_path  = msg.arg(1) if len(msg.args) > 1 else ""
                self._complete(f"LOCK:{lock_name}", msg)
                self._complete(f"RECHECK:{seq_path}", msg)
            elif t == CMD_WAIT:
                lock_name = msg.arg(0) if msg.args else ""
                self._complete(f"LOCK:{lock_name}", msg)
            elif t == CMD_RELEASED:
                lock_name = msg.arg(0) if msg.args else ""
                self._complete(f"UNLOCK:{lock_name}", msg)
            elif t == CMD_RENEWED:
                lock_name = msg.arg(0) if msg.args else ""
                self._complete(f"RENEW:{lock_name}", msg)
            elif t == CMD_REDIRECT:
                leader_id = msg.arg(0) if msg.args else ""
                err = NotLeaderError(leader_id)
                for key, (ev, slot) in list(self._pending.items()):
                    slot.append(err)
                    ev.set()
                self._pending.clear()
            elif t == CMD_ERROR:
                err_msg = msg.arg(0) if msg.args else "server error"
                err = ProtocolError(err_msg)
                for key, (ev, slot) in list(self._pending.items()):
                    slot.append(err)
                    ev.set()
                self._pending.clear()

    def _handle_watch(self, raw: str) -> None:
        # 格式：WATCH_EVENT {type} {path}
        parts = raw.split(None, 2)
        if len(parts) < 3:
            return
        event_type, path = parts[1], parts[2]
        with self._lock:
            cb = self._watchers.pop(path, None)
        if cb:
            cb(event_type)

    def _complete(self, key: str, msg: Message) -> None:
        """调用前须持有 self._lock"""
        entry = self._pending.pop(key, None)
        if entry:
            ev, slot = entry
            slot.append(msg)
            ev.set()

    def _fail_all(self, exc: Exception) -> None:
        with self._lock:
            for ev, slot in self._pending.values():
                slot.append(exc)
                ev.set()
            self._pending.clear()

    def close(self) -> None:
        self._closed = True
        try:
            self._sock.close()
        except Exception:
            pass


# ---------------------------------------------------------------------------
# LockToken：持有锁的凭证
# ---------------------------------------------------------------------------

@dataclass
class LockToken:
    lock_name: str
    seq_node_path: str
    session_id: str
    _watchdog_timer: Optional[threading.Timer] = field(default=None, repr=False, compare=False)

    def stop_watchdog(self) -> None:
        if self._watchdog_timer:
            self._watchdog_timer.cancel()
            self._watchdog_timer = None


# ---------------------------------------------------------------------------
# HutuLockClient
# ---------------------------------------------------------------------------

class HutuLockClient:
    """HutuLock 分布式锁 Python 客户端。

    Args:
        nodes:              集群节点列表，格式 [(host, port), ...]
        connect_timeout:    连接超时（秒），默认 5
        lock_timeout:       获取锁超时（秒），默认 30
        watchdog_interval:  看门狗心跳间隔（秒），默认 10
    """

    def __init__(
        self,
        nodes: List[Tuple[str, int]],
        connect_timeout: float = 5.0,
        lock_timeout: float = 30.0,
        watchdog_interval: float = 10.0,
    ):
        if not nodes:
            raise ValueError("at least one node required")
        self._nodes = list(nodes)
        self._connect_timeout = connect_timeout
        self._lock_timeout = lock_timeout
        self._watchdog_interval = watchdog_interval

        self._conn: Optional[_Connection] = None
        self._session_id: Optional[str] = None
        self._held: Dict[str, LockToken] = {}   # lock_name → token

    @property
    def session_id(self) -> Optional[str]:
        return self._session_id

    # ---- 连接 ----

    def connect(self) -> None:
        """连接到集群并建立会话。"""
        self._conn = self._connect_any(self._nodes)
        self._session_id = self._establish_session()
        log.info("connected, session=%s", self._session_id)

    def _connect_any(self, nodes: List[Tuple[str, int]]) -> _Connection:
        last_err: Optional[Exception] = None
        for host, port in nodes:
            try:
                conn = _Connection(host, port, timeout=self._connect_timeout)
                log.info("connected to %s:%s", host, port)
                return conn
            except Exception as e:
                last_err = e
                log.warning("failed to connect to %s:%s — %s", host, port, e)
        raise ConnectionError(f"all nodes unreachable") from last_err

    def _establish_session(self, existing_session_id: Optional[str] = None) -> str:
        args = [existing_session_id] if existing_session_id else []
        resp = self._conn.request(
            "CONNECT",
            Message(CMD_CONNECT, args),
            timeout=self._connect_timeout,
        )
        if resp.type != CMD_CONNECTED:
            raise ProtocolError(f"unexpected response to CONNECT: {resp}")
        return resp.arg(0)

    # ---- 上下文管理器 ----

    @contextmanager
    def lock(self, lock_name: str, timeout: Optional[float] = None) -> Generator[LockToken, None, None]:
        """上下文管理器，自动获取和释放锁。

        with client.lock("order-lock") as token:
            do_work()
        """
        token = self.acquire(lock_name, timeout=timeout)
        try:
            yield token
        finally:
            self.release(token)

    # ---- 获取锁 ----

    def acquire(self, lock_name: str, timeout: Optional[float] = None) -> LockToken:
        """获取锁，返回 LockToken。超时抛出 LockTimeoutError。"""
        deadline = time.monotonic() + (timeout if timeout is not None else self._lock_timeout)
        token = self._do_acquire(lock_name, deadline)
        self._start_watchdog(token)
        self._held[lock_name] = token
        log.info("lock acquired: %s seq=%s", lock_name, token.seq_node_path)
        return token

    def _do_acquire(self, lock_name: str, deadline: float) -> LockToken:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise LockTimeoutError(f"lock timeout: {lock_name}")

        resp = self._conn.request(
            f"LOCK:{lock_name}",
            Message(CMD_LOCK, [lock_name, self._session_id]),
            timeout=remaining,
        )

        if resp.type == CMD_REDIRECT:
            self._handle_redirect(resp.arg(0))
            return self._do_acquire(lock_name, deadline)

        if resp.type == CMD_OK:
            return LockToken(
                lock_name=lock_name,
                seq_node_path=resp.arg(1),
                session_id=self._session_id,
            )

        if resp.type == CMD_WAIT:
            seq_node_path = resp.arg(1)
            return self._wait_for_lock(lock_name, seq_node_path, deadline)

        raise ProtocolError(f"unexpected response to LOCK: {resp}")

    def _wait_for_lock(self, lock_name: str, seq_node_path: str, deadline: float) -> LockToken:
        """等待 WATCH_EVENT，然后 RECHECK。"""
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise LockTimeoutError(f"lock timeout while waiting: {lock_name}")

        watch_event = threading.Event()
        watch_result: list = []

        def on_watch(event_type: str) -> None:
            watch_result.append(event_type)
            watch_event.set()

        self._conn.register_watcher(seq_node_path, on_watch)

        if not watch_event.wait(timeout=remaining):
            raise LockTimeoutError(f"lock timeout waiting for watch event: {lock_name}")

        event_type = watch_result[0] if watch_result else ""
        if event_type == "SESSION_EXPIRED":
            raise SessionExpiredError("session expired while waiting for lock")

        return self._recheck(lock_name, seq_node_path, deadline)

    def _recheck(self, lock_name: str, seq_node_path: str, deadline: float) -> LockToken:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise LockTimeoutError(f"lock timeout during recheck: {lock_name}")

        resp = self._conn.request(
            f"RECHECK:{seq_node_path}",
            Message(CMD_RECHECK, [lock_name, seq_node_path, self._session_id]),
            timeout=remaining,
        )

        if resp.type == CMD_OK:
            return LockToken(
                lock_name=lock_name,
                seq_node_path=resp.arg(1),
                session_id=self._session_id,
            )

        if resp.type == CMD_WAIT:
            new_seq = resp.arg(1)
            return self._wait_for_lock(lock_name, new_seq, deadline)

        raise ProtocolError(f"unexpected response to RECHECK: {resp}")

    # ---- 释放锁 ----

    def release(self, token: LockToken) -> None:
        """释放锁，停止看门狗。"""
        token.stop_watchdog()
        self._held.pop(token.lock_name, None)

        try:
            self._conn.request(
                f"UNLOCK:{token.lock_name}",
                Message(CMD_UNLOCK, [token.seq_node_path, self._session_id]),
                timeout=10.0,
            )
            log.info("lock released: %s", token.lock_name)
        except LockTimeoutError:
            log.warning("unlock timeout for %s, session expiry will clean up", token.lock_name)

    # ---- 看门狗 ----

    def _start_watchdog(self, token: LockToken) -> None:
        def renew() -> None:
            try:
                self._conn.request(
                    f"RENEW:{token.lock_name}",
                    Message(CMD_RENEW, [token.lock_name, self._session_id]),
                    timeout=5.0,
                )
                log.debug("watchdog renewed: %s", token.lock_name)
            except Exception as e:
                log.warning("watchdog renew failed: %s — %s", token.lock_name, e)
            finally:
                # 重新调度（持续心跳）
                if token.lock_name in self._held:
                    t = threading.Timer(self._watchdog_interval, renew)
                    t.daemon = True
                    token._watchdog_timer = t
                    t.start()

        t = threading.Timer(self._watchdog_interval, renew)
        t.daemon = True
        token._watchdog_timer = t
        t.start()

    # ---- 重定向 ----

    def _handle_redirect(self, leader_id: str) -> None:
        log.info("redirected to leader: %s, reconnecting...", leader_id)
        if self._conn:
            self._conn.close()
        import random
        nodes = list(self._nodes)
        random.shuffle(nodes)
        self._conn = self._connect_any(nodes)
        self._session_id = self._establish_session(self._session_id)

    # ---- 关闭 ----

    def close(self) -> None:
        """关闭客户端，停止所有看门狗并断开连接。"""
        for token in list(self._held.values()):
            token.stop_watchdog()
        self._held.clear()
        if self._conn:
            self._conn.close()
            self._conn = None

    def __enter__(self) -> "HutuLockClient":
        self.connect()
        return self

    def __exit__(self, *_) -> None:
        self.close()
