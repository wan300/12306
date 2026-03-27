#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
日志流相关 API 接口
"""

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from ..core.terminal_logs import get_terminal_log_broadcaster

router = APIRouter(prefix="/logs", tags=["日志"])


@router.websocket("/ws/terminal")
async def stream_terminal_logs(websocket: WebSocket):
    """WebSocket 实时推送后端终端 stdout/stderr 日志"""
    broadcaster = get_terminal_log_broadcaster()

    await websocket.accept()
    await broadcaster.register(websocket)

    try:
        while True:
            message = await websocket.receive_text()
            if message == "ping":
                await websocket.send_json({"type": "pong"})
    except WebSocketDisconnect:
        pass
    finally:
        await broadcaster.unregister(websocket)
