"""HutuLock Python SDK 单元测试

使用 mock socket 模拟服务端响应，不依赖真实服务。
兼容 pytest 和 unittest。
"""
import threading
import time
import unittest
from contextlib import contextmanager

from hutulock.client import HutuLockClient, LockToken, _Connection
from hutulock.exceptions import LockTimeoutError, NotLeaderError, ProtocolError
from hutulock.protocol import Message


@contextmanager
def raises(exc_type):
    """简易 pytest.raises 替代（unittest 兼容）。"""
    try:
        yield
        raise AssertionError(f"Expected {exc_type.__name__} but no exception raised")
    except exc_type:
        pass


# ---------------------------------------------------------------------------
# 工具：FakeServer（内存 socket 对）
# ---------------------------------------------------------------------------

class FakeServer:
    """模拟服务端：通过 socketpair 与客户端通信。"""

    def __init__(self):
        import socket
        self.server_sock, self.client_sock = socket.socketpair()
        self._file = self.server_sock.makefile("rw", encoding="utf-8", newline="\n",
                                               buffering=1)

    def send(self, line: str) -> None:
        self._file.write(line + "\n")
        self._file.flush()

    def recv(self) -> str:
        return self._file.readline().rstrip("\n")

    def close(self) -> None:
        self.server_sock.close()
        self.client_sock.close()


def make_connection(fake: FakeServer) -> _Connection:
    """用 FakeServer 的 client_sock 构造 _Connection（绕过 TCP 连接）。"""
    conn = _Connection.__new__(_Connection)
    conn._sock = fake.client_sock
    conn._file = fake.client_sock.makefile("r", encoding="utf-8", newline="\n")
    conn._lock = threading.Lock()
    conn._pending = {}
    conn._watchers = {}
    conn._closed = False
    conn._reader = threading.Thread(target=conn._read_loop, daemon=True,
                                    name="hutulock-reader-test")
    conn._reader.start()
    return conn


# ---------------------------------------------------------------------------
# 协议测试
# ---------------------------------------------------------------------------

class TestMessage(unittest.TestCase):
    def test_parse_simple(self):
        msg = Message.parse("CONNECTED sess-abc")
        assert msg.type == "CONNECTED"
        assert msg.arg(0) == "sess-abc"

    def test_parse_no_args(self):
        msg = Message.parse("CONNECT")
        assert msg.type == "CONNECT"
        assert msg.args == []

    def test_serialize(self):
        msg = Message("LOCK", ["order-lock", "sess-1"])
        assert msg.serialize() == "LOCK order-lock sess-1"

    def test_arg_out_of_range(self):
        msg = Message("OK", ["lock-name"])
        with raises(IndexError):
            msg.arg(5)


# ---------------------------------------------------------------------------
# _Connection 测试
# ---------------------------------------------------------------------------

class TestConnection(unittest.TestCase):
    def setUp(self):
        self.fake = FakeServer()
        self.conn = make_connection(self.fake)

    def tearDown(self):
        self.conn.close()
        self.fake.close()

    def test_connect_response(self):
        def server():
            self.fake.recv()  # 读取 CONNECT
            self.fake.send("CONNECTED sess-xyz")

        t = threading.Thread(target=server, daemon=True)
        t.start()
        resp = self.conn.request("CONNECT", Message("CONNECT"), timeout=3)
        assert resp.type == "CONNECTED"
        assert resp.arg(0) == "sess-xyz"
        t.join(timeout=2)

    def test_lock_ok(self):
        def server():
            self.fake.recv()  # LOCK
            self.fake.send("OK order-lock /locks/order-lock/seq-0000000001")

        t = threading.Thread(target=server, daemon=True)
        t.start()
        resp = self.conn.request("LOCK:order-lock",
                                 Message("LOCK", ["order-lock", "sess-1"]), timeout=3)
        assert resp.type == "OK"
        assert resp.arg(1) == "/locks/order-lock/seq-0000000001"
        t.join(timeout=2)

    def test_lock_wait_then_watch_event(self):
        watch_received = threading.Event()
        watch_type: list = []

        def on_watch(event_type):
            watch_type.append(event_type)
            watch_received.set()

        def server():
            self.fake.recv()  # LOCK
            self.fake.send("WAIT order-lock /locks/order-lock/seq-0000000002")
            time.sleep(0.2)   # 给客户端足够时间注册 watcher
            # WATCH_EVENT 路径必须与 watcher 注册路径一致（即 WAIT 响应里的 seq_path）
            self.fake.send("WATCH_EVENT NODE_DELETED /locks/order-lock/seq-0000000002")

        t = threading.Thread(target=server, daemon=True)
        t.start()

        resp = self.conn.request("LOCK:order-lock",
                                 Message("LOCK", ["order-lock", "sess-1"]), timeout=3)
        assert resp.type == "WAIT"
        seq_path = resp.arg(1)

        self.conn.register_watcher(seq_path, on_watch)
        assert watch_received.wait(timeout=2)
        assert watch_type[0] == "NODE_DELETED"
        t.join(timeout=2)

    def test_request_timeout(self):
        # 服务端不响应
        with raises(LockTimeoutError):
            self.conn.request("LOCK:no-reply",
                              Message("LOCK", ["no-reply", "sess-1"]), timeout=0.1)

    def test_error_response(self):
        def server():
            self.fake.recv()
            self.fake.send("ERROR PERMISSION_DENIED")

        t = threading.Thread(target=server, daemon=True)
        t.start()
        with raises(ProtocolError):
            self.conn.request("LOCK:order-lock",
                              Message("LOCK", ["order-lock", "sess-1"]), timeout=3)
        t.join(timeout=2)


