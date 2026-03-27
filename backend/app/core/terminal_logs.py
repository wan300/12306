#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
终端日志捕获与实时广播

将后端 stdout/stderr 双写到终端与内存缓冲，
并通过 WebSocket 实时推送给订阅端。
"""

from __future__ import annotations

import asyncio
import sys
import threading
from collections import deque
from datetime import datetime
from typing import Deque, Dict, List, Optional, Set

from fastapi import WebSocket
from starlette.websockets import WebSocketState

from .config import get_settings

settings = get_settings()


class TerminalLogBroadcaster:
    """终端日志广播器（带内存环形缓冲）"""

    def __init__(self, max_buffer_size: int):
        self._max_buffer_size = max_buffer_size
        self._buffer: Deque[Dict] = deque(maxlen=max_buffer_size)
        self._connections: Set[WebSocket] = set()
        self._seq = 0
        self._lock = asyncio.Lock()

    async def register(self, websocket: WebSocket) -> None:
        """注册连接，并发送当前缓冲快照"""
        async with self._lock:
            self._connections.add(websocket)
            snapshot = list(self._buffer)

        await websocket.send_json({
            "type": "snapshot",
            "logs": snapshot,
        })

    async def unregister(self, websocket: WebSocket) -> None:
        """注销连接"""
        async with self._lock:
            self._connections.discard(websocket)

    async def append(self, stream: str, text: str, source: str = "backend") -> None:
        """追加日志并广播"""
        line = text.strip("\r\n")
        if not line:
            return

        async with self._lock:
            self._seq += 1
            entry = {
                "seq": self._seq,
                "stream": "stderr" if stream == "stderr" else "stdout",
                "text": line,
                "source": source,
                "timestamp": datetime.utcnow().isoformat() + "Z",
            }
            self._buffer.append(entry)
            subscribers = list(self._connections)

        if not subscribers:
            return

        payload = {"type": "log", "log": entry}
        dead_connections: List[WebSocket] = []

        for connection in subscribers:
            try:
                if connection.client_state != WebSocketState.CONNECTED:
                    dead_connections.append(connection)
                    continue
                await connection.send_json(payload)
            except Exception:
                dead_connections.append(connection)

        if dead_connections:
            async with self._lock:
                for connection in dead_connections:
                    self._connections.discard(connection)


class _TerminalTee:
    """终端流双写包装器"""

    def __init__(self, stream_name: str, original_stream):
        self.stream_name = stream_name
        self.original_stream = original_stream
        self._buffer = ""
        self._buffer_lock = threading.Lock()

    def write(self, data):
        text = "" if data is None else str(data)
        written = self.original_stream.write(text)
        self.original_stream.flush()
        self._consume(text)
        return written

    def flush(self):
        self.original_stream.flush()
        self._flush_remaining()

    def isatty(self):
        if hasattr(self.original_stream, "isatty"):
            return self.original_stream.isatty()
        return False

    @property
    def encoding(self):
        return getattr(self.original_stream, "encoding", "utf-8")

    def fileno(self):
        if hasattr(self.original_stream, "fileno"):
            return self.original_stream.fileno()
        raise OSError("stream does not use a file descriptor")

    def _consume(self, text: str) -> None:
        if not text:
            return

        complete_lines: List[str] = []
        with self._buffer_lock:
            self._buffer += text
            parts = self._buffer.splitlines(keepends=True)

            self._buffer = ""
            if parts and not parts[-1].endswith(("\n", "\r")):
                self._buffer = parts.pop()

            for part in parts:
                complete_lines.append(part.rstrip("\r\n"))

        for line in complete_lines:
            publish_terminal_log(self.stream_name, line)

    def _flush_remaining(self) -> None:
        with self._buffer_lock:
            remaining = self._buffer.strip("\r\n")
            self._buffer = ""

        if remaining:
            publish_terminal_log(self.stream_name, remaining)


_broadcaster = TerminalLogBroadcaster(
    max_buffer_size=max(200, int(getattr(settings, "TERMINAL_LOG_BUFFER_SIZE", 2000)))
)

_event_loop: Optional[asyncio.AbstractEventLoop] = None
_capture_installed = False
_stdout_wrapper: Optional[_TerminalTee] = None
_stderr_wrapper: Optional[_TerminalTee] = None


def get_terminal_log_broadcaster() -> TerminalLogBroadcaster:
    """获取日志广播器单例"""
    return _broadcaster


def bind_terminal_log_loop(loop: asyncio.AbstractEventLoop) -> None:
    """绑定用于广播的事件循环"""
    global _event_loop
    _event_loop = loop


def publish_terminal_log(stream: str, text: str, source: str = "backend") -> None:
    """发布一条终端日志（线程安全）"""
    loop = _event_loop
    if loop is None or not loop.is_running():
        return

    try:
        asyncio.run_coroutine_threadsafe(
            _broadcaster.append(stream=stream, text=text, source=source),
            loop,
        )
    except Exception:
        pass


def install_terminal_capture() -> None:
    """安装 stdout/stderr 捕获"""
    global _capture_installed, _stdout_wrapper, _stderr_wrapper

    if _capture_installed:
        return

    _stdout_wrapper = _TerminalTee("stdout", sys.stdout)
    _stderr_wrapper = _TerminalTee("stderr", sys.stderr)

    sys.stdout = _stdout_wrapper
    sys.stderr = _stderr_wrapper
    _capture_installed = True


def uninstall_terminal_capture() -> None:
    """卸载 stdout/stderr 捕获"""
    global _capture_installed, _stdout_wrapper, _stderr_wrapper

    if not _capture_installed:
        return

    if _stdout_wrapper is not None:
        _stdout_wrapper.flush()
        sys.stdout = _stdout_wrapper.original_stream

    if _stderr_wrapper is not None:
        _stderr_wrapper.flush()
        sys.stderr = _stderr_wrapper.original_stream

    _stdout_wrapper = None
    _stderr_wrapper = None
    _capture_installed = False
