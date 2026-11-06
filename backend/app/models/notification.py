from datetime import datetime
from sqlalchemy import String, Text, DateTime
from sqlalchemy.orm import Mapped, mapped_column
from ..core.database import Base


class NotificationEvent(Base):
    __tablename__ = "notification_events"
    key: Mapped[str] = mapped_column(String(180), primary_key=True)
    ticket_type: Mapped[str] = mapped_column(String(10))
    task_id: Mapped[int]
    event_type: Mapped[str] = mapped_column(String(30))
    title: Mapped[str] = mapped_column(String(200))
    content: Mapped[str] = mapped_column(Text)
    status: Mapped[str] = mapped_column(String(20), default="pending", index=True)
    attempts: Mapped[int] = mapped_column(default=0)
    delivered_channels: Mapped[str] = mapped_column(Text, default="[]")
    last_error: Mapped[str | None] = mapped_column(String(200), nullable=True)
    next_attempt: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)
