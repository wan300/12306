import asyncio
from datetime import date, timedelta
import os
from unittest import IsolatedAsyncioTestCase, TestCase
from unittest.mock import AsyncMock, patch

from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import ValidationError
from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker

from app.api.flights import router
from app.core.database import Base
from app.models.flight import FlightMonitor
from app.schemas.flight import FlightMonitorCreate, FlightSearch, FlightManualOrder
from app.services.flight_service import FlightError, FlightService, verify_checkout


def config(**overrides):
    return FlightMonitorCreate.model_validate({
        "provider": "qunar", "origin": "BJS", "destination": "SHA",
        "origin_name": "北京", "destination_name": "上海",
        "departure_date": str(date.today() + timedelta(days=14)),
        "flight_number": "MU5138", "passengers": ["测试旅客"],
        "max_total": "600.00", "name": "测试行程", "mode": "auto_order",
        **overrides,
    })


def checkout(**overrides):
    return {
        "flight_number": "MU5138", "departure_date": str(config().departure_date),
        "route_verified": True, "selected_passengers": ["测试旅客"], "adult_only": True,
        "contact_ready": True, "total": "570.00", "currency": "CNY",
        "total_verified": True, "restricted": False, "service_package": False,
        **overrides,
    }


class FlightContractTests(TestCase):
    def test_rejects_past_date_and_same_city_and_duplicate_passengers(self):
        for value in [
            {"departure_date": str(date.today() - timedelta(days=2))},
            {"destination": "BJS"}, {"passengers": ["测试旅客", "测试旅客"]},
            {"interval_seconds": 5}, {"max_total": "NaN"},
            {"passengers": ["110101200001011234"]},
        ]:
            with self.subTest(value=value), self.assertRaises(ValidationError):
                config(**value)

    def test_total_budget_includes_all_passengers_and_taxes(self):
        self.assertEqual(str(verify_checkout(config(), checkout())), "570.00")
        with self.assertRaises(FlightError) as raised:
            verify_checkout(config(max_total="500"), checkout())
        self.assertEqual(raised.exception.code, "OVER_BUDGET")
        with self.assertRaises(FlightError):
            verify_checkout(config(passengers=["测试旅客", "第二旅客"]), checkout())

    def test_cannot_submit_unknown_price_restricted_extra_or_wrong_trip(self):
        cases = [
            {"total": "NaN"}, {"total_verified": False}, {"service_package": True},
            {"restricted": True}, {"route_verified": False}, {"flight_number": "MU5186"},
            {"departure_date": "2099-11-15"}, {"selected_passengers": []},
            {"contact_ready": False}, {"currency": "USD"}, {"adult_only": False},
        ]
        for value in cases:
            with self.subTest(value=value), self.assertRaises(FlightError):
                verify_checkout(config(), checkout(**value))

    def test_desktop_routes_require_per_launch_secret(self):
        app = FastAPI()
        app.include_router(router)
        client = TestClient(app)
        with patch.dict(os.environ, {"DESKTOP_FLIGHT_TOKEN": "test-desktop-token"}):
            self.assertEqual(client.get("/flights/monitors").status_code, 401)
            self.assertEqual(client.get("/flights/monitors", headers={"X-Flight-Token": "wrong"}).status_code, 401)
        with patch.dict(os.environ, {"DESKTOP_FLIGHT_TOKEN": ""}):
            self.assertEqual(client.get("/flights/monitors").status_code, 503)


