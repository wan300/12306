import hmac
import os
from fastapi import Depends, Header, Request
from .auth import get_current_user, security
from .database import get_db


async def workspace_access(request: Request, x_flight_token: str = Header(default=""),
                           credentials=Depends(security), db=Depends(get_db)):
    expected = os.environ.get("DESKTOP_FLIGHT_TOKEN", "")
    if expected and request.client and request.client.host in {"127.0.0.1", "::1", "testclient"} and hmac.compare_digest(expected, x_flight_token):
        return None
    return await get_current_user(db, credentials)
