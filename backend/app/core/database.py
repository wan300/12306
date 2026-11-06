#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
数据库配置模块

使用 SQLAlchemy 2.0 异步模式
"""

from sqlalchemy.ext.asyncio import AsyncSession, create_async_engine, async_sessionmaker
from sqlalchemy.orm import DeclarativeBase
from typing import AsyncGenerator
from sqlalchemy import inspect, text

from .config import get_settings

settings = get_settings()

# 创建异步引擎
engine = create_async_engine(
    settings.DATABASE_URL,
    echo=settings.DEBUG,
    future=True,
)

# 创建异步会话工厂
AsyncSessionLocal = async_sessionmaker(
    engine,
    class_=AsyncSession,
    expire_on_commit=False,
    autoflush=False,
    autocommit=False,
)


class Base(DeclarativeBase):
    """模型基类"""
    pass


async def get_db() -> AsyncGenerator[AsyncSession, None]:
    """获取数据库会话（依赖注入）"""
    async with AsyncSessionLocal() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise
        finally:
            await session.close()


async def init_db():
    """初始化数据库（创建表）"""
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
        for table in ("tasks", "flight_monitors"):
            columns = await conn.run_sync(lambda connection: {column["name"] for column in inspect(connection).get_columns(table)})
            if "notification_run_id" not in columns:
                await conn.execute(text(f"ALTER TABLE {table} ADD COLUMN notification_run_id VARCHAR(36)"))
            await conn.execute(text(f"UPDATE {table} SET notification_run_id = lower(hex(randomblob(16))) WHERE notification_run_id IS NULL"))
        columns = await conn.run_sync(lambda connection: {column["name"] for column in inspect(connection).get_columns("notification_events")})
        if "delivered_channels" not in columns:
            await conn.execute(text("ALTER TABLE notification_events ADD COLUMN delivered_channels TEXT NOT NULL DEFAULT '[]'"))


async def close_db():
    """关闭数据库连接"""
    await engine.dispose()
