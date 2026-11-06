#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
任务模型

存储抢票任务信息和日志
"""

from datetime import datetime, timedelta
from typing import Optional
from enum import Enum as PyEnum
from uuid import uuid4
from sqlalchemy import String, Text, DateTime, Boolean, Integer, ForeignKey, Enum
from sqlalchemy.orm import Mapped, mapped_column, relationship

from ..core.database import Base


def china_now():
    return datetime.utcnow() + timedelta(hours=8)


class TaskStatus(str, PyEnum):
    """任务状态"""
    PENDING = "pending"        # 待运行
    RUNNING = "running"        # 运行中
    PAUSED = "paused"          # 已暂停
    SUCCESS = "success"        # 成功
    FAILED = "failed"          # 失败
    CANCELLED = "cancelled"    # 已取消


class Task(Base):
    """抢票任务表"""
    __tablename__ = "tasks"
    
    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    
    # 关联用户
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    
    # 任务名称
    name: Mapped[str] = mapped_column(String(200))
    notification_run_id: Mapped[Optional[str]] = mapped_column(String(36), nullable=True, default=lambda: str(uuid4()))
    
    # 行程信息
    from_station: Mapped[str] = mapped_column(String(50))          # 出发站
    to_station: Mapped[str] = mapped_column(String(50))            # 到达站
    train_date: Mapped[str] = mapped_column(String(20))            # 出发日期 YYYY-MM-DD
    
    # 筛选条件
    train_codes: Mapped[Optional[str]] = mapped_column(Text, nullable=True)   # 车次号（逗号分隔，如 G101,G103）
    train_types: Mapped[Optional[str]] = mapped_column(String(50), nullable=True)  # 车次类型（如 G,D）
    seat_types: Mapped[str] = mapped_column(String(100))           # 席别（优先级，如 O,M,9）
    start_time_range: Mapped[Optional[str]] = mapped_column(String(50), nullable=True)  # 出发时间范围（如 08:00-12:00）
    
    # 乘车人（JSON 数组）
    passengers: Mapped[str] = mapped_column(Text)
    
    # 任务配置
    query_interval: Mapped[int] = mapped_column(Integer, default=5)    # 刷票间隔（秒）
    max_retry_count: Mapped[int] = mapped_column(Integer, default=100)  # 最大重试次数
    auto_submit: Mapped[bool] = mapped_column(Boolean, default=True)   # 自动提交订单
    
    # 状态
    status: Mapped[TaskStatus] = mapped_column(
        Enum(TaskStatus), 
        default=TaskStatus.PENDING
    )
    retry_count: Mapped[int] = mapped_column(Integer, default=0)
    
    # 结果
    order_id: Mapped[Optional[str]] = mapped_column(String(100), nullable=True)
    result_message: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    
    # 时间戳
    created_at: Mapped[datetime] = mapped_column(DateTime, default=china_now)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=china_now, onupdate=china_now)
    started_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    finished_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    
    # 关联日志
    logs = relationship("TaskLog", back_populates="task", cascade="all, delete-orphan")
    
    def __repr__(self) -> str:
        return f"<Task(id={self.id}, name={self.name}, status={self.status})>"


class TaskLog(Base):
    """任务日志表"""
    __tablename__ = "task_logs"
    
    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    task_id: Mapped[int] = mapped_column(ForeignKey("tasks.id"), index=True)
    
    # 日志级别: info, warning, error, success
    level: Mapped[str] = mapped_column(String(20), default="info")
    
    # 日志内容
    message: Mapped[str] = mapped_column(Text)
    
    # 详细数据（JSON）
    details: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    
    # 时间戳
    created_at: Mapped[datetime] = mapped_column(DateTime, default=china_now)
    
    # 关联任务
    task = relationship("Task", back_populates="logs")
    
    def __repr__(self) -> str:
        return f"<TaskLog(id={self.id}, level={self.level}, message={self.message[:50]})>"
