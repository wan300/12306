#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
日志流相关 API 接口
"""

from fastapi import APIRouter, WebSocket, WebSocketDisconnect, HTTPException, status

from ..core.auth import decode_access_token
from ..core.config import get_settings
from ..core.terminal_logs import get_terminal_log_broadcaster

router = APIRouter(prefix="/logs", tags=["日志"])
settings = get_settings()


@router.websocket("/ws/terminal")
async def stream_terminal_logs(websocket: WebSocket):
    """WebSocket 实时推送后端终端 stdout/stderr 日志"""
    if not settings.ENABLE_TERMINAL_LOG_STREAM:
        await websocket.close(
            code=status.WS_1008_POLICY_VIOLATION,
            reason="terminal log stream disabled"
        )
        return

    token = websocket.query_params.get("token")
    if not token:
        await websocket.close(
            code=status.WS_1008_POLICY_VIOLATION,
            reason="missing access token"
        )
        return

    try:
        decode_access_token(token)
    except HTTPException:
        await websocket.close(
            code=status.WS_1008_POLICY_VIOLATION,
            reason="invalid access token"
        )
        return

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
