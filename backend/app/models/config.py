#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
系统配置模型
"""

from datetime import datetime, timedelta
from typing import Optional

from sqlalchemy import DateTime, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from ..core.database import Base


def china_now():
    return datetime.utcnow() + timedelta(hours=8)


class SystemConfig(Base):
    """系统级键值配置。"""

    __tablename__ = "system_configs"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    key: Mapped[str] = mapped_column(String(80), unique=True, index=True)
    value: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    description: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=china_now, onupdate=china_now)

    def __repr__(self) -> str:
        return f"<SystemConfig(key={self.key})>"