class FlightMonitorTests(IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.engine = create_async_engine("sqlite+aiosqlite:///:memory:")
        async with self.engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
        self.sessions = async_sessionmaker(self.engine, expire_on_commit=False)
        self.bridge = AsyncMock()
        self.bridge.command.side_effect = self.answer
        self.service = FlightService(self.sessions, self.bridge)

    async def asyncTearDown(self):
        await self.service.shutdown()
        await self.engine.dispose()

    async def answer(self, action, payload):
        if action == "search":
            return {"flights": [{"flight_number": "MU5138", "base_price": "430.00"}]}
        if action == "prepare":
            return checkout()
        return {"status": "pending_payment", "order_id": "123456789"}

    async def new(self, **overrides):
        row = await self.service.create(config(**overrides))
        await self.service.control(row["id"], "start")
        return row["id"]

    async def row(self, monitor_id):
        return next(r for r in await self.service.list() if r["id"] == monitor_id)

    async def test_creates_one_order_and_stops_before_payment(self):
        monitor_id = await self.new()
        await self.service.run_once(monitor_id)
        await self.service.run_once(monitor_id)
        result = await self.row(monitor_id)
        self.assertEqual(result["status"], "pending_payment")
        self.assertEqual(result["order_id"], "123456789")
        self.assertEqual([c.args[0] for c in self.bridge.command.await_args_list], ["search", "prepare", "submit"])
        with self.assertRaises(FlightError):
            await self.service.control(monitor_id, "start")

    async def test_preparation_mode_never_submits(self):
        monitor_id = await self.new(mode="prepare")
        await self.service.run_once(monitor_id)
        self.assertEqual((await self.row(monitor_id))["status"], "ready")
        self.assertNotIn("submit", [c.args[0] for c in self.bridge.command.await_args_list])

    async def test_manual_order_rechecks_displayed_total_and_submits_once(self):
        booking = FlightManualOrder(**config().model_dump(exclude={"name", "mode", "interval_seconds"}), verified_total="570")
        result = await self.service.manual_order(booking)
        self.assertEqual(result["status"], "pending_payment")
        self.assertEqual([c.args[0] for c in self.bridge.command.await_args_list], ["prepare", "submit"])
        duplicate = await self.service.manual_order(booking)
        self.assertEqual(duplicate["error_code"], "DUPLICATE_ORDER_ATTEMPT")
        self.assertEqual(sum(c.args[0] == "submit" for c in self.bridge.command.await_args_list), 1)

    async def test_changed_manual_quote_does_not_submit_or_schedule_a_later_attempt(self):
        booking = FlightManualOrder(**config().model_dump(exclude={"name", "mode", "interval_seconds"}), verified_total="560")
        result = await self.service.manual_order(booking)
        self.assertEqual(result["status"], "paused")
        self.assertEqual(result["error_code"], "CHECKOUT_CHANGED")
        self.assertIsNone(result["next_check_at"])
        self.assertEqual([c.args[0] for c in self.bridge.command.await_args_list], ["prepare"])

    async def test_indicative_price_does_not_authorize_order(self):
        monitor_id = await self.new(max_total="450")
        await self.service.run_once(monitor_id)
        row = await self.row(monitor_id)
        self.assertEqual(row["status"], "running")
        self.assertEqual(row["error_code"], "OVER_BUDGET")
        self.assertEqual(row["last_result"]["total"], "570.00")
        self.assertNotIn("submit", [c.args[0] for c in self.bridge.command.await_args_list])

    async def test_submit_timeout_is_unknown_and_never_retried(self):
        async def answer(action, payload):
            if action == "submit":
                raise TimeoutError("secret-cookie-and-identity-must-not-leak")
            return await self.answer(action, payload)
        self.bridge.command.side_effect = answer
        monitor_id = await self.new()
        await self.service.run_once(monitor_id)
        await self.service.run_once(monitor_id)
        row = await self.row(monitor_id)
        self.assertEqual(row["status"], "unknown")
        self.assertNotIn("secret-cookie", str(row))
        self.assertEqual(len(self.bridge.command.await_args_list), 3)

    async def test_cross_provider_duplicate_trip_is_reserved_durably(self):
        first = await self.new()
        await self.service.run_once(first)
        second = await self.new(provider="ctrip")
        await self.service.run_once(second)
        self.assertEqual((await self.row(second))["error_code"], "DUPLICATE_ORDER_ATTEMPT")
        self.assertEqual(sum(c.args[0] == "submit" for c in self.bridge.command.await_args_list), 1)

    async def test_overlapping_checks_execute_once(self):
        gate = asyncio.Event()
        entered = asyncio.Event()
        async def answer(action, payload):
            if action == "search":
                entered.set()
                await gate.wait()
            return await self.answer(action, payload)
        self.bridge.command.side_effect = answer
        monitor_id = await self.new()
        running = asyncio.create_task(self.service.run_once(monitor_id))
        await entered.wait()
        await self.service.run_once(monitor_id)
        gate.set()
        await running
        self.assertEqual(sum(c.args[0] == "submit" for c in self.bridge.command.await_args_list), 1)

    async def test_pause_during_search_prevents_submission(self):
        async def answer(action, payload):
            if action == "search":
                await self.service.control(monitor_id, "pause")
            return await self.answer(action, payload)
        self.bridge.command.side_effect = answer
        monitor_id = await self.new()
        await self.service.run_once(monitor_id)
        self.assertEqual((await self.row(monitor_id))["status"], "paused")
        self.assertNotIn("submit", [c.args[0] for c in self.bridge.command.await_args_list])

    async def test_cannot_restart_a_check_in_progress(self):
        async def answer(action, payload):
            if action == "search":
                with self.assertRaises(FlightError) as error:
                    await self.service.control(monitor_id, "start")
                self.assertEqual(error.exception.code, "MONITOR_BUSY")
            return await self.answer(action, payload)
        self.bridge.command.side_effect = answer
        monitor_id = await self.new(mode="prepare")
        await self.service.run_once(monitor_id)
        self.assertEqual((await self.row(monitor_id))["status"], "ready")

    async def test_starting_an_expired_task_returns_a_stable_error(self):
        monitor_id = await self.new()
        async with self.sessions() as db:
            row = await db.get(FlightMonitor, monitor_id)
            row.config = config().model_dump_json().replace(str(config().departure_date), "2000-01-01")
            row.status = "paused"
            await db.commit()
        with self.assertRaises(FlightError) as error:
            await self.service.control(monitor_id, "start")
        self.assertEqual(error.exception.code, "INVALID_MONITOR")

    async def test_restart_preserves_unknown_order_and_does_not_resume_inflight_check(self):
        monitor_id = await self.new()
        async with self.sessions() as db:
            row = await db.get(FlightMonitor, monitor_id)
            row.status = "submitting"
            await db.commit()
        restarted = FlightService(self.sessions, self.bridge)
        await restarted.recover()
        self.assertEqual((await self.row(monitor_id))["status"], "unknown")
        await restarted.run_once(monitor_id)
        self.bridge.command.assert_not_awaited()

    async def test_login_failure_pauses_and_keeps_structured_code(self):
        self.bridge.command.side_effect = FlightError("LOGIN_REQUIRED", "请重新登录")
        monitor_id = await self.new()
        await self.service.run_once(monitor_id)
        row = await self.row(monitor_id)
        self.assertEqual((row["status"], row["error_code"]), ("paused", "LOGIN_REQUIRED"))
