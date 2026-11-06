"""Local desktop capability; independent from the 12306 account."""

import hmac
import os
from typing import Literal
from fastapi import APIRouter, Depends, Header, HTTPException, Request
from fastapi.responses import JSONResponse
from ..schemas.flight import FlightSearch, FlightOffers, FlightBooking, FlightManualOrder, FlightMonitorCreate, Provider
from ..services.flight_service import flight_service, FlightError


async def desktop_access(request: Request, x_flight_token: str = Header(default="")):
    expected = os.environ.get("DESKTOP_FLIGHT_TOKEN", "")
    if not expected:
        raise HTTPException(503, detail={"error_code": "DESKTOP_REQUIRED", "message": "请从桌面应用打开机票功能"})
    if request.client and request.client.host not in {"127.0.0.1", "::1", "testclient"}:
        raise HTTPException(403, detail={"error_code": "LOCAL_ONLY", "message": "机票功能仅供本机使用"})
    if not hmac.compare_digest(expected, x_flight_token):
        raise HTTPException(401, detail={"error_code": "DESKTOP_AUTH_REQUIRED", "message": "桌面会话已失效，请重新打开应用"})


router = APIRouter(prefix="/flights", tags=["个人机票"], dependencies=[Depends(desktop_access)])


async def execute(action, payload):
    try:
        return {"success": True, "data": await flight_service.command(action, payload)}
    except FlightError as exc:
        return JSONResponse(status_code=exc.status, content={"success": False, "error_code": exc.code, "message": exc.message})


@router.get("/providers")
async def providers():
    return await execute("status", {})


@router.post("/providers/{provider}/open")
async def open_provider(provider: Provider):
    return await execute("open", {"provider": provider})


@router.post("/providers/{provider}/login/qr")
async def login_qr(provider: Provider):
    return await execute("login_qr", {"provider": provider})


@router.get("/providers/{provider}/login/status")
async def login_status(provider: Provider):
    return await execute("login_status", {"provider": provider})


@router.get("/providers/{provider}/session")
async def session_status(provider: Provider):
    return await execute("session_status", {"provider": provider})


@router.post("/search")
async def search(query: FlightSearch):
    return await execute("search", query.model_dump(mode="json"))


@router.post("/prepare")
async def prepare(query: FlightBooking):
    return await execute("prepare", query.model_dump(mode="json"))


@router.post("/offers")
async def offers(query: FlightOffers):
    return await execute("offers", query.model_dump(mode="json"))


@router.get("/monitors")
async def monitors():
    return {"success": True, "data": await flight_service.list()}


@router.post("/orders", status_code=201)
async def manual_order(query: FlightManualOrder):
    return {"success": True, "data": await flight_service.manual_order(query)}


@router.post("/monitors", status_code=201)
async def create_monitor(config: FlightMonitorCreate):
    return {"success": True, "data": await flight_service.create(config)}


@router.get("/monitors/{monitor_id}")
async def get_monitor(monitor_id: int):
    try:
        return {"success": True, "data": await flight_service.get(monitor_id)}
    except FlightError as exc:
        return JSONResponse(status_code=exc.status, content={"success": False, "error_code": exc.code, "message": exc.message})


@router.patch("/monitors/{monitor_id}")
async def edit_monitor(monitor_id: int, config: FlightMonitorCreate):
    try:
        return {"success": True, "data": await flight_service.edit(monitor_id, config)}
    except FlightError as exc:
        return JSONResponse(status_code=exc.status, content={"success": False, "error_code": exc.code, "message": exc.message})


@router.post("/monitors/{monitor_id}/{action}")
async def control_monitor(monitor_id: int, action: Literal["start", "pause"]):
    try:
        return {"success": True, "data": await flight_service.control(monitor_id, action)}
    except FlightError as exc:
        return JSONResponse(status_code=exc.status, content={"success": False, "error_code": exc.code, "message": exc.message})


@router.delete("/monitors/{monitor_id}")
async def delete_monitor(monitor_id: int):
    try:
        return {"success": True, "data": await flight_service.control(monitor_id, "delete")}
    except FlightError as exc:
        return JSONResponse(status_code=exc.status, content={"success": False, "error_code": exc.code, "message": exc.message})
