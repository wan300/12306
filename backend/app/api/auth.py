#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
认证相关 API 接口

处理用户登录、二维码获取、状态检查等
"""

import json
from datetime import datetime, timedelta, timezone
from uuid import uuid4
from typing import Dict, Any
from fastapi import APIRouter, HTTPException, WebSocket, Depends
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select

from ..core.database import get_db
from ..core.auth import create_access_token, get_access_token_expires_in, get_current_user
from ..models.user import User
from ..schemas.user import (
    UserResponse, LoginStatusResponse,
    QRCodeResponse, QRCodeStatusResponse, AuthSessionResponse, LoginQRCodeResponse
)
from ..schemas.common import ResponseBase
from ..services.login_service import LoginService, QRCodeStatus

router = APIRouter(prefix="/auth", tags=["认证"])


LOGIN_CHALLENGE_EXPIRE_SECONDS = 180
_login_challenges: Dict[str, Dict[str, Any]] = {}


def _cleanup_expired_challenges():
    """清理过期扫码挑战。"""
    now = datetime.now(timezone.utc)
    expired_keys = []

    for challenge_id, payload in _login_challenges.items():
        created_at = payload.get("created_at")
        if not created_at:
            expired_keys.append(challenge_id)
            continue

        if now - created_at > timedelta(seconds=LOGIN_CHALLENGE_EXPIRE_SECONDS):
            expired_keys.append(challenge_id)

    for key in expired_keys:
        session_key = _login_challenges[key].get("session_key")
        if session_key:
            LoginService(str(session_key)).clear_session()
        _login_challenges.pop(key, None)


async def _build_unique_username(base_name: str, db: AsyncSession) -> str:
    """根据基础名生成唯一用户名。"""
    raw_name = (base_name or "").strip()
    if not raw_name:
        raw_name = "12306_user"

    base = raw_name[:100]
    candidate = base
    index = 1

    while True:
        stmt = select(User).where(User.username == candidate)
        result = await db.execute(stmt)
        existing = result.scalar_one_or_none()
        if not existing:
            return candidate

        suffix = f"_{index}"
        allowed = max(1, 100 - len(suffix))
        candidate = f"{base[:allowed]}{suffix}"
        index += 1


async def _upsert_user_by_railway_account(
    railway_username: str,
    login_service: LoginService,
    db: AsyncSession,
) -> User:
    """根据 12306 账号绑定/创建系统用户，并写入会话。"""
    if not railway_username:
        raise HTTPException(status_code=500, detail="未获取到 12306 用户信息")

    stmt = select(User).where(
        User.railway_username == railway_username,
        User.is_active == True,
    )
    result = await db.execute(stmt)
    user = result.scalar_one_or_none()

    if not user:
        username = await _build_unique_username(railway_username, db)
        user = User(
            username=username,
            railway_username=railway_username,
            is_active=True,
        )
        db.add(user)
        await db.flush()

    user.is_logged_in = True
    user.railway_username = railway_username
    user.session_data = json.dumps(login_service.session.to_dict())
    user.login_time = login_service.session.login_time

    await db.commit()
    await db.refresh(user)

    return user


def _build_auth_session(user: User) -> AuthSessionResponse:
    """生成前端可用的认证会话结构。"""
    access_token = create_access_token(
        user_id=user.id,
        username=user.username,
    )
    return AuthSessionResponse(
        access_token=access_token,
        token_type="bearer",
        expires_in=get_access_token_expires_in(),
        user=UserResponse.model_validate(user),
    )


# ==================== 用户管理 ====================

@router.post("/users", response_model=ResponseBase[UserResponse])
async def create_user():
    """已废弃：平台用户由扫码登录自动创建"""
    raise HTTPException(
        status_code=410,
        detail="该接口已废弃，请使用扫码登录自动创建账号",
    )


@router.get("/users", response_model=ResponseBase[list])
async def list_users(current_user: User = Depends(get_current_user)):
    """获取用户列表（仅返回当前用户）"""
    return ResponseBase(
        success=True,
        data=[UserResponse.model_validate(current_user)]
    )


@router.get("/users/{user_id}", response_model=ResponseBase[UserResponse])
async def get_user(user_id: int, current_user: User = Depends(get_current_user)):
    """获取用户信息（仅允许查询本人）"""
    if user_id != current_user.id:
        raise HTTPException(status_code=403, detail="无权访问该用户")

    return ResponseBase(
        success=True,
        data=UserResponse.model_validate(current_user)
    )


# ==================== 登录状态 ====================

@router.get("/status/{user_id}", response_model=ResponseBase[LoginStatusResponse])
async def check_login_status(
    user_id: int,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
):
    """检查用户登录状态"""
    if user_id != current_user.id:
        raise HTTPException(status_code=403, detail="无权访问该用户")

    user = current_user
    
    login_service = LoginService(str(user.id))
    
    # 检查会话是否有效
    is_valid = False
    if login_service.is_logged_in():
        try:
            is_valid = await login_service.check_login_status()
        except Exception:
            pass
        finally:
            await login_service.close()
    
    # 更新数据库状态
    if user.is_logged_in != is_valid:
        user.is_logged_in = is_valid
        await db.commit()
    
    return ResponseBase(
        success=True,
        data=LoginStatusResponse(
            is_logged_in=is_valid,
            username=user.username,
            railway_username=login_service.get_username(),
            login_time=user.login_time,
            expire_time=user.session_expire_time
        )
    )


@router.get("/me", response_model=ResponseBase[UserResponse])
async def get_current_login_user(current_user: User = Depends(get_current_user)):
    """获取当前登录用户信息"""
    return ResponseBase(
        success=True,
        data=UserResponse.model_validate(current_user)
    )


# ==================== 二维码登录 ====================

@router.post("/login/qrcode", response_model=ResponseBase[LoginQRCodeResponse])
async def create_login_qr_code():
    """创建扫码登录挑战并返回二维码。"""
    _cleanup_expired_challenges()

    challenge_id = uuid4().hex
    session_key = f"login_challenge_{challenge_id}"
    login_service = LoginService(session_key)

    try:
        uuid, image_b64, error = await login_service.get_qr_code()
        if error:
            raise HTTPException(status_code=500, detail=f"获取二维码失败: {error}")

        _login_challenges[challenge_id] = {
            "session_key": session_key,
            "uuid": uuid,
            "created_at": datetime.now(timezone.utc),
        }

        return ResponseBase(
            success=True,
            data=LoginQRCodeResponse(
                challenge_id=challenge_id,
                uuid=uuid,
                image_base64=image_b64,
            ),
        )
    finally:
        await login_service.close()


@router.get("/login/qrcode/{challenge_id}/status", response_model=ResponseBase[QRCodeStatusResponse])
async def check_login_qr_status(
    challenge_id: str,
    db: AsyncSession = Depends(get_db),
):
    """检查扫码登录挑战状态，并在成功后签发平台令牌。"""
    _cleanup_expired_challenges()

    challenge = _login_challenges.get(challenge_id)
    if not challenge:
        raise HTTPException(status_code=404, detail="登录挑战不存在或已过期，请刷新二维码")

    login_service = LoginService(str(challenge["session_key"]))

    try:
        status, _ = await login_service.check_qr_status(challenge["uuid"])

        status_messages = {
            QRCodeStatus.WAITING: "等待扫码",
            QRCodeStatus.SCANNED: "已扫码，请确认",
            QRCodeStatus.CONFIRMED: "登录成功",
            QRCodeStatus.EXPIRED: "二维码已过期",
            QRCodeStatus.ERROR: "系统异常",
        }

        auth_payload = None
        is_success = False

        if status == QRCodeStatus.CONFIRMED:
            auth_success = await login_service.authenticate()
            if auth_success:
                railway_username = login_service.get_username()
                user = await _upsert_user_by_railway_account(
                    railway_username=railway_username,
                    login_service=login_service,
                    db=db,
                )
                auth_payload = _build_auth_session(user)
                is_success = auth_payload is not None
            else:
                status_messages[QRCodeStatus.CONFIRMED] = "登录确认失败"

            _login_challenges.pop(challenge_id, None)
            login_service.clear_session()
        elif status in (QRCodeStatus.EXPIRED, QRCodeStatus.ERROR):
            _login_challenges.pop(challenge_id, None)
            login_service.clear_session()

        return ResponseBase(
            success=True,
            data=QRCodeStatusResponse(
                status=status,
                message=status_messages.get(status, "未知状态"),
                is_success=is_success,
                auth=auth_payload,
            ),
        )
    finally:
        await login_service.close()


@router.post("/qrcode/{user_id}", response_model=ResponseBase[QRCodeResponse])
async def get_qr_code(
    user_id: int,
    db: AsyncSession = Depends(get_db)
):
    """已废弃：请使用 /auth/login/qrcode"""
    raise HTTPException(status_code=410, detail="该接口已废弃，请使用 /auth/login/qrcode")


@router.get("/qrcode/{user_id}/status", response_model=ResponseBase[QRCodeStatusResponse])
async def check_qr_status(
    user_id: int,
    uuid: str,
    db: AsyncSession = Depends(get_db)
):
    """已废弃：请使用 /auth/login/qrcode/{challenge_id}/status"""
    raise HTTPException(
        status_code=410,
        detail="该接口已废弃，请使用 /auth/login/qrcode/{challenge_id}/status",
    )


# ==================== WebSocket 扫码登录 ====================

@router.websocket("/ws/login/{user_id}")
async def websocket_login(
    websocket: WebSocket,
    user_id: int
):
    """已废弃：请使用 HTTP 轮询扫码登录接口"""
    await websocket.accept()
    await websocket.send_json({
        "type": "error",
        "message": "该接口已废弃，请使用 /auth/login/qrcode + /auth/login/qrcode/{challenge_id}/status"
    })
    await websocket.close(code=1000)


@router.post("/logout", response_model=ResponseBase)
async def logout(
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
):
    """登出"""
    # 清除会话
    login_service = LoginService(str(current_user.id))
    login_service.clear_session()
    
    # 更新数据库
    current_user.is_logged_in = False
    current_user.session_data = None
    await db.commit()
    
    return ResponseBase(success=True, message="已登出")


@router.delete("/users/{user_id}", response_model=ResponseBase)
async def delete_user(
    user_id: int,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
):
    """删除用户"""
    if user_id != current_user.id:
        raise HTTPException(status_code=403, detail="无权删除该用户")

    stmt = select(User).where(User.id == user_id)
    result = await db.execute(stmt)
    user = result.scalar_one_or_none()
    
    if not user:
        raise HTTPException(status_code=404, detail="用户不存在")
    
    # 清除用户会话
    login_service = LoginService(str(user_id))
    login_service.clear_session()
    
    # 软删除用户（将 is_active 设为 False）
    user.is_active = False
    user.is_logged_in = False
    user.session_data = None
    await db.commit()
    
    return ResponseBase(success=True, message="用户已删除")
