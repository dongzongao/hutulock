"""HutuLock 文本协议（行分隔，UTF-8）

格式：{TYPE} {arg0} {arg1} ...\n
"""
from __future__ import annotations
from dataclasses import dataclass, field
from typing import List

# 客户端请求命令
CMD_CONNECT = "CONNECT"
CMD_LOCK    = "LOCK"
CMD_UNLOCK  = "UNLOCK"
CMD_RECHECK = "RECHECK"
CMD_RENEW   = "RENEW"

# 服务端响应命令
CMD_CONNECTED   = "CONNECTED"
CMD_OK          = "OK"
CMD_WAIT        = "WAIT"
CMD_RELEASED    = "RELEASED"
CMD_RENEWED     = "RENEWED"
CMD_REDIRECT    = "REDIRECT"
CMD_WATCH_EVENT = "WATCH_EVENT"
CMD_ERROR       = "ERROR"


@dataclass
class Message:
    type: str
    args: List[str] = field(default_factory=list)

    @staticmethod
    def parse(line: str) -> "Message":
        parts = line.strip().split(None, 1)
        if not parts:
            raise ValueError(f"empty message: {line!r}")
        cmd = parts[0].upper()
        args = parts[1].split() if len(parts) > 1 else []
        return Message(type=cmd, args=args)

    def serialize(self) -> str:
        if not self.args:
            return self.type
        return self.type + " " + " ".join(self.args)

    def arg(self, index: int) -> str:
        if index >= len(self.args):
            raise IndexError(f"arg[{index}] missing in: {self.serialize()!r}")
        return self.args[index]

    def __str__(self) -> str:
        return self.serialize()
