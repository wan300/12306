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
from ..tasks.scheduler import get_scheduler
from ..utils import notify

router = APIRouter(prefix="/config", tags=["系统配置"])

NOTIFICATION_CONFIG_KEY = "notification_settings"


def _normalize_notification_config(config: dict | None) -> dict:
    normalized = dict(config or {})
    # 通知配置页没有暴露一言开关，默认关闭，避免测试发送额外访问外部接口。
    normalized.setdefault("HITOKOTO", "false")
    return normalized


async def _get_config(
    db: AsyncSession,
    key: str,
) -> SystemConfig | None:
    result = await db.execute(select(SystemConfig).where(SystemConfig.key == key))
    return result.scalar_one_or_none()


@router.get("/notification")
async def get_notification_config(
    _current_user: User = Depends(get_current_user),
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
    _current_user: User = Depends(get_current_user),
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

    scheduler = get_scheduler()
    await scheduler.reload_notification_config()

    return normalized


@router.post("/notification/test")
async def test_notification(
    config: dict,
    _current_user: User = Depends(get_current_user),
):
    """测试发送通知。"""
    try:
        notify.send(
            "12306 助手测试通知",
            "通知服务配置成功。这是一条由测试发送按钮触发的消息。",
            ignore_default_config=True,
            **_normalize_notification_config(config),
        )
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc

    return {"success": True, "message": "测试请求已发送，请检查接收端"}
