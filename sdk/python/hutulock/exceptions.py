"""HutuLock 异常定义"""


class HutuLockError(Exception):
    """基础异常"""


class ConnectionError(HutuLockError):
    """连接失败或断开"""


class LockTimeoutError(HutuLockError):
    """获取锁超时"""


class NotLeaderError(HutuLockError):
    """当前节点不是 Leader，需要重定向"""

    def __init__(self, leader_id: str = ""):
        self.leader_id = leader_id
        super().__init__(f"not leader, redirect to: {leader_id}")


class SessionExpiredError(HutuLockError):
    """会话已过期"""


class ProtocolError(HutuLockError):
    """协议解析错误"""
