"""Flight monitor records never contain provider cookies or identity numbers."""

from datetime import datetime
from typing import Optional
from uuid import uuid4
from sqlalchemy import DateTime, String, Text
from sqlalchemy.orm import Mapped, mapped_column
from ..core.database import Base


class FlightMonitor(Base):
    __tablename__ = "flight_monitors"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(100))
    notification_run_id: Mapped[Optional[str]] = mapped_column(String(36), nullable=True, default=lambda: str(uuid4()))
    config: Mapped[str] = mapped_column(Text)
    status: Mapped[str] = mapped_column(String(30), default="paused", index=True)
    last_result: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    message: Mapped[str] = mapped_column(String(300), default="等待启动")
    error_code: Mapped[Optional[str]] = mapped_column(String(60), nullable=True)
    order_id: Mapped[Optional[str]] = mapped_column(String(100), nullable=True)
    next_check_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)


class FlightAttempt(Base):
    """A durable, cross-provider single-attempt key for one trip/passenger set."""
    __tablename__ = "flight_attempts"
    booking_key: Mapped[str] = mapped_column(String(64), primary_key=True)
    monitor_id: Mapped[int] = mapped_column()
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)
