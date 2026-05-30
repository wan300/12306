#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
用户相关的 Pydantic 模式
"""

from datetime import datetime
from typing import Optional
from pydantic import BaseModel, Field


class UserBase(BaseModel):
    """用户基础模式"""
    username: str = Field(..., min_length=1, max_length=100)


class UserCreate(UserBase):
    """创建用户"""
    pass


class UserUpdate(BaseModel):
    """更新用户"""
    username: Optional[str] = Field(None, min_length=1, max_length=100)
    is_active: Optional[bool] = None


class UserResponse(UserBase):
    """用户响应"""
    id: int
    railway_username: Optional[str] = None
    is_logged_in: bool
    is_active: bool
    login_time: Optional[datetime] = None
    created_at: datetime
    
    class Config:
        from_attributes = True


class LoginStatusResponse(BaseModel):
    """登录状态响应"""
    is_logged_in: bool
    username: Optional[str] = None
    railway_username: Optional[str] = None
    login_time: Optional[datetime] = None
    expire_time: Optional[datetime] = None


class AuthSessionResponse(BaseModel):
    """认证会话信息"""
    access_token: str
    token_type: str = "bearer"
    expires_in: int
    user: UserResponse


class QRCodeResponse(BaseModel):
    """二维码响应"""
    uuid: str
    image_base64: str  # Base64 编码的图片


class LoginQRCodeResponse(QRCodeResponse):
    """扫码登录挑战二维码响应"""
    challenge_id: str


class QRCodeStatusResponse(BaseModel):
    """二维码状态响应"""
    status: int  # 0-等待, 1-已扫码, 2-确认登录, 3-过期
    message: str
    is_success: bool = False
    auth: Optional[AuthSessionResponse] = None
