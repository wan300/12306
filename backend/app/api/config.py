#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
系统配置 API
"""

import json
from json import JSONDecodeError

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..core.auth import get_current_user
from ..core.database import get_db
from ..models.config import SystemConfig
from ..models.user import User
from ..utils import notify
from ..core.workspace_access import workspace_access
from ..services.notification_service import normalize_config, channel_config
from ..services.notification_service import notification_service

router = APIRouter(prefix="/config", tags=["系统配置"])

NOTIFICATION_CONFIG_KEY = "notification_settings"


def _normalize_notification_config(config: dict | None) -> dict:
    return normalize_config(config)


async def _get_config(
    db: AsyncSession,
    key: str,
) -> SystemConfig | None:
    result = await db.execute(select(SystemConfig).where(SystemConfig.key == key))
    return result.scalar_one_or_none()


@router.get("/notification")
async def get_notification_config(
    _current_user = Depends(workspace_access),
    db: AsyncSession = Depends(get_db),
):
    """获取通知配置。"""
    config = await _get_config(db, NOTIFICATION_CONFIG_KEY)
    if not config or not config.value:
        return _normalize_notification_config({})

    try:
        return _normalize_notification_config(json.loads(config.value))
    except JSONDecodeError:
        return _normalize_notification_config({})


@router.post("/notification")
async def update_notification_config(
    config: dict,
    _current_user = Depends(workspace_access),
    db: AsyncSession = Depends(get_db),
):
    """更新通知配置。"""
    normalized = _normalize_notification_config(config)
    config_obj = await _get_config(db, NOTIFICATION_CONFIG_KEY)
    value = json.dumps(normalized, ensure_ascii=False)

    if config_obj:
        config_obj.value = value
    else:
        config_obj = SystemConfig(
            key=NOTIFICATION_CONFIG_KEY,
            value=value,
            description="通知渠道配置",
        )
        db.add(config_obj)

    await db.commit()

    return normalized


@router.post("/notification/test")
async def test_notification(
    config: dict,
    _current_user = Depends(workspace_access),
):
    """测试发送通知。"""
    try:
        await notification_service.deliver(
            ("机票" if config.get("test_type") == "flight" else "火车票") + "助手测试通知",
            "通知服务配置成功。这是一条由测试发送按钮触发的消息。",
            _normalize_notification_config(config),
        )
    except Exception as exc:
        raise HTTPException(status_code=500, detail="通知发送失败，请检查渠道配置") from None

    return {"success": True, "message": "测试请求已发送，请检查接收端"}
