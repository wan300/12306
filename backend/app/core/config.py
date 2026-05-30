#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
核心配置模块

包含应用的所有配置项
"""

import os
import sys
from pathlib import Path
from functools import lru_cache
from pydantic_settings import BaseSettings
from typing import Optional


# 获取数据目录的绝对路径
def get_data_dir() -> Path:
    """
    获取数据目录的绝对路径
    - 开发环境：使用当前目录的 data 文件夹
    - 打包后：使用可执行文件同级的 data 文件夹
    """
    if getattr(sys, 'frozen', False):
        # 打包后的环境
        base_path = Path(sys.executable).parent
    else:
        # 开发环境
        base_path = Path(__file__).parent.parent.parent
    
    data_dir = base_path / "data"
    return data_dir


class Settings(BaseSettings):
    """应用配置"""
    
    # 应用信息
    APP_NAME: str = "12306 自动化抢票系统"
    APP_VERSION: str = "1.0.0"
    DEBUG: bool = True
    
    # API 配置
    API_V1_PREFIX: str = "/api/v1"
    
    # 数据目录
    @property
    def DATA_DIR(self) -> Path:
        return get_data_dir()
    
    # 数据库配置
    @property
    def DATABASE_URL(self) -> str:
        db_path = self.DATA_DIR / "12306.db"
        return f"sqlite+aiosqlite:///{db_path}"
    
    # 会话配置
    @property
    def SESSION_DIR(self) -> str:
        return str(self.DATA_DIR / "sessions")
    
    # 任务调度配置
    SCHEDULER_TIMEZONE: str = "Asia/Shanghai"
    DEFAULT_QUERY_INTERVAL: int = 5  # 默认刷票间隔（秒）
    MIN_QUERY_INTERVAL: int = 3      # 最小刷票间隔（秒）
    MAX_QUERY_INTERVAL: int = 60     # 最大刷票间隔（秒）
    
    # 12306 相关配置
    @property
    def STATION_FILE(self) -> str:
        return str(self.DATA_DIR / "assets" / "station_name.js")
    
    # 日志配置
    LOG_LEVEL: str = "INFO"
    TERMINAL_LOG_BUFFER_SIZE: int = 2000
    
    @property
    def LOG_DIR(self) -> str:
        return str(self.DATA_DIR / "logs")
    
    # CORS 配置
    # Electron 在 file 协议下通常发送 Origin: null
    CORS_ORIGINS: list = [
        "http://localhost:5173",
        "http://localhost:3000",
        "http://127.0.0.1:5173",
        "http://127.0.0.1:3000",
        "null",
        "file://",
    ]
    
    # JWT 配置（如果需要用户认证）
    SECRET_KEY: str = "please-change-secret-key-in-production"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 60 * 24 * 7  # 7天

    # 安全开关：默认关闭全局终端日志流，避免多用户环境下的信息泄露
    ENABLE_TERMINAL_LOG_STREAM: bool = False
    
    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"
        case_sensitive = True


@lru_cache()
def get_settings() -> Settings:
    """获取配置单例"""
    return Settings()


# 创建必要的目录
def ensure_directories():
    """确保必要的目录存在"""
    settings = get_settings()
    
    dirs = [
        settings.DATA_DIR,
        Path(settings.SESSION_DIR),
        Path(settings.LOG_DIR),
        settings.DATA_DIR / "assets",
    ]
    
    for d in dirs:
        d.mkdir(parents=True, exist_ok=True)
    
    # 打印路径信息便于调试
    print(f"[配置] 数据目录: {settings.DATA_DIR}")
    print(f"[配置] 数据库路径: {settings.DATABASE_URL}")
