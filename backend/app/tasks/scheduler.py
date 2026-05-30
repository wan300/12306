#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
任务调度器

使用 APScheduler 实现定时刷票和任务管理
"""

import json
import asyncio
from datetime import datetime, timedelta
from typing import Dict, Optional, List
from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.jobstores.memory import MemoryJobStore
from apscheduler.executors.asyncio import AsyncIOExecutor
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, update

from ..core.config import get_settings
from ..core.database import AsyncSessionLocal
from ..models.config import SystemConfig
from ..models.user import User
from ..models.task import Task, TaskLog, TaskStatus
from ..services.login_service import LoginService
from ..services.query_service import QueryService
from ..services.order_service import OrderService, Passenger
from ..utils import notify

settings = get_settings()


class TicketScheduler:
    """抢票调度器"""
    
    _instance: Optional["TicketScheduler"] = None
    
    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._initialized = False
        return cls._instance
    
    def __init__(self):
        if self._initialized:
            return
        
        self._initialized = True
        
        # 配置调度器
        jobstores = {
            'default': MemoryJobStore()
        }
        executors = {
            'default': AsyncIOExecutor()
        }
        job_defaults = {
            'coalesce': True,
            'max_instances': 3
        }
        
        self.scheduler = AsyncIOScheduler(
            jobstores=jobstores,
            executors=executors,
            job_defaults=job_defaults,
            timezone=settings.SCHEDULER_TIMEZONE
        )
        
        # 活动任务追踪
        self._active_tasks: Dict[int, bool] = {}  # task_id -> is_running
        
        # 服务实例缓存
        self._login_services: Dict[str, LoginService] = {}

        # 通知配置缓存
        self._notification_config: Dict = {}
    
    def start(self):
        """启动调度器"""
        if not self.scheduler.running:
            self.scheduler.start()
            print("[调度] 调度器已启动")
            asyncio.create_task(self.reload_notification_config())

    async def reload_notification_config(self):
        """重新加载通知配置。"""
        async with AsyncSessionLocal() as db:
            stmt = select(SystemConfig).where(SystemConfig.key == "notification_settings")
            result = await db.execute(stmt)
            config = result.scalar_one_or_none()

            if not config or not config.value:
                self._notification_config = {}
                return

            try:
                loaded = json.loads(config.value)
                loaded.setdefault("HITOKOTO", "false")
                self._notification_config = loaded
                print("[调度] 通知配置已加载")
            except Exception as exc:
                self._notification_config = {}
                print(f"[调度] 通知配置解析失败: {exc}")

    def _send_notification(self, title: str, content: str):
        """按当前通知配置发送消息。"""
        if not self._notification_config:
            return

        try:
            notify.send(
                title,
                content,
                ignore_default_config=True,
                **self._notification_config,
            )
        except Exception as exc:
            print(f"[调度] 发送通知失败: {exc}")

    def _task_summary(self, task: Task) -> str:
        return f"{task.name}\n行程: {task.from_station} -> {task.to_station}\n日期: {task.train_date}"
    
    def shutdown(self):
        """关闭调度器"""
        if self.scheduler.running:
            self.scheduler.shutdown(wait=True)
            print("[调度] 调度器已关闭")

    async def resume_tasks(self):
        """恢复运行中的任务"""
        print("[调度] 正在恢复运行中的任务...")
        async with AsyncSessionLocal() as db:
            stmt = select(Task).where(Task.status == TaskStatus.RUNNING)
            result = await db.execute(stmt)
            tasks = result.scalars().all()
            
            for task in tasks:
                print(f"[调度] 恢复任务 {task.id}: {task.name}")
                await self.start_task(task.id)

    
    async def start_task(self, task_id: int):
        """启动抢票任务"""
        if task_id in self._active_tasks:
            return
        
        self._active_tasks[task_id] = True
        
        # 添加定时任务
        job_id = f"ticket_task_{task_id}"
        
        # 获取任务信息以确定刷票间隔
        async with AsyncSessionLocal() as db:
            stmt = select(Task).where(Task.id == task_id)
            result = await db.execute(stmt)
            task = result.scalar_one_or_none()
            
            if not task:
                del self._active_tasks[task_id]
                return
            
            interval = max(task.query_interval, settings.MIN_QUERY_INTERVAL)
        
        self.scheduler.add_job(
            self._run_ticket_task,
            'interval',
            seconds=interval,
            id=job_id,
            args=[task_id],
            replace_existing=True
        )
        
        print(f"[调度] 任务 {task_id} 已启动 (间隔: {interval}秒)")
        
        # 立即执行一次 (异步执行，避免阻塞 API)
        asyncio.create_task(self._run_ticket_task(task_id))
    
    async def stop_task(self, task_id: int):
        """停止抢票任务"""
        job_id = f"ticket_task_{task_id}"
        
        if task_id in self._active_tasks:
            del self._active_tasks[task_id]
        
        try:
            self.scheduler.remove_job(job_id)
            print(f"[Scheduler] 任务 {task_id} 已停止")
        except Exception:
            pass
    
    async def _run_ticket_task(self, task_id: int):
        """执行抢票任务"""
        if task_id not in self._active_tasks:
            return
        
        async with AsyncSessionLocal() as db:
            # 获取任务
            stmt = select(Task).where(Task.id == task_id)
            result = await db.execute(stmt)
            task = result.scalar_one_or_none()
            
            if not task:
                await self.stop_task(task_id)
                return
            
            # 检查任务状态
            if task.status != TaskStatus.RUNNING:
                await self.stop_task(task_id)
                return
            
            # 检查重试次数 (max_retry_count < 0 表示无限重试)
            if task.max_retry_count > 0 and task.retry_count >= task.max_retry_count:
                task.status = TaskStatus.FAILED
                task.result_message = "超过最大重试次数"
                task.finished_at = datetime.utcnow() + timedelta(hours=8)
                
                await self._add_log(db, task_id, "error", "任务失败：超过最大重试次数")
                self._send_notification(
                    "12306 助手：抢票失败",
                    f"{self._task_summary(task)}\n原因: 超过最大重试次数\n时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
                )
                await db.commit()
                await self.stop_task(task_id)
                return
            
            # 增加重试计数
            task.retry_count += 1
            await db.commit()
            
            # 获取用户登录信息
            stmt = select(User).where(User.id == task.user_id)
            result = await db.execute(stmt)
            user = result.scalar_one_or_none()
            
            if not user or not user.session_data:
                await self._add_log(db, task_id, "error", "用户未登录")
                task.status = TaskStatus.FAILED
                task.result_message = "用户未登录"
                self._send_notification(
                    "12306 助手：抢票异常",
                    f"{self._task_summary(task)}\n原因: 用户未登录或会话丢失\n请重新登录后重启任务。",
                )
                await db.commit()
                await self.stop_task(task_id)
                return
            
            session_data = json.loads(user.session_data)
            # 兼容处理：如果是新格式（包含 cookies 键），取 cookies；否则假设整个对象就是 cookies 字典
            if "cookies" in session_data and isinstance(session_data["cookies"], dict):
                cookies = session_data["cookies"]
            else:
                cookies = session_data
            
            # 执行查票
            await self._add_log(db, task_id, "info", f"第 {task.retry_count} 次刷票...")
            await db.commit()
            
            try:
                success, order_id, message, extra_data = await self._query_and_order(
                    task, cookies, db
                )
                
                if success:
                    task.status = TaskStatus.SUCCESS
                    task.order_id = order_id
                    task.result_message = message
                    task.finished_at = datetime.utcnow() + timedelta(hours=8)
                    
                    await self._add_log(db, task_id, "success", f"抢票成功！订单号: {order_id}")
                    passenger_names = ", ".join(extra_data.get("passenger_names", [])) if extra_data else ""
                    self._send_notification(
                        "12306 助手：抢票成功",
                        (
                            f"{self._task_summary(task)}\n"
                            f"订单号: {order_id}\n"
                            f"车次: {extra_data.get('train_code', '') if extra_data else ''}\n"
                            f"时间: {extra_data.get('start_time', '') if extra_data else ''}"
                            f" - {extra_data.get('arrive_time', '') if extra_data else ''}\n"
                            f"席别: {extra_data.get('seat_name', '') if extra_data else ''}\n"
                            f"乘车人: {passenger_names}\n"
                            "请尽快前往 12306 支付。"
                        ),
                    )
                    await self.stop_task(task_id)
                else:
                    await self._add_log(db, task_id, "info", message)
                    
                    # 检查是否因为未登录导致失败
                    if "未登录" in message or "登录已过期" in message:
                        task.status = TaskStatus.FAILED
                        task.result_message = message
                        task.finished_at = datetime.utcnow() + timedelta(hours=8)
                        await self._add_log(db, task_id, "error", f"任务停止: {message}")
                        self._send_notification(
                            "12306 助手：抢票异常",
                            f"{self._task_summary(task)}\n原因: {message}",
                        )
                        await self.stop_task(task_id)
                
                await db.commit()
                
            except Exception as e:
                await self._add_log(db, task_id, "error", f"执行异常: {str(e)}")
                self._send_notification(
                    "12306 助手：任务执行异常",
                    f"{self._task_summary(task)}\n异常: {str(e)}",
                )
                await db.commit()
    
    async def _query_and_order(
        self,
        task: Task,
        cookies: Dict,
        db: AsyncSession
    ) -> tuple[bool, str, str, Optional[Dict]]:
        """查票并下单"""
        query_service = QueryService(cookies)
        
        try:
            # 处理车次类型
            train_types = None
            if task.train_types:
                train_types = task.train_types.split(",")
            
            # 处理时间范围
            time_range = None
            if task.start_time_range:
                parts = task.start_time_range.split("-")
                if len(parts) == 2:
                    time_range = (parts[0].strip(), parts[1].strip())
            
            # 查票
            trains, error = await query_service.query(
                from_station=task.from_station,
                to_station=task.to_station,
                train_date=task.train_date,
                train_types=train_types,
                start_time_range=time_range,
                only_has_ticket=False
            )
            
            if error:
                return False, "", f"查票失败: {error}", None
            
            if not trains:
                return False, "", "未查询到任何车次", None
            
            # 过滤指定车次
            if task.train_codes:
                target_codes = set(task.train_codes.split(","))
                trains = [t for t in trains if t.train_code in target_codes]
                
                if not trains:
                    return False, "", "指定车次不存在或已停运", None
            
            # 获取席别优先级
            seat_types = task.seat_types.split(",") if task.seat_types else ["O"]
            
            # 用于记录扫描详情
            scan_details = []
            
            # 遍历车次和席别尝试购票
            for train in trains:
                # 检查任务是否还在运行列表
                if task.id not in self._active_tasks:
                   return False, "", "任务已暂停或停止", None

                # 收集该车次的席位状态
                seat_status_list = []
                has_ticket_for_train = False
                
                for seat_type in seat_types:
                    # 检查该席别是否有票
                    seat_map = {
                        "9": ("商务座", train.business_seat),
                        "M": ("一等座", train.first_seat),
                        "O": ("二等座", train.second_seat),
                        "4": ("软卧", train.soft_sleeper),
                        "3": ("硬卧", train.hard_sleeper),
                        "1": ("硬座", train.hard_seat),
                    }
                    
                    if seat_type not in seat_map:
                        continue
                        
                    seat_name, seat_count = seat_map[seat_type]
                    seat_status_list.append(f"{seat_name}:{seat_count}")
                    
                    # 检查是否有票可买（不仅是显示不做任务）
                    can_buy = False
                    if seat_count not in ("--", "无", "*", ""):
                         try:
                             if seat_count == "有" or int(seat_count) > 0:
                                 can_buy = True
                         except ValueError:
                             pass
                    
                    if can_buy:
                        has_ticket_for_train = True
                        if not train.secret_str:
                            continue

                        # 尝试下单
                        if task.auto_submit:
                            await self._add_log(
                                db, task.id, "info",
                                f"发现余票: {train.train_code} {seat_name}({seat_count}), 尝试下单..."
                            )
                            await db.commit()
                            
                            order_service = OrderService(cookies)
                            
                            try:
                                # 解析任务中保存的乘车人信息（用于匹配）
                                passengers_data = json.loads(task.passengers)
                                target_passengers = {
                                    (p["passenger_name"], p["passenger_id_no"]): p
                                    for p in passengers_data
                                }
                                
                                # 从 12306 获取最新的乘车人列表（包含 all_enc_str）
                                success, api_passengers, error = await order_service.query_passengers()
                                if not success or not api_passengers:
                                    await self._add_log(
                                        db, task.id, "warning",
                                        f"获取乘车人失败: {error or '无法获取乘车人列表'}"
                                    )
                                    await db.commit()
                                    continue
                                
                                # 匹配乘车人：根据姓名和身份证号匹配
                                matched_passengers = []
                                for api_passenger in api_passengers:
                                    key = (api_passenger.passenger_name, api_passenger.passenger_id_no)
                                    if key in target_passengers:
                                        # 获取任务中设置的乘客类型作为购票类型
                                        target_p = target_passengers[key]
                                        # 关键修改：允许用户指定购票类型（如学生买成人票）
                                        if "passenger_type" in target_p:
                                            api_passenger.ticket_type = target_p["passenger_type"]
                                            
                                        # 使用 API 返回的乘客信息（包含最新的 all_enc_str）
                                        matched_passengers.append(api_passenger)
                                
                                if not matched_passengers:
                                    await self._add_log(
                                        db, task.id, "warning",
                                        f"未找到匹配的乘车人，请检查乘车人信息是否正确"
                                    )
                                    await db.commit()
                                    continue
                                
                                result = await order_service.buy_ticket(
                                    train_info=train,
                                    secret_str=train.secret_str,
                                    passengers=matched_passengers,
                                    seat_type=seat_type
                                )
                                
                                if result.success:
                                    extra_data = {
                                        "train_code": train.train_code,
                                        "start_time": train.start_time,
                                        "arrive_time": train.arrive_time,
                                        "seat_name": seat_name,
                                        "passenger_names": [p.passenger_name for p in matched_passengers],
                                    }
                                    return True, result.order_id, f"购票成功！", extra_data
                                else:
                                    await self._add_log(
                                        db, task.id, "warning",
                                        f"下单失败: {result.message}"
                                    )
                                    await db.commit()
                            finally:
                                await order_service.close()
                        else:
                            # 仅提示
                            msg = f"发现余票: {train.train_code} {seat_name}({seat_count})"
                            # 如果还没记录过这个车次的发现日志，记一下（防止scan_details里重复强调）
                            # 这里直接返回，外部会记录
                            return False, "", f"{msg}, 等待手动下单", None
                
                # 记录该车次状态
                scan_details.append(f"{train.train_code}[{', '.join(seat_status_list)}]")

            # 如果没有成功下单，或者没有 auto_submit，返回扫描详情
            details_str = " | ".join(scan_details)
            return False, "", f"扫描结束: {details_str}", None
            
        finally:
            await query_service.close()
    
    async def _add_log(
        self,
        db: AsyncSession,
        task_id: int,
        level: str,
        message: str,
        details: str = None
    ):
        """添加任务日志"""
        log = TaskLog(
            task_id=task_id,
            level=level,
            message=message,
            details=details
        )
        db.add(log)


# 全局调度器实例
scheduler = TicketScheduler()


def get_scheduler() -> TicketScheduler:
    """获取调度器实例"""
    return scheduler