# ---------------------------------------------------------------------------
# HutuLockClient 高层 API 测试
# ---------------------------------------------------------------------------

class TestHutuLockClient(unittest.TestCase):
    """用 FakeServer 驱动完整客户端流程。"""

    def _make_client(self, fake: FakeServer) -> HutuLockClient:
        client = HutuLockClient.__new__(HutuLockClient)
        client._nodes = [("127.0.0.1", 0)]
        client._connect_timeout = 5.0
        client._lock_timeout = 5.0
        client._watchdog_interval = 60.0   # 测试中不触发
        client._conn = make_connection(fake)
        client._session_id = "sess-test"
        client._held = {}
        return client

    def test_acquire_and_release(self):
        fake = FakeServer()
        client = self._make_client(fake)

        def server():
            # acquire
            fake.recv()  # LOCK
            fake.send("OK order-lock /locks/order-lock/seq-0000000001")
            # release
            fake.recv()  # UNLOCK
            fake.send("RELEASED order-lock")

        t = threading.Thread(target=server, daemon=True)
        t.start()

        token = client.acquire("order-lock")
        assert token.lock_name == "order-lock"
        assert token.seq_node_path == "/locks/order-lock/seq-0000000001"

        client.release(token)
        assert "order-lock" not in client._held
        t.join(timeout=3)
        fake.close()

    def test_context_manager(self):
        fake = FakeServer()
        client = self._make_client(fake)

        def server():
            fake.recv()  # LOCK
            fake.send("OK order-lock /locks/order-lock/seq-0000000001")
            fake.recv()  # UNLOCK
            fake.send("RELEASED order-lock")

        t = threading.Thread(target=server, daemon=True)
        t.start()

        with client.lock("order-lock") as token:
            assert token.lock_name == "order-lock"

        assert "order-lock" not in client._held
        t.join(timeout=3)
        fake.close()

    def test_acquire_wait_then_granted(self):
        fake = FakeServer()
        client = self._make_client(fake)

        def server():
            fake.recv()  # LOCK
            fake.send("WAIT order-lock /locks/order-lock/seq-0000000002")
            time.sleep(0.05)
            fake.send("WATCH_EVENT NODE_DELETED /locks/order-lock/seq-0000000002")
            fake.recv()  # RECHECK
            fake.send("OK order-lock /locks/order-lock/seq-0000000002")

        t = threading.Thread(target=server, daemon=True)
        t.start()

        token = client.acquire("order-lock")
        assert token.seq_node_path == "/locks/order-lock/seq-0000000002"
        t.join(timeout=3)
        fake.close()

    def test_acquire_timeout(self):
        fake = FakeServer()
        client = self._make_client(fake)
        client._lock_timeout = 0.1

        def server():
            fake.recv()  # LOCK — 不响应，触发超时

        t = threading.Thread(target=server, daemon=True)
        t.start()

        with raises(LockTimeoutError):
            client.acquire("order-lock")
        t.join(timeout=2)
        fake.close()

    def test_release_unknown_lock_does_not_raise(self):
        """release 一个未持有的 token 不应抛出（unlock 超时静默处理）。"""
        fake = FakeServer()
        client = self._make_client(fake)

        def server():
            fake.recv()  # UNLOCK — 不响应，触发超时
        t = threading.Thread(target=server, daemon=True)
        t.start()

        token = LockToken("ghost-lock", "/locks/ghost-lock/seq-1", "sess-test")
        # release 内部 unlock 超时会 warning 但不抛出
        client.release(token)
        t.join(timeout=3)
        fake.close()
