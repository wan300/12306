"""Contracts for the local, personal desktop flight feature."""

from datetime import date, datetime, timezone, timedelta
from decimal import Decimal
import re
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


Provider = Literal["ctrip", "qunar"]


class FlightSearch(BaseModel):
    model_config = ConfigDict(extra="forbid")
    provider: Provider = "ctrip"
    origin: str = Field("BJS", pattern=r"^[A-Z]{3}$")
    destination: str = Field("SHA", pattern=r"^[A-Z]{3}$")
    origin_name: str = Field("北京", min_length=1, max_length=30)
    destination_name: str = Field("上海", min_length=1, max_length=30)
    departure_date: date

    @model_validator(mode="after")
    def validate_route(self):
        today = datetime.now(timezone(timedelta(hours=8))).date()
        if self.origin == self.destination:
            raise ValueError("出发地与目的地不能相同")
        if not today <= self.departure_date <= today + timedelta(days=365):
            raise ValueError("出发日期须在今天至未来一年内")
        return self


class FlightOffers(FlightSearch):
    flight_number: str = Field(pattern=r"^[A-Z0-9]{2}\d{3,4}$")


class FlightBooking(FlightOffers):
    offer_key: str | None = Field(default=None, max_length=4000)
    passengers: list[str] = Field(min_length=1, max_length=9)
    max_total: Decimal = Field(gt=0, le=1000000, max_digits=9, decimal_places=2)
    allow_service_packages: bool = False

    @field_validator("passengers")
    @classmethod
    def validate_passengers(cls, values):
        names = [v.strip() for v in values]
        if any(not re.fullmatch(r"[A-Za-z\u3400-\u9fff ./'·-]{1,60}", v) or not re.search(r"[A-Za-z\u3400-\u9fff]", v) for v in names):
            raise ValueError("请输入平台已保存的乘机人姓名，不要填写证件号")
        if len(set(names)) != len(names):
            raise ValueError("乘机人不能重复")
        return names


class FlightManualOrder(FlightBooking):
    verified_total: Decimal = Field(gt=0, le=1000000, max_digits=9, decimal_places=2)


class FlightMonitorCreate(FlightBooking):
    name: str = Field(min_length=1, max_length=100)
    interval_seconds: int = Field(300, ge=60, le=86400)
    mode: Literal["notify", "prepare", "auto_order"] = "prepare"
