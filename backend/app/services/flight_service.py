"""Low-frequency desktop flight monitoring and durable submission decisions."""

import asyncio
from collections import defaultdict
from datetime import datetime, timedelta
from decimal import Decimal, InvalidOperation
import json
import os
import hashlib
from uuid import uuid4
from .notification_service import NotificationService

import httpx
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from ..core.database import AsyncSessionLocal
from ..models.flight import FlightMonitor, FlightAttempt
from ..schemas.flight import FlightMonitorCreate


class FlightError(Exception):
    def __init__(self, code: str, message: str, status: int = 409):
        super().__init__(message)
        self.code, self.message, self.status = code, message, status


class DesktopFlightBridge:
    async def command(self, action: str, payload: dict):
        url = os.environ.get("FLIGHT_BROWSER_URL", "")
        token = os.environ.get("DESKTOP_FLIGHT_TOKEN", "")
        if not url.startswith("http://127.0.0.1:") or not token:
            raise FlightError("DESKTOP_REQUIRED", "请从桌面应用打开机票功能", 503)
        try:
            async with httpx.AsyncClient(timeout=150, trust_env=False) as client:
                response = await client.post(
                    url + "/command", json={"action": action, "payload": payload},
                    headers={"X-Flight-Token": token},
                )
            data = response.json()
            if not data.get("success"):
                code = data.get("error_code", "PROVIDER_UNAVAILABLE")
                # Only bridge-owned messages are accepted; never proxy vendor exceptions.
                raise FlightError(code, data.get("message", "平台页面暂时不可用"))
            return data.get("data", {})
        except FlightError:
            raise
        except (httpx.HTTPError, ValueError):
            raise FlightError("BRIDGE_UNAVAILABLE", "桌面浏览器连接中断，请打开平台窗口检查状态", 503) from None


def money(value):
    try:
        amount = Decimal(str(value))
        if not amount.is_finite() or amount <= 0:
            raise InvalidOperation
        return amount.quantize(Decimal("0.01"))
    except (InvalidOperation, ValueError, TypeError):
        raise FlightError("PRICE_UNVERIFIED", "无法确认当前含税总价，已停止下单") from None


def verify_checkout(config: FlightMonitorCreate, result: dict):
    if result.get("flight_number") != config.flight_number:
        raise FlightError("FLIGHT_CHANGED", "当前航班与任务不一致")
    if result.get("departure_date") != str(config.departure_date):
        raise FlightError("DATE_CHANGED", "当前出发日期与任务不一致")
    if not result.get("route_verified"):
        raise FlightError("ROUTE_UNVERIFIED", "未能确认当前行程的起降城市")
    if sorted(result.get("selected_passengers", [])) != sorted(config.passengers):
        raise FlightError("PASSENGERS_UNVERIFIED", "已选乘机人与任务不一致，请在平台窗口确认")
    if not result.get("adult_only"):
        raise FlightError("PASSENGER_TYPE_UNSUPPORTED", "请选择成人乘机人")
    if not result.get("contact_ready"):
        raise FlightError("CONTACT_REQUIRED", "请在平台窗口补全联系人")
    if result.get("restricted"):
        raise FlightError("PRODUCT_RESTRICTED", "当前产品包含适用条件，需手动核对")
    if result.get("service_package") and not config.allow_service_packages:
        raise FlightError("PAID_EXTRAS", "当前产品包含付费附加服务，请选择普通产品或调整任务")
    if result.get("currency") != "CNY" or not result.get("total_verified"):
        raise FlightError("PRICE_UNVERIFIED", "未能明确识别人民币含税总价")
    total = money(result.get("total"))
    if total > config.max_total:
        raise FlightError("OVER_BUDGET", "最新含税总价超过任务上限，继续监控")
    return total


def public_monitor(row):
    return {
        "id": row.id, "name": row.name, "config": json.loads(row.config),
        "status": row.status, "message": row.message, "error_code": row.error_code,
        "order_id": row.order_id,
        "last_result": json.loads(row.last_result) if row.last_result else None,
        "next_check_at": row.next_check_at.isoformat() + "Z" if row.next_check_at else None,
        "updated_at": row.updated_at.isoformat() + "Z",
    }


