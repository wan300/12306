#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
任务相关 API 接口
"""

import json
from typing import Optional
from datetime import datetime, timedelta
from fastapi import APIRouter, HTTPException, Query, Depends
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, func

from ..core.auth import get_current_user
from ..core.database import get_db
from ..models.user import User
from ..models.task import Task, TaskLog, TaskStatus
from ..schemas.task import (
    TaskCreate, TaskUpdate, TaskResponse, TaskListResponse,
    TaskLogResponse, TaskLogsResponse, TaskStatusEnum
)
from ..schemas.common import ResponseBase
from ..tasks.scheduler import get_scheduler

router = APIRouter(prefix="/tasks", tags=["任务"])


async def _get_owned_task(
    task_id: int,
    current_user_id: int,
    db: AsyncSession,
) -> Task:
    """按任务 ID 查询并校验任务归属。"""
    stmt = select(Task).where(Task.id == task_id)
    result = await db.execute(stmt)
    task = result.scalar_one_or_none()

    if not task:
        raise HTTPException(status_code=404, detail="任务不存在")

    if task.user_id != current_user_id:
        raise HTTPException(status_code=403, detail="无权访问该任务")

    return task


# ==================== 任务 CRUD ====================

@router.post("", response_model=ResponseBase[TaskResponse])
async def create_task(
    task_data: TaskCreate,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
):
    """创建抢票任务"""
    if not current_user.is_logged_in:
        raise HTTPException(status_code=400, detail="用户未登录，请先登录 12306")
    
    # 创建任务
    task = Task(
        user_id=current_user.id,
        name=task_data.name,
        from_station=task_data.from_station,
        to_station=task_data.to_station,
        train_date=task_data.train_date,
        train_codes=",".join(task_data.train_codes) if task_data.train_codes else None,
        train_types=",".join(task_data.train_types) if task_data.train_types else None,
        seat_types=",".join(task_data.seat_types),
        start_time_range=task_data.start_time_range,
        passengers=json.dumps([p.model_dump() for p in task_data.passengers], ensure_ascii=False),
        query_interval=task_data.query_interval,
        max_retry_count=task_data.max_retry_count,
        auto_submit=task_data.auto_submit,
        status=TaskStatus.PENDING
    )
    
    db.add(task)
    await db.commit()
    await db.refresh(task)
    
    # 添加创建日志
    log = TaskLog(
        task_id=task.id,
        level="info",
        message=f"任务创建成功: {task.from_station} -> {task.to_station} ({task.train_date})"
    )
    db.add(log)
    await db.commit()
    
    return ResponseBase(
        success=True,
        message="任务创建成功",
        data=TaskResponse.model_validate(task)
    )


@router.get("", response_model=TaskListResponse)
async def list_tasks(
    status: Optional[TaskStatusEnum] = Query(None, description="任务状态筛选"),
    skip: int = Query(0, ge=0, description="跳过条数"),
    limit: int = Query(20, ge=1, le=100, description="返回条数"),
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
):
    """获取任务列表"""
    stmt = select(Task).where(Task.user_id == current_user.id)
    
    if status:
        stmt = stmt.where(Task.status == TaskStatus(status.value))
    
    stmt = stmt.order_by(Task.created_at.desc()).offset(skip).limit(limit)
    
    result = await db.execute(stmt)
    tasks = result.scalars().all()
    
    # 获取总数
    count_stmt = select(func.count()).select_from(Task).where(Task.user_id == current_user.id)
    if status:
        count_stmt = count_stmt.where(Task.status == TaskStatus(status.value))

    count_result = await db.execute(count_stmt)
    total = count_result.scalar_one()
    
    return TaskListResponse(
        total=total,
        tasks=[TaskResponse.model_validate(t) for t in tasks]
    )


@router.get("/{task_id}", response_model=ResponseBase[TaskResponse])
async def get_task(
    task_id: int,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """获取任务详情"""
    task = await _get_owned_task(task_id, current_user.id, db)
    
    return ResponseBase(
        success=True,
        data=TaskResponse.model_validate(task)
    )


@router.put("/{task_id}", response_model=ResponseBase[TaskResponse])
async def update_task(
    task_id: int,
    task_data: TaskUpdate,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
):
    """更新任务"""
    task = await _get_owned_task(task_id, current_user.id, db)
    
    if task.status == TaskStatus.RUNNING:
        raise HTTPException(status_code=400, detail="运行中的任务无法修改")
    
    # 更新字段
    update_data = task_data.model_dump(exclude_unset=True)
    
    if "passengers" in update_data:
        update_data["passengers"] = json.dumps(update_data["passengers"], ensure_ascii=False)
    
    if "train_codes" in update_data and update_data["train_codes"]:
        update_data["train_codes"] = ",".join(update_data["train_codes"])
    if "train_types" in update_data and update_data["train_types"]:
        update_data["train_types"] = ",".join(update_data["train_types"])
    if "seat_types" in update_data and update_data["seat_types"]:
        update_data["seat_types"] = ",".join(update_data["seat_types"])
    
    for key, value in update_data.items():
        setattr(task, key, value)
    
    task.updated_at = datetime.utcnow() + timedelta(hours=8)
    await db.commit()
    await db.refresh(task)
    
    return ResponseBase(
        success=True,
        message="任务更新成功",
        data=TaskResponse.model_validate(task)
    )


@router.delete("/{task_id}", response_model=ResponseBase)
async def delete_task(
    task_id: int,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """删除任务"""
    task = await _get_owned_task(task_id, current_user.id, db)
    
    if task.status == TaskStatus.RUNNING:
        raise HTTPException(status_code=400, detail="运行中的任务无法删除，请先停止")
    
    await db.delete(task)
    await db.commit()
    
    return ResponseBase(success=True, message="任务删除成功")


# ==================== 任务控制 ====================

@router.post("/{task_id}/start", response_model=ResponseBase)
async def start_task(
    task_id: int,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """启动任务"""
    task = await _get_owned_task(task_id, current_user.id, db)
    
    if task.status == TaskStatus.RUNNING:
        raise HTTPException(status_code=400, detail="任务已在运行中")
    
    if task.status == TaskStatus.SUCCESS:
        raise HTTPException(status_code=400, detail="任务已成功完成")
    
    # 更新状态
    task.status = TaskStatus.RUNNING
    task.started_at = datetime.utcnow() + timedelta(hours=8)
    task.retry_count = 0
    
    # 添加日志
    log = TaskLog(
        task_id=task.id,
        level="info",
        message="任务已启动"
    )
    db.add(log)
    
    await db.commit()
    
    # 通知调度器启动任务
    scheduler = get_scheduler()
    await scheduler.start_task(task_id)
    
    return ResponseBase(success=True, message="任务已启动")


@router.post("/{task_id}/stop", response_model=ResponseBase)
async def stop_task(
    task_id: int,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """停止任务"""
    task = await _get_owned_task(task_id, current_user.id, db)
    
    if task.status != TaskStatus.RUNNING:
        raise HTTPException(status_code=400, detail="任务未在运行中")
    
    # 更新状态
    task.status = TaskStatus.PAUSED
    
    # 添加日志
    log = TaskLog(
        task_id=task.id,
        level="info",
        message="任务已暂停"
    )
    db.add(log)
    
    await db.commit()
    
    # 通知调度器停止任务
    scheduler = get_scheduler()
    await scheduler.stop_task(task_id)
    
    return ResponseBase(success=True, message="任务已暂停")


@router.post("/{task_id}/cancel", response_model=ResponseBase)
async def cancel_task(
    task_id: int,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """取消任务"""
    task = await _get_owned_task(task_id, current_user.id, db)
    
    if task.status == TaskStatus.SUCCESS:
        raise HTTPException(status_code=400, detail="已成功的任务无法取消")
    
    # 更新状态
    task.status = TaskStatus.CANCELLED
    task.finished_at = datetime.utcnow() + timedelta(hours=8)
    
    # 添加日志
    log = TaskLog(
        task_id=task.id,
        level="warning",
        message="任务已取消"
    )
    db.add(log)
    
    await db.commit()
    
    # 通知调度器停止任务
    scheduler = get_scheduler()
    await scheduler.stop_task(task_id)
    
    return ResponseBase(success=True, message="任务已取消")


# ==================== 任务日志 ====================

@router.get("/{task_id}/logs", response_model=TaskLogsResponse)
async def get_task_logs(
    task_id: int,
    level: Optional[str] = Query(None, description="日志级别筛选"),
    skip: int = Query(0, ge=0),
    limit: int = Query(50, ge=1, le=500),
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
):
    """获取任务日志"""
    task = await _get_owned_task(task_id, current_user.id, db)
    
    # 查询日志
    stmt = select(TaskLog).where(TaskLog.task_id == task.id)
    
    if level:
        stmt = stmt.where(TaskLog.level == level)
    
    stmt = stmt.order_by(TaskLog.created_at.desc()).offset(skip).limit(limit)
    
    result = await db.execute(stmt)
    logs = result.scalars().all()
    
    return TaskLogsResponse(
        total=len(logs),
        logs=[TaskLogResponse.model_validate(log) for log in logs]
    )
