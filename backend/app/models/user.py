#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
用户模型

存储用户信息和登录会话
"""

from datetime import datetime
from typing import Optional
from sqlalchemy import String, Text, DateTime, Boolean
from sqlalchemy.orm import Mapped, mapped_column

from ..core.database import Base


class User(Base):
    """用户表"""
    __tablename__ = "users"
    
    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    
    # 用户标识（可以是12306用户名或自定义名称）
    username: Mapped[str] = mapped_column(String(100), unique=True, index=True)
    
    # 12306 登录相关
    railway_username: Mapped[Optional[str]] = mapped_column(String(100), nullable=True, unique=True, index=True)
    
    # Session 数据（JSON 格式存储 cookies 等）
    session_data: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    
    # 状态
    is_logged_in: Mapped[bool] = mapped_column(Boolean, default=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    
    # 时间戳
    login_time: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    session_expire_time: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
    
    def __repr__(self) -> str:
        return f"<User(id={self.id}, username={self.username}, is_logged_in={self.is_logged_in})>"