class FlightService:
    def __init__(self, sessions=AsyncSessionLocal, bridge=None):
        self.sessions = sessions
        self.bridge = bridge or DesktopFlightBridge()
        self.locks = defaultdict(asyncio.Lock)
        self.provider_locks = defaultdict(asyncio.Lock)
        self.cancelled = set()
        self.runner = None
        self.notifications = NotificationService(sessions)

    async def command(self, action, payload):
        provider = payload.get("provider", "ctrip")
        async with self.provider_locks[provider]:
            result = await self.bridge.command(action, payload)
            if action == "login_status" and result.get("status") == "confirmed":
                async with self.sessions() as db:
                    rows = (await db.scalars(select(FlightMonitor).where(FlightMonitor.status.in_(["running", "checking", "preparing", "ready"])))).all()
                    for row in rows:
                        if json.loads(row.config).get("provider") == provider:
                            self.cancelled.add(row.id)
                            row.status, row.message, row.next_check_at = "paused", "账号已更新，请核对后启动任务", None
                    await db.commit()
                return await self.bridge.command("login_commit", payload)
            return result

    async def create(self, config):
        async with self.sessions() as db:
            row = FlightMonitor(name=config.name, config=config.model_dump_json(), status="paused")
            db.add(row)
            await db.commit()
            await db.refresh(row)
            return public_monitor(row)

    async def list(self):
        async with self.sessions() as db:
            rows = (await db.execute(select(FlightMonitor).order_by(FlightMonitor.id.desc()))).scalars().all()
            return [public_monitor(row) for row in rows]

    async def get(self, monitor_id):
        async with self.sessions() as db:
            row = await db.get(FlightMonitor, monitor_id)
            if not row:
                raise FlightError("MONITOR_NOT_FOUND", "任务不存在", 404)
            return public_monitor(row)

    async def edit(self, monitor_id, config):
        async with self.sessions() as db:
            row = await db.get(FlightMonitor, monitor_id)
            if not row:
                raise FlightError("MONITOR_NOT_FOUND", "任务不存在", 404)
            if row.status not in {"paused", "ready"} or row.order_id or self.locks[monitor_id].locked():
                raise FlightError("MONITOR_BUSY", "请先暂停任务并核对订单状态")
            row.name, row.config = config.name, config.model_dump_json()
            row.status, row.message = "paused", "等待启动"
            row.last_result = row.error_code = row.next_check_at = None
            await db.commit()
            return public_monitor(row)

    async def manual_order(self, booking):
        config = FlightMonitorCreate(
            **booking.model_dump(exclude={"verified_total"}),
            name=f"{booking.origin_name}到{booking.destination_name} {booking.flight_number}",
            mode="auto_order",
        )
        created = await self.create(config)
        await self.run_once(created["id"], expected_total=booking.verified_total)
        async with self.sessions() as db:
            return public_monitor(await db.get(FlightMonitor, created["id"]))

    async def control(self, monitor_id, action):
        if action == "pause":
            self.cancelled.add(monitor_id)
        async with self.sessions() as db:
            row = await db.get(FlightMonitor, monitor_id)
            if not row:
                raise FlightError("MONITOR_NOT_FOUND", "任务不存在", 404)
            if row.status in {"submitting", "unknown", "pending_payment"}:
                raise FlightError("ORDER_REVIEW_REQUIRED", "请先在平台核对已有订单；该任务不能重新下单")
            if action == "start":
                if self.locks[monitor_id].locked() or row.status in {"checking", "preparing"}:
                    raise FlightError("MONITOR_BUSY", "任务正在检查，请等待本次检查结束")
                try:
                    FlightMonitorCreate.model_validate_json(row.config)
                except ValueError:
                    raise FlightError("INVALID_MONITOR", "任务参数或出发日期已失效，请重新保存任务") from None
                self.cancelled.discard(monitor_id)
                row.status, row.message = "running", "等待检查报价"
                row.next_check_at = datetime.utcnow()
                row.error_code = None
                row.notification_run_id = str(uuid4())
            elif action == "pause":
                row.status, row.message = "paused", "已暂停"
                row.next_check_at = None
            elif action == "delete":
                if self.locks[monitor_id].locked():
                    raise FlightError("MONITOR_BUSY", "任务正在检查，请暂停并等待检查结束后删除")
                self.cancelled.add(monitor_id)
                await db.delete(row)
                await db.commit()
                return {"id": monitor_id}
            await db.commit()
            return public_monitor(row)

    async def recover(self):
        async with self.sessions() as db:
            rows = (await db.execute(select(FlightMonitor))).scalars().all()
            for row in rows:
                if row.status == "submitting":
                    row.status, row.message = "unknown", "上次下单时应用退出，请在平台核对订单，禁止自动重试"
                    row.error_code = "ORDER_UNKNOWN"
                    await self._notify(db, row)
                elif row.status in {"checking", "preparing"}:
                    row.status, row.message = "paused", "上次检查被中断，请检查平台窗口后重新启动"
                row.next_check_at = None if row.status != "running" else datetime.utcnow() + timedelta(seconds=60)
            await db.commit()

    async def run_once(self, monitor_id, expected_total=None):
        lock = self.locks[monitor_id]
        if lock.locked():
            return
        async with lock:
            async with self.sessions() as db:
                row = await db.get(FlightMonitor, monitor_id)
                expected_status = "paused" if expected_total is not None else "running"
                if not row or row.status != expected_status or monitor_id in self.cancelled:
                    return
                try:
                    config = FlightMonitorCreate.model_validate_json(row.config)
                except ValueError:
                    row.status, row.message, row.error_code = "paused", "任务参数或出发日期已失效", "INVALID_MONITOR"
                    await db.commit()
                    return
                row.status = "checking"
                row.next_check_at = datetime.utcnow() + timedelta(seconds=config.interval_seconds)
                await db.commit()
                submission_started = False
                try:
                    async with self.provider_locks[config.provider]:
                        payload = config.model_dump(mode="json")
                        if expected_total is None:
                            result = await self.bridge.command("search", payload)
                            flights = [f for f in result.get("flights", []) if f.get("flight_number") == config.flight_number]
                            if not flights:
                                raise FlightError("FLIGHT_NOT_FOUND", "未找到指定航班，等待下次查询")
                            row.last_result = json.dumps({"flight_number": config.flight_number, "indicative_price": flights[0].get("base_price")}, ensure_ascii=False)
                        if config.mode == "notify":
                            # Untaxed list prices are never labelled as a budget match.
                            row.status, row.message = "running", "航班报价已更新；列表价不含完整税费"
                        else:
                            row.status = "preparing"
                            await db.commit()
                            if monitor_id in self.cancelled:
                                return
                            checkout = await self.bridge.command("prepare", payload)
                            if expected_total is not None and money(checkout.get("total")) != expected_total:
                                raise FlightError("CHECKOUT_CHANGED", "含税总价已变化，请重新核对预订信息")
                            try:
                                total = verify_checkout(config, checkout)
                            except FlightError as exc:
                                if exc.code == "OVER_BUDGET":
                                    row.last_result = json.dumps({"flight_number": config.flight_number, "total": str(money(checkout.get("total"))), "currency": "CNY", "passenger_count": len(config.passengers)}, ensure_ascii=False)
                                raise
                            row.last_result = json.dumps({"flight_number": config.flight_number, "total": str(total), "currency": "CNY", "passenger_count": len(config.passengers)}, ensure_ascii=False)
                            if monitor_id in self.cancelled:
                                return
                            if config.mode == "prepare":
                                row.status = "ready"
                                row.message = "已达到预算，预订信息已核对"
                            else:
                                booking_key = hashlib.sha256(json.dumps([
                                    config.origin, config.destination, str(config.departure_date),
                                    config.flight_number, sorted(config.passengers),
                                ], ensure_ascii=False).encode()).hexdigest()
                                db.add(FlightAttempt(booking_key=booking_key, monitor_id=monitor_id))
                                row.status, row.message = "submitting", "正在提交一次订单，请勿重复操作"
                                await db.commit()  # durable before sending any consequential command
                                submission_started = True
                                result = await self.bridge.command("submit", {**payload, "verified_total": str(total)})
                                order_id = str(result.get("order_id", ""))
                                if result.get("status") != "pending_payment" or not order_id:
                                    raise FlightError("ORDER_UNKNOWN", "提交结果不明确，请在平台核对订单")
                                row.status, row.order_id = "pending_payment", order_id
                                row.message = "已收到订单号，请在平台窗口核对并支付，付款后确认出票"
                    row.error_code = None
                except IntegrityError:
                    await db.rollback()
                    row = await db.get(FlightMonitor, monitor_id)
                    row.status, row.error_code = "paused", "DUPLICATE_ORDER_ATTEMPT"
                    row.message = "此行程和乘机人已有下单尝试，请先在平台核对已有订单"
                except FlightError as exc:
                    row.error_code, row.message = exc.code, exc.message
                    if submission_started:
                        row.status = "unknown"
                        row.message = "提交结果不明确，请在平台核对订单，禁止自动重试"
                    elif expected_total is None and exc.code in {"OVER_BUDGET", "FLIGHT_NOT_FOUND", "RATE_LIMITED"}:
                        row.status = "running"
                    else:
                        row.status = "paused"
                except Exception:
                    row.error_code = "ORDER_UNKNOWN" if submission_started else "FLIGHT_CHECK_FAILED"
                    row.status = "unknown" if submission_started else "paused"
                    row.message = "请在平台核对订单，禁止自动重试" if submission_started else "检查未完成，请打开平台窗口后重试"
                finally:
                    if monitor_id in self.cancelled and not submission_started:
                        row.status, row.message = "paused", "已暂停"
                    if row.status != "running":
                        row.next_check_at = None
                    if not (monitor_id in self.cancelled and not submission_started):
                        await self._notify(db, row)
                    await db.commit()

    async def _notify(self, db, row):
        config = json.loads(row.config)
        event = {"ready": "budget_met", "pending_payment": "order_created", "unknown": "order_unknown"}.get(row.status)
        if row.status == "paused" and row.error_code:
            event = "login_required" if row.error_code in {"LOGIN_REQUIRED", "VERIFICATION_REQUIRED"} else "task_failed"
        if not event:
            return
        provider = "携程" if config["provider"] == "ctrip" else "去哪儿"
        titles = {"budget_met": "已达到含税预算", "order_created": "订单待支付", "order_unknown": "订单结果需核对", "login_required": "登录需要处理", "task_failed": "任务需要处理"}
        content = f"{row.name}\n行程: {config['origin_name']} → {config['destination_name']}\n日期: {config['departure_date']}\n{row.message}"
        if row.last_result:
            result = json.loads(row.last_result)
            if result.get("total"):
                content += f"\n含税总价: ¥{result['total']}"
        if row.order_id:
            content += f"\n订单号: {row.order_id}"
        await self.notifications.queue(db, "flight", row, event, f"机票 · {provider} · {titles[event]}", content)

    async def _loop(self):
        while True:
            async with self.sessions() as db:
                ids = (await db.execute(select(FlightMonitor.id).where(
                    FlightMonitor.status == "running", FlightMonitor.next_check_at <= datetime.utcnow()
                ))).scalars().all()
            for monitor_id in ids:
                await self.run_once(monitor_id)
            await asyncio.sleep(2)

    async def start(self):
        await self.recover()
        self.runner = asyncio.create_task(self._loop())

    async def shutdown(self):
        if self.runner:
            self.runner.cancel()
            try:
                await self.runner
            except asyncio.CancelledError:
                pass


flight_service = FlightService()
