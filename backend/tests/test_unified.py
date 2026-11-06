import json
import os
import httpx
from fastapi import FastAPI
from datetime import date, timedelta
from unittest import IsolatedAsyncioTestCase
from unittest.mock import Mock, AsyncMock, patch
from sqlalchemy import select, text
from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker
from app.core.database import Base
from app.models.config import SystemConfig
from app.models.flight import FlightMonitor
from app.models.notification import NotificationEvent
from app.services.notification_service import NotificationService, channel_config, normalize_config
from app.services.flight_service import FlightService, FlightError
from app.schemas.flight import FlightMonitorCreate


def flight(provider="ctrip", **values):
    return FlightMonitorCreate(
        provider=provider, origin="BJS", destination="SHA", origin_name="北京", destination_name="上海",
        departure_date=date.today() + timedelta(days=5), flight_number="MF8561",
        passengers=["测试旅客"], max_total="800", name="北京上海", mode="prepare", **values,
    )


class UnifiedTests(IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.engine = create_async_engine("sqlite+aiosqlite:///:memory:")
        async with self.engine.begin() as connection:
            await connection.run_sync(Base.metadata.create_all)
        self.sessions = async_sessionmaker(self.engine, expire_on_commit=False)
        self.sender = Mock()
        self.notifier = NotificationService(self.sessions, self.sender)
        self.bridge = AsyncMock()
        self.flights = FlightService(self.sessions, self.bridge)
        async with self.sessions() as db:
            db.add(SystemConfig(key="notification_settings", value=json.dumps({
                "ENABLE_WEBHOOK": True, "WEBHOOK_URL": "https://fixture.invalid",
                "ENABLE_DESKTOP": False,
            })))
            await db.commit()

    async def asyncTearDown(self):
        await self.notifier.shutdown()
        await self.engine.dispose()

    async def event(self, event="budget_met", kind="flight"):
        async with self.sessions() as db:
            row = FlightMonitor(name="北京上海", config=flight().model_dump_json(), status="ready")
            db.add(row)
            await db.flush()
            await self.notifier.queue(db, kind, row, event, "预算达标", "含税价格760元")
            await self.notifier.queue(db, kind, row, event, "预算达标", "含税价格760元")
            await db.commit()
            return row.id

    async def test_event_is_durable_deduplicated_and_not_replayed_after_restart(self):
        await self.event()
        await self.notifier.dispatch_once()
        restarted = NotificationService(self.sessions, self.sender)
        await restarted.dispatch_once()
        self.assertEqual(self.sender.call_count, 1)
        async with self.sessions() as db:
            rows = (await db.scalars(select(NotificationEvent))).all()
            self.assertEqual(len(rows), 1)
            self.assertEqual(rows[0].status, "sent")
        self.assertNotIn("ticket_events", self.sender.call_args.kwargs)
        self.assertNotIn("ENABLE_DESKTOP", self.sender.call_args.kwargs)

    async def test_delivery_failure_is_recorded_and_does_not_change_task_result(self):
        task_id = await self.event()
        self.sender.side_effect = RuntimeError("secret-token")
        await self.notifier.dispatch_once()
        async with self.sessions() as db:
            event = await db.scalar(select(NotificationEvent))
            task = await db.get(FlightMonitor, task_id)
            self.assertEqual(event.status, "retry")
            self.assertNotIn("secret-token", event.last_error)
            self.assertEqual(task.status, "ready")

    async def test_rail_results_share_the_outbox_without_duplicate_availability_messages(self):
        from app.models.task import Task, TaskStatus
        from app.models.user import User
        from app.tasks import scheduler as module
        scheduler = module.TicketScheduler()
        async with self.sessions() as db:
            user = User(username="fixture-account", session_data='{"cookies":{}}')
            db.add(user)
            await db.flush()
            task = Task(user_id=user.id, name="火车票通知核对", from_station="北京南", to_station="上海虹桥",
                        train_date="2026-11-10", seat_types="O", passengers="[]", auto_submit=False,
                        status=TaskStatus.RUNNING, max_retry_count=-1)
            db.add(task)
            await db.commit()
            task_id = task.id
        scheduler._active_tasks[task_id] = True
        try:
            with patch.object(module, "AsyncSessionLocal", self.sessions), patch.object(
                scheduler, "_query_and_order", new_callable=AsyncMock,
                return_value=(False, "", "发现余票，等待手动预订", {"tickets_available": True}),
            ):
                await scheduler._run_ticket_task(task_id)
                await scheduler._run_ticket_task(task_id)
            async with self.sessions() as db:
                events = (await db.scalars(select(NotificationEvent))).all()
                self.assertEqual(len(events), 1)
                self.assertEqual(events[0].ticket_type, "rail")
                self.assertEqual(events[0].event_type, "tickets_available")
                task = await db.get(Task, task_id)
                self.assertEqual(task.status, TaskStatus.RUNNING)
                self.assertIsNone(task.order_id)
                self.assertIn("发现余票", task.result_message)
            await self.notifier.dispatch_once()
            self.assertEqual(self.sender.call_count, 1)
        finally:
            scheduler._active_tasks.pop(task_id, None)

    async def test_subscription_filter_and_channel_metadata_are_separate(self):
        async with self.sessions() as db:
            config = await db.scalar(select(SystemConfig))
            config.value = json.dumps({"ENABLE_WEBHOOK": True, "ENABLE_DESKTOP": False,
                                       "ticket_events": {"flight": [], "rail": ["tickets_available"]}})
            await db.commit()
        await self.event()
        await self.event("tickets_available", "rail")
        await self.notifier.dispatch_once()
        self.assertEqual(self.sender.call_count, 1)
        legacy = normalize_config({"WEBHOOK_URL": "fixture"})
        self.assertIn("budget_met", legacy["ticket_events"]["flight"])
        self.assertNotIn("test_type", channel_config({**legacy, "test_type": "flight"}))

    async def test_retry_does_not_resend_channels_that_already_succeeded(self):
        async with self.sessions() as db:
            config = await db.scalar(select(SystemConfig))
            config.value = json.dumps({"ENABLE_WEBHOOK": True, "ENABLE_SMTP": True, "ENABLE_DESKTOP": False})
            await db.commit()
        def deliver(*args, **kwargs):
            if kwargs.get("ENABLE_SMTP"):
                raise RuntimeError("mail failure")
        self.sender.side_effect = deliver
        await self.event()
        await self.notifier.dispatch_once()
        async with self.sessions() as db:
            event = await db.scalar(select(NotificationEvent))
            event.next_attempt = __import__("datetime").datetime.utcnow()
            await db.commit()
        await self.notifier.dispatch_once()
        calls = self.sender.call_args_list
        self.assertEqual(sum(bool(call.kwargs.get("ENABLE_WEBHOOK")) for call in calls), 1)
        self.assertEqual(sum(bool(call.kwargs.get("ENABLE_SMTP")) for call in calls), 2)

    async def test_edit_paused_flight_clears_quote_and_preserves_identity(self):
        created = await self.flights.create(flight())
        async with self.sessions() as db:
            row = await db.get(FlightMonitor, created["id"])
            row.last_result = '{"total":"760.00"}'
            await db.commit()
        changed = flight()
        changed.max_total = 900
        result = await self.flights.edit(created["id"], changed)
        self.assertEqual(result["id"], created["id"])
        self.assertIsNone(result["last_result"])
        await self.flights.control(created["id"], "start")
        with self.assertRaises(FlightError):
            await self.flights.edit(created["id"], changed)

    async def test_account_replacement_pauses_only_the_selected_platform(self):
        ctrip = await self.flights.create(flight())
        qunar = await self.flights.create(flight("qunar"))
        for task in (ctrip, qunar):
            await self.flights.control(task["id"], "start")
        self.bridge.command.side_effect = [{"status": "confirmed"}, {"status": "authenticated"}]
        result = await self.flights.command("login_status", {"provider": "ctrip"})
        self.assertEqual(result["status"], "authenticated")
        self.assertEqual((await self.flights.get(ctrip["id"]))["status"], "paused")
        self.assertEqual((await self.flights.get(qunar["id"]))["status"], "running")

    async def test_restart_retains_notification_run_identifier(self):
        task = await self.flights.create(flight())
        await self.flights.control(task["id"], "start")
        async with self.sessions() as db:
            first = (await db.get(FlightMonitor, task["id"])).notification_run_id
        await self.flights.recover()
        async with self.sessions() as db:
            self.assertEqual(first, (await db.get(FlightMonitor, task["id"])).notification_run_id)

    async def test_desktop_notifications_are_independent_of_railway_login(self):
        from app.api.config import router, notification_service
        from app.core.database import get_db
        app = FastAPI()
        app.include_router(router)
        async def database():
            async with self.sessions() as db:
                yield db
        app.dependency_overrides[get_db] = database
        with patch.dict(os.environ, {"DESKTOP_FLIGHT_TOKEN": "fixture-local-token"}):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://127.0.0.1") as client:
                self.assertEqual((await client.get("/config/notification")).status_code, 401)
                headers = {"X-Flight-Token": "fixture-local-token"}
                response = await client.get("/config/notification", headers=headers)
                self.assertEqual(response.status_code, 200)
                config = response.json()
                config["ticket_events"]["flight"] = ["order_created"]
                self.assertEqual((await client.post("/config/notification", headers=headers, json=config)).status_code, 200)
                self.assertEqual((await client.get("/config/notification", headers=headers)).json()["ticket_events"]["flight"], ["order_created"])
                with patch.object(notification_service, "deliver", new_callable=AsyncMock) as deliver:
                    response = await client.post("/config/notification/test", headers=headers, json={**config, "test_type": "flight"})
                    self.assertEqual(response.status_code, 200)
                    self.assertIn("机票", deliver.call_args.args[0])


class MigrationTests(IsolatedAsyncioTestCase):
    async def test_ipv6_proxy_exclusions_keep_existing_proxy_settings(self):
        from app.core.http_environment import normalize_proxy_exclusions
        with patch.dict(os.environ, {"NO_PROXY": "localhost,::1,[::1],example.test", "HTTPS_PROXY": "http://127.0.0.1:7890"}):
            normalize_proxy_exclusions()
            self.assertEqual(os.environ["NO_PROXY"], "localhost,::1,example.test")
            self.assertEqual(os.environ["HTTPS_PROXY"], "http://127.0.0.1:7890")
            async with httpx.AsyncClient():
                pass

    async def test_legacy_tables_are_extended_without_replacing_records(self):
        from app.core import database
        engine = create_async_engine("sqlite+aiosqlite:///:memory:")
        try:
            async with engine.begin() as connection:
                for name in ("tasks", "flight_monitors"):
                    await connection.execute(text(f"CREATE TABLE {name} (id INTEGER PRIMARY KEY, name TEXT)"))
                    await connection.execute(text(f"INSERT INTO {name} (id,name) VALUES (1,'preserved')"))
            with patch.object(database, "engine", engine):
                await database.init_db()
                async with engine.connect() as connection:
                    first = (await connection.execute(text("SELECT notification_run_id FROM tasks WHERE id=1"))).scalar()
                await database.init_db()
                async with engine.connect() as connection:
                    row = (await connection.execute(text("SELECT name,notification_run_id FROM tasks WHERE id=1"))).one()
                    self.assertEqual(row[0], "preserved")
                    self.assertEqual(row[1], first)
        finally:
            await engine.dispose()
