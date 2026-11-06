import asyncio
import json
import os
import httpx
from datetime import datetime, timedelta
from sqlalchemy import select, update
from sqlalchemy.dialects.sqlite import insert
from ..core.database import AsyncSessionLocal
from ..models.config import SystemConfig
from ..models.notification import NotificationEvent
from ..utils import notify


EVENTS = {
    "rail": ["tickets_available", "order_created", "login_required", "task_failed", "order_unknown"],
    "flight": ["budget_met", "order_created", "login_required", "task_failed", "order_unknown"],
}


def normalize_config(config):
    result = dict(config or {})
    result.setdefault("HITOKOTO", "false")
    result.setdefault("ENABLE_DESKTOP", bool(os.environ.get("DESKTOP_FLIGHT_TOKEN")))
    selected = result.get("ticket_events", {})
    if not isinstance(selected, dict):
        selected = {}
    result["ticket_events"] = {
        kind: [event for event in (selected[kind] if isinstance(selected.get(kind), list) else events) if event in events]
        for kind, events in EVENTS.items()
    }
    return result


def channel_config(config):
    return {key: value for key, value in config.items() if key not in {"ticket_events", "test_type", "ENABLE_DESKTOP"}}


class NotificationService:
    def __init__(self, sessions=AsyncSessionLocal, sender=None):
        self.sessions = sessions
        self.sender = sender or notify.send
        self.runner = None

    async def config(self, db):
        row = await db.scalar(select(SystemConfig).where(SystemConfig.key == "notification_settings"))
        try:
            return normalize_config(json.loads(row.value) if row and row.value else {})
        except (ValueError, TypeError):
            return normalize_config({})

    async def queue(self, db, kind, task, event, title, content):
        config = await self.config(db)
        if event not in config["ticket_events"].get(kind, []):
            return
        enabled = any(key.startswith("ENABLE_") and str(value).lower() == "true" for key, value in config.items())
        if not enabled:
            return
        key = f"{kind}:{task.id}:{task.notification_run_id}:{event}"
        await db.execute(insert(NotificationEvent).values(
            key=key, ticket_type=kind, task_id=task.id, event_type=event,
            title=title, content=content, status="pending",
        ).on_conflict_do_nothing(index_elements=["key"]))

    async def dispatch_once(self):
        async with self.sessions() as db:
            rows = (await db.scalars(select(NotificationEvent).where(
                NotificationEvent.status.in_(["pending", "retry"]),
                NotificationEvent.next_attempt <= datetime.utcnow(),
            ).order_by(NotificationEvent.created_at).limit(20))).all()
            for row in rows:
                config = await self.config(db)
                if row.event_type not in config["ticket_events"].get(row.ticket_type, []):
                    row.status = "skipped"
                    await db.commit()
                    continue
                row.status = "sending"
                row.attempts += 1
                await db.commit()
                try:
                    delivered = set(json.loads(row.delivered_channels or "[]"))
                    enabled = [key for key, value in config.items() if key.startswith("ENABLE_") and str(value).lower() == "true"]
                    for channel in enabled:
                        if channel in delivered:
                            continue
                        selected = {**config, **{key: key == channel for key in config if key.startswith("ENABLE_")}}
                        await self.deliver(row.title, row.content, selected)
                        delivered.add(channel)
                        row.delivered_channels = json.dumps(sorted(delivered))
                        await db.commit()
                    row.status, row.last_error = "sent", None
                except Exception as error:
                    row.status = "retry" if row.attempts < 3 else "failed"
                    row.last_error = type(error).__name__ + ": 通知发送失败"
                    row.next_attempt = datetime.utcnow() + timedelta(seconds=60)
                await db.commit()

    async def deliver(self, title, content, config):
        if str(config.get("ENABLE_DESKTOP", False)).lower() == "true":
            url = os.environ.get("FLIGHT_BROWSER_URL", "")
            if url.startswith("http://127.0.0.1:"):
                async with httpx.AsyncClient(timeout=10, trust_env=False) as client:
                    response = await client.post(url + "/command",
                        headers={"X-Flight-Token": os.environ.get("DESKTOP_FLIGHT_TOKEN", "")},
                        json={"action": "desktop_notify", "payload": {"title": title, "content": content}})
                    response.raise_for_status()
        channels = channel_config(config)
        if any(key.startswith("ENABLE_") and str(value).lower() == "true" for key, value in channels.items()):
            await asyncio.to_thread(self.sender, title, content, ignore_default_config=True, **channels)

    async def _loop(self):
        while True:
            try:
                await self.dispatch_once()
            except Exception:
                # Delivery failures must not stop ticket processing.
                pass
            await asyncio.sleep(2)

    async def start(self):
        async with self.sessions() as db:
            await db.execute(update(NotificationEvent).where(NotificationEvent.status == "sending").values(status="retry"))
            await db.commit()
        self.runner = asyncio.create_task(self._loop())

    async def shutdown(self):
        if self.runner:
            self.runner.cancel()
            try:
                await self.runner
            except asyncio.CancelledError:
                pass


notification_service = NotificationService()
