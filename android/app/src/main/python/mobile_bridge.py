import asyncio
import hashlib
import json
import os
import sqlite3
import threading
import time
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


_data_dir: Optional[Path] = None
_db_path: Optional[Path] = None
_db_lock = threading.RLock()
_loop: Optional[asyncio.AbstractEventLoop] = None
_loop_thread: Optional[threading.Thread] = None
_login_challenges: Dict[str, Dict[str, Any]] = {}
_task_events: Dict[int, threading.Event] = {}
_task_threads: Dict[int, threading.Thread] = {}
_scheduler_lock = threading.RLock()
LOGIN_CHALLENGE_TTL_SECONDS = 10 * 60


def _now() -> str:
    return datetime.now().isoformat(timespec="seconds")


def _ok(data: Any = None, message: str = "") -> str:
    return json.dumps({"success": True, "message": message, "data": data}, ensure_ascii=False)


def _fail(message: str, data: Any = None) -> str:
    return json.dumps({"success": False, "message": message, "data": data}, ensure_ascii=False)


def _clear_login_service_session(session_key: Optional[str]) -> None:
    if not session_key:
        return
    try:
        from app.services.login_service import LoginService

        LoginService(str(session_key)).clear_session()
    except Exception:
        pass


def _remove_login_challenge(challenge_id: str, *, clear_session: bool = False) -> Optional[Dict[str, Any]]:
    challenge = _login_challenges.pop(challenge_id, None)
    if clear_session and challenge:
        _clear_login_service_session(challenge.get("session_key"))
    return challenge


def _cleanup_login_challenges() -> None:
    now = time.time()
    expired_ids = [
        challenge_id
        for challenge_id, challenge in list(_login_challenges.items())
        if now - float(challenge.get("created_at") or 0) > LOGIN_CHALLENGE_TTL_SECONDS
    ]
    for challenge_id in expired_ids:
        _remove_login_challenge(challenge_id, clear_session=True)


def _ensure_initialized() -> None:
    if _data_dir is None or _db_path is None:
        raise RuntimeError("mobile_bridge is not initialized")


def initialize(data_dir: str) -> str:
    global _data_dir, _db_path
    _data_dir = Path(data_dir)
    os.environ["ANDROID_DATA_DIR"] = str(_data_dir)

    for subdir in ("assets", "sessions", "logs"):
        (_data_dir / subdir).mkdir(parents=True, exist_ok=True)

    _db_path = _data_dir / "12306.db"
    _init_db()

    try:
        from app.core.config import get_settings

        get_settings.cache_clear()
    except Exception:
        pass

    _ensure_loop()
    resume_running_tasks()
    return _ok({"data_dir": str(_data_dir)})


def shutdown() -> str:
    with _scheduler_lock:
        for event in list(_task_events.values()):
            event.set()
        _task_events.clear()
        _task_threads.clear()
    for challenge_id in list(_login_challenges.keys()):
        _remove_login_challenge(challenge_id, clear_session=True)
    return _ok()


def _connect() -> sqlite3.Connection:
    _ensure_initialized()
    conn = sqlite3.connect(str(_db_path), timeout=30, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    return conn


def _init_db() -> None:
    with _db_lock, _connect() as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL UNIQUE,
                railway_username TEXT,
                session_data TEXT,
                is_logged_in INTEGER NOT NULL DEFAULT 0,
                is_active INTEGER NOT NULL DEFAULT 1,
                login_time TEXT,
                created_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS tasks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                from_station TEXT NOT NULL,
                to_station TEXT NOT NULL,
                train_date TEXT NOT NULL,
                train_codes TEXT,
                train_types TEXT,
                seat_types TEXT NOT NULL,
                start_time_range TEXT,
                passengers TEXT NOT NULL,
                query_interval INTEGER NOT NULL DEFAULT 5,
                max_retry_count INTEGER NOT NULL DEFAULT 100,
                auto_submit INTEGER NOT NULL DEFAULT 1,
                status TEXT NOT NULL DEFAULT 'pending',
                retry_count INTEGER NOT NULL DEFAULT 0,
                order_id TEXT,
                result_message TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                started_at TEXT,
                finished_at TEXT
            );

            CREATE TABLE IF NOT EXISTS task_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id INTEGER NOT NULL,
                level TEXT NOT NULL DEFAULT 'info',
                message TEXT NOT NULL,
                details TEXT,
                created_at TEXT NOT NULL
            );
            """
        )
        conn.commit()


def _ensure_loop() -> asyncio.AbstractEventLoop:
    global _loop, _loop_thread
    if _loop and _loop.is_running():
        return _loop

    _loop = asyncio.new_event_loop()

    def run_loop() -> None:
        asyncio.set_event_loop(_loop)
        _loop.run_forever()

    _loop_thread = threading.Thread(target=run_loop, name="ticket-helper-python-loop", daemon=True)
    _loop_thread.start()
    return _loop


def _run(coro, timeout: int = 120):
    loop = _ensure_loop()
    future = asyncio.run_coroutine_threadsafe(coro, loop)
    return future.result(timeout=timeout)


def _set_setting(key: str, value: str) -> None:
    with _db_lock, _connect() as conn:
        conn.execute(
            "INSERT INTO settings(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            (key, value),
        )
        conn.commit()


def _get_setting(key: str) -> Optional[str]:
    with _db_lock, _connect() as conn:
        row = conn.execute("SELECT value FROM settings WHERE key = ?", (key,)).fetchone()
        return row["value"] if row else None


def _row_to_user(row: sqlite3.Row) -> Dict[str, Any]:
    return {
        "id": row["id"],
        "username": row["username"],
        "railway_username": row["railway_username"],
        "is_logged_in": bool(row["is_logged_in"]),
        "is_active": bool(row["is_active"]),
        "login_time": row["login_time"],
        "created_at": row["created_at"],
    }


def _current_user_row() -> Optional[sqlite3.Row]:
    user_id = _get_setting("current_user_id")
    if not user_id:
        return None
    with _db_lock, _connect() as conn:
        return conn.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()


def get_current_user() -> str:
    row = _current_user_row()
    if not row or not row["is_logged_in"]:
        return _ok(None)
    return _ok(_row_to_user(row))


def _current_cookies() -> Dict[str, str]:
    row = _current_user_row()
    if not row or not row["session_data"]:
        return {}
    try:
        session_data = json.loads(row["session_data"])
    except Exception:
        return {}
    if isinstance(session_data, dict) and isinstance(session_data.get("cookies"), dict):
        return session_data["cookies"]
    return session_data if isinstance(session_data, dict) else {}


def logout() -> str:
    row = _current_user_row()
    if row:
        try:
            from app.services.login_service import LoginService

            LoginService(str(row["id"])).clear_session()
        except Exception:
            pass
        with _db_lock, _connect() as conn:
            conn.execute(
                "UPDATE users SET is_logged_in = 0, session_data = NULL WHERE id = ?",
                (row["id"],),
            )
            conn.execute("DELETE FROM settings WHERE key = 'current_user_id'")
            conn.commit()
    return _ok()


def _normalize_cookies(cookies: Dict[str, Any]) -> Dict[str, str]:
    return {
        str(key): str(value)
        for key, value in (cookies or {}).items()
        if str(key).strip() and value is not None and str(value).strip()
    }


def _fallback_username(cookies: Dict[str, str]) -> str:
    fingerprint = hashlib.sha256(
        json.dumps(cookies, sort_keys=True, ensure_ascii=False).encode("utf-8")
    ).hexdigest()[:10]
    return f"railway_user_{fingerprint}"


async def _validate_12306_cookies(cookies: Dict[str, Any]) -> Tuple[bool, Dict[str, Any], str]:
    cookies = _normalize_cookies(cookies)
    if not cookies:
        return False, {}, "No 12306 cookies found"

    try:
        import httpx
        from app.services.login_service import LoginService
        from app.services.order_service import OrderService
    except Exception as exc:
        return False, {}, f"Validation dependencies unavailable: {exc}"

    username = ""
    user_payload: Dict[str, Any] = {}
    try:
        async with httpx.AsyncClient(
            headers=LoginService.HEADERS,
            timeout=30.0,
            verify=False,
            follow_redirects=True,
        ) as client:
            client.cookies.update(cookies)
            response = await client.post(LoginService.USER_INFO_URL, data={"_json_att": ""})
            if "login" in str(response.url) or "login.html" in str(response.url):
                return False, {"cookie_count": len(cookies)}, "12306 session is not logged in"
            try:
                payload = response.json()
            except Exception:
                return False, {"cookie_count": len(cookies)}, "12306 user info response is not JSON"

            if not payload.get("status") or not isinstance(payload.get("data"), dict):
                messages = payload.get("messages") or []
                message = messages[0] if messages else "12306 user info validation failed"
                return False, {"cookie_count": len(cookies), "response": payload}, message

            user_payload = payload["data"]
            username = (
                user_payload.get("user_name")
                or user_payload.get("username")
                or user_payload.get("name")
                or ""
            )
    except Exception as exc:
        return False, {"cookie_count": len(cookies)}, f"12306 user info validation error: {exc}"

    order_service = OrderService(cookies)
    try:
        success, passengers, message = await order_service.query_passengers()
    finally:
        await order_service.close()

    if not success:
        return False, {"cookie_count": len(cookies), "user": user_payload}, message or "Passenger validation failed"
    if not passengers:
        return False, {"cookie_count": len(cookies), "user": user_payload}, "No passengers returned by 12306"

    if not username:
        username = passengers[0].passenger_name or _fallback_username(cookies)

    return True, {
        "username": username,
        "cookie_count": len(cookies),
        "passenger_count": len(passengers),
        "has_device_cookie": "RAIL_DEVICEID" in cookies and "RAIL_EXPIRATION" in cookies,
    }, ""


def _upsert_cookie_user(cookies: Dict[str, Any], username: str, source: str) -> Dict[str, Any]:
    cookies = _normalize_cookies(cookies)
    username = (username or "").strip() or _fallback_username(cookies)
    session_data = {
        "cookies": cookies,
        "uamtk": "",
        "apptk": "",
        "username": username,
        "is_logged_in": True,
        "login_time": _now(),
        "source": source,
    }
    session_json = json.dumps(session_data, ensure_ascii=False)
    with _db_lock, _connect() as conn:
        row = conn.execute(
            "SELECT * FROM users WHERE railway_username = ? OR username = ? ORDER BY id LIMIT 1",
            (username, username),
        ).fetchone()
        if row:
            conn.execute(
                """
                UPDATE users
                SET username = ?, railway_username = ?, session_data = ?, is_logged_in = 1, login_time = ?
                WHERE id = ?
                """,
                (username, username, session_json, _now(), row["id"]),
            )
            user_id = row["id"]
        else:
            cur = conn.execute(
                """
                INSERT INTO users(username, railway_username, session_data, is_logged_in, login_time, created_at)
                VALUES(?, ?, ?, 1, ?, ?)
                """,
                (username, username, session_json, _now(), _now()),
            )
            user_id = cur.lastrowid
        conn.commit()
    _set_setting("current_user_id", str(user_id))
    row = _current_user_row()
    return _row_to_user(row)


def verify_web_session(cookies_json: str) -> str:
    try:
        cookies = json.loads(cookies_json or "{}")
        if not isinstance(cookies, dict):
            return _fail("Cookie payload must be an object")
        success, validation, message = _run(_validate_12306_cookies(cookies), timeout=90)
        if not success:
            return _fail(message, validation)
        user = _upsert_cookie_user(cookies, validation.get("username", ""), "webview")
        validation["user"] = user
        return _ok(validation, "Login succeeded")
    except Exception as exc:
        return _fail(str(exc))


def validate_current_session() -> str:
    row = _current_user_row()
    if not row or not row["is_logged_in"]:
        return _ok(None)
    cookies = _current_cookies()
    if not cookies:
        return _ok(None)
    success, validation, message = _run(_validate_12306_cookies(cookies), timeout=90)
    if not success:
        with _db_lock, _connect() as conn:
            conn.execute(
                "UPDATE users SET is_logged_in = 0, session_data = NULL WHERE id = ?",
                (row["id"],),
            )
            conn.execute("DELETE FROM settings WHERE key = 'current_user_id'")
            conn.commit()
        return _ok(None, message)
    return _ok(_row_to_user(row), "Session valid")


def create_login_qrcode() -> str:
    try:
        from app.services.login_service import LoginService

        _cleanup_login_challenges()
        challenge_id = uuid.uuid4().hex
        session_key = f"android_login_{challenge_id}"
        login_service = LoginService(session_key)
        qr_uuid, image_base64, error = _run(login_service.get_qr_code())
        if error or not qr_uuid or not image_base64:
            return _fail(error or "Failed to create QR code")
        _login_challenges[challenge_id] = {
            "session_key": session_key,
            "uuid": qr_uuid,
            "created_at": time.time(),
        }
        return _ok({"challenge_id": challenge_id, "uuid": qr_uuid, "image_base64": image_base64})
    except Exception as exc:
        return _fail(str(exc))


def check_login_qrcode_status(challenge_id: str) -> str:
    try:
        from app.services.login_service import LoginService, QRCodeStatus

        _cleanup_login_challenges()
        challenge = _login_challenges.get(challenge_id)
        if not challenge:
            return _fail("Login challenge not found or expired")

        login_service = LoginService(str(challenge["session_key"]))
        status, _uamtk = _run(login_service.check_qr_status(challenge["uuid"]))
        message = {
            QRCodeStatus.WAITING: "Waiting for scan",
            QRCodeStatus.SCANNED: "Scanned, confirm in 12306",
            QRCodeStatus.CONFIRMED: "Confirmed",
            QRCodeStatus.EXPIRED: "QR code expired",
            QRCodeStatus.ERROR: "QR status error",
        }.get(status, "Unknown status")

        auth = None
        is_success = False
        if status == QRCodeStatus.CONFIRMED:
            authenticated = _run(login_service.authenticate())
            if authenticated:
                valid, validation, validation_message = _run(
                    _validate_12306_cookies(login_service.session.cookies),
                    timeout=90,
                )
                if valid:
                    user = _upsert_login_user(login_service)
                    auth = {"access_token": "local", "token_type": "local", "expires_in": 0, "user": user}
                    is_success = True
                    _remove_login_challenge(challenge_id, clear_session=True)
                    message = "Login succeeded"
                else:
                    message = validation_message or "Login session cannot be used for ticket ordering"
                    status = QRCodeStatus.ERROR
                    _remove_login_challenge(challenge_id, clear_session=True)
            else:
                message = "12306 authentication failed"
                status = QRCodeStatus.ERROR
                _remove_login_challenge(challenge_id, clear_session=True)

        if status in (QRCodeStatus.EXPIRED, QRCodeStatus.ERROR):
            _remove_login_challenge(challenge_id, clear_session=True)

        return _ok({"status": status, "message": message, "is_success": is_success, "auth": auth})
    except Exception as exc:
        return _fail(str(exc))


def start_password_login(username: str, password: str) -> str:
    try:
        from app.services.login_service import LoginService

        _cleanup_login_challenges()
        username = (username or "").strip()
        password = password or ""
        if not username or not password:
            return _fail("请输入 12306 账号和密码")

        challenge_id = uuid.uuid4().hex
        session_key = f"android_password_login_{challenge_id}"
        login_service = LoginService(session_key)
        result = _run(login_service.begin_password_login(username, password), timeout=90)

        status = result.get("status")
        if status == "success":
            valid, validation, validation_message = _run(
                _validate_12306_cookies(login_service.session.cookies),
                timeout=90,
            )
            if not valid:
                login_service.clear_session()
                return _fail(validation_message or "Login session cannot be used for ticket ordering", validation)
            user = _upsert_login_user(login_service)
            result["auth"] = {"access_token": "local", "token_type": "local", "expires_in": 0, "user": user}
            result["user"] = user
            login_service.clear_session()
            return _ok(result, result.get("message", "Login succeeded"))

        if status == "needs_verification":
            _login_challenges[challenge_id] = {
                "kind": "password",
                "session_key": session_key,
                "username": username,
                "password": password,
                "created_at": time.time(),
                "slide_token": result.get("slide_token", ""),
                "available_verifications": result.get("available_verifications", []),
            }
            result["challenge_id"] = challenge_id
            return _ok(result, result.get("message", "Login verification required"))

        login_service.clear_session()
        if status == "manual_required":
            return _ok(result, result.get("message", "Manual verification required"))
        return _fail(result.get("message", "12306 password login failed"), result)
    except Exception as exc:
        return _fail(str(exc))


def send_login_sms(challenge_id: str, cast_num: str) -> str:
    try:
        from app.services.login_service import LoginService

        _cleanup_login_challenges()
        challenge = _login_challenges.get(challenge_id)
        if not challenge or challenge.get("kind") != "password":
            return _fail("Login challenge not found or expired")
        if not (cast_num or "").strip():
            return _fail("请输入证件号后 4 位")

        login_service = LoginService(str(challenge["session_key"]))
        result = _run(
            login_service.send_password_sms_code(str(challenge["username"]), cast_num),
            timeout=60,
        )
        code = str(result.get("result_code", ""))
        message = result.get("result_message") or result.get("message") or "短信验证码已发送"
        if code == "0":
            return _ok({"result_code": code, "raw": result}, message)
        return _fail(message, {"result_code": code, "raw": result})
    except Exception as exc:
        return _fail(str(exc))


def complete_password_login(challenge_id: str, verification_json: str) -> str:
    try:
        from app.services.login_service import LoginService

        _cleanup_login_challenges()
        challenge = _login_challenges.get(challenge_id)
        if not challenge or challenge.get("kind") != "password":
            return _fail("Login challenge not found or expired")

        verification = json.loads(verification_json or "{}")
        if not isinstance(verification, dict):
            return _fail("Verification payload must be an object")

        if verification.get("type") == "slide" and not verification.get("if_check_slide_passcode_token"):
            verification["if_check_slide_passcode_token"] = challenge.get("slide_token", "")
            verification["token"] = challenge.get("slide_token", "")

        login_service = LoginService(str(challenge["session_key"]))
        result = _run(
            login_service.submit_password_login(
                str(challenge["username"]),
                str(challenge["password"]),
                verification,
            ),
            timeout=90,
        )

        status = result.get("status")
        if status == "success":
            valid, validation, validation_message = _run(
                _validate_12306_cookies(login_service.session.cookies),
                timeout=90,
            )
            if not valid:
                login_service.clear_session()
                _remove_login_challenge(challenge_id, clear_session=True)
                return _fail(validation_message or "Login session cannot be used for ticket ordering", validation)
            user = _upsert_login_user(login_service)
            result["auth"] = {"access_token": "local", "token_type": "local", "expires_in": 0, "user": user}
            result["user"] = user
            _remove_login_challenge(challenge_id, clear_session=True)
            return _ok(result, result.get("message", "Login succeeded"))

        if status == "manual_required":
            _remove_login_challenge(challenge_id, clear_session=True)
            return _ok(result, result.get("message", "Manual verification required"))

        if status == "error":
            _remove_login_challenge(challenge_id, clear_session=True)
            return _fail(result.get("message", "12306 password login failed"), result)

        return _ok(result, result.get("message", "Login verification pending"))
    except Exception as exc:
        return _fail(str(exc))


def _upsert_login_user(login_service) -> Dict[str, Any]:
    username = login_service.session.username or "railway_user"
    session_json = json.dumps(login_service.session.to_dict(), ensure_ascii=False)
    login_time = login_service.session.login_time.isoformat() if login_service.session.login_time else _now()
    with _db_lock, _connect() as conn:
        row = conn.execute(
            "SELECT * FROM users WHERE railway_username = ? OR username = ? ORDER BY id LIMIT 1",
            (username, username),
        ).fetchone()
        if row:
            conn.execute(
                """
                UPDATE users
                SET username = ?, railway_username = ?, session_data = ?, is_logged_in = 1, login_time = ?
                WHERE id = ?
                """,
                (username, username, session_json, login_time, row["id"]),
            )
            user_id = row["id"]
        else:
            cur = conn.execute(
                """
                INSERT INTO users(username, railway_username, session_data, is_logged_in, login_time, created_at)
                VALUES(?, ?, ?, 1, ?, ?)
                """,
                (username, username, session_json, login_time, _now()),
            )
            user_id = cur.lastrowid
        conn.commit()

    _set_setting("current_user_id", str(user_id))
    row = _current_user_row()
    return _row_to_user(row)


def search_stations(keyword: str) -> str:
    try:
        from app.services.query_service import QueryService

        service = QueryService()
        stations = [station.__dict__ for station in service.search_stations(keyword or "", limit=20)]
        return _ok({"total": len(stations), "stations": stations})
    except Exception as exc:
        return _fail(str(exc), {"total": 0, "stations": []})


def query_tickets(params_json: str) -> str:
    try:
        from app.services.query_service import QueryService

        params = json.loads(params_json or "{}")
        train_types = params.get("train_types")
        if isinstance(train_types, str):
            train_types = [x for x in train_types.split(",") if x]
        start_time_range = None
        if params.get("start_time_min") and params.get("start_time_max"):
            start_time_range = (params["start_time_min"], params["start_time_max"])
        service = QueryService(_current_cookies())
        try:
            trains, error = _run(
                service.query(
                    from_station=params.get("from_station", ""),
                    to_station=params.get("to_station", ""),
                    train_date=params.get("train_date", ""),
                    train_types=train_types,
                    start_time_range=start_time_range,
                    only_has_ticket=bool(params.get("only_has_ticket", False)),
                )
            )
        finally:
            _run(service.close(), timeout=10)
        if error:
            return _fail(error, {"total": 0, "trains": []})
        data = [train.to_dict() for train in trains]
        return _ok({"total": len(data), "trains": data})
    except Exception as exc:
        return _fail(str(exc), {"total": 0, "trains": []})


def get_passengers() -> str:
    try:
        from app.services.order_service import OrderService

        cookies = _current_cookies()
        if not cookies:
            return _fail("Not logged in", [])
        service = OrderService(cookies)
        try:
            success, passengers, message = _run(service.query_passengers())
        finally:
            _run(service.close(), timeout=10)
        if not success:
            return _fail(message, [])
        return _ok([passenger.to_dict() for passenger in passengers])
    except Exception as exc:
        return _fail(str(exc), [])


def _normalize_task_payload(data: Dict[str, Any]) -> Dict[str, Any]:
    def join_optional(value):
        if value is None:
            return None
        if isinstance(value, list):
            return ",".join(str(x) for x in value if str(x))
        return str(value) if str(value) else None

    return {
        "name": data.get("name") or "",
        "from_station": data.get("from_station") or "",
        "to_station": data.get("to_station") or "",
        "train_date": data.get("train_date") or "",
        "train_codes": join_optional(data.get("train_codes")),
        "train_types": join_optional(data.get("train_types")),
        "seat_types": join_optional(data.get("seat_types")) or "O",
        "start_time_range": data.get("start_time_range"),
        "passengers": json.dumps(data.get("passengers") or [], ensure_ascii=False),
        "query_interval": int(data.get("query_interval") or 5),
        "max_retry_count": int(data.get("max_retry_count") if data.get("max_retry_count") is not None else 100),
        "auto_submit": 1 if data.get("auto_submit", True) else 0,
    }


def _row_to_task(row: sqlite3.Row) -> Dict[str, Any]:
    return {key: row[key] for key in row.keys()}


def get_tasks(status: str = "") -> str:
    with _db_lock, _connect() as conn:
        if status:
            rows = conn.execute(
                "SELECT * FROM tasks WHERE status = ? ORDER BY created_at DESC",
                (status,),
            ).fetchall()
        else:
            rows = conn.execute("SELECT * FROM tasks ORDER BY created_at DESC").fetchall()
    tasks = [_row_to_task(row) for row in rows]
    return _ok({"total": len(tasks), "tasks": tasks})


def get_task(task_id: int) -> str:
    row = _get_task_row(int(task_id))
    if not row:
        return _fail("Task not found")
    return _ok(_row_to_task(row))


def create_task(task_json: str) -> str:
    row = _current_user_row()
    if not row or not row["is_logged_in"]:
        return _fail("Not logged in")
    data = _normalize_task_payload(json.loads(task_json or "{}"))
    now = _now()
    with _db_lock, _connect() as conn:
        cur = conn.execute(
            """
            INSERT INTO tasks(
                user_id, name, from_station, to_station, train_date, train_codes, train_types,
                seat_types, start_time_range, passengers, query_interval, max_retry_count,
                auto_submit, status, retry_count, created_at, updated_at
            )
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', 0, ?, ?)
            """,
            (
                row["id"],
                data["name"],
                data["from_station"],
                data["to_station"],
                data["train_date"],
                data["train_codes"],
                data["train_types"],
                data["seat_types"],
                data["start_time_range"],
                data["passengers"],
                data["query_interval"],
                data["max_retry_count"],
                data["auto_submit"],
                now,
                now,
            ),
        )
        conn.commit()
        task_id = cur.lastrowid
    _add_log(task_id, "info", "Task created")
    return get_task(task_id)


def update_task(task_id: int, task_json: str) -> str:
    row = _get_task_row(int(task_id))
    if not row:
        return _fail("Task not found")
    if row["status"] == "running":
        return _fail("Stop task before editing")
    data = _normalize_task_payload(json.loads(task_json or "{}"))
    with _db_lock, _connect() as conn:
        conn.execute(
            """
            UPDATE tasks
            SET name = ?, from_station = ?, to_station = ?, train_date = ?, train_codes = ?,
                train_types = ?, seat_types = ?, start_time_range = ?, passengers = ?,
                query_interval = ?, max_retry_count = ?, auto_submit = ?, updated_at = ?
            WHERE id = ?
            """,
            (
                data["name"],
                data["from_station"],
                data["to_station"],
                data["train_date"],
                data["train_codes"],
                data["train_types"],
                data["seat_types"],
                data["start_time_range"],
                data["passengers"],
                data["query_interval"],
                data["max_retry_count"],
                data["auto_submit"],
                _now(),
                task_id,
            ),
        )
        conn.commit()
    _add_log(task_id, "info", "Task updated")
    return get_task(task_id)


def delete_task(task_id: int) -> str:
    stop_task(int(task_id))
    with _db_lock, _connect() as conn:
        conn.execute("DELETE FROM task_logs WHERE task_id = ?", (task_id,))
        conn.execute("DELETE FROM tasks WHERE id = ?", (task_id,))
        conn.commit()
    return _ok()


def start_task(task_id: int) -> str:
    task_id = int(task_id)
    row = _get_task_row(task_id)
    if not row:
        return _fail("Task not found")
    with _scheduler_lock:
        if task_id in _task_events and not _task_events[task_id].is_set():
            return _ok({"already_running": True})
        event = threading.Event()
        _task_events[task_id] = event
        thread = threading.Thread(target=_task_worker, args=(task_id, event), name=f"ticket-task-{task_id}", daemon=True)
        _task_threads[task_id] = thread
        _update_task_status(task_id, "running", started_at=row["started_at"] or _now(), result_message=None)
        _add_log(task_id, "info", "Task started")
        thread.start()
    return _ok()


def stop_task(task_id: int) -> str:
    task_id = int(task_id)
    with _scheduler_lock:
        event = _task_events.pop(task_id, None)
        _task_threads.pop(task_id, None)
        if event:
            event.set()
    row = _get_task_row(task_id)
    if row and row["status"] == "running":
        _update_task_status(task_id, "paused")
        _add_log(task_id, "info", "Task paused")
    return _ok()


def cancel_task(task_id: int) -> str:
    task_id = int(task_id)
    with _scheduler_lock:
        event = _task_events.pop(task_id, None)
        _task_threads.pop(task_id, None)
        if event:
            event.set()
    _update_task_status(task_id, "cancelled", finished_at=_now(), result_message="Task cancelled")
    _add_log(task_id, "warning", "Task cancelled")
    return _ok()


def resume_running_tasks() -> str:
    with _db_lock, _connect() as conn:
        rows = conn.execute("SELECT id FROM tasks WHERE status = 'running'").fetchall()
    for row in rows:
        start_task(int(row["id"]))
    return _ok({"count": len(rows)})


def get_running_task_count() -> str:
    with _db_lock, _connect() as conn:
        row = conn.execute("SELECT COUNT(*) AS count FROM tasks WHERE status = 'running'").fetchone()
    return _ok({"count": row["count"] if row else 0})


def get_task_logs(task_id: int, limit: int = 200) -> str:
    with _db_lock, _connect() as conn:
        rows = conn.execute(
            "SELECT * FROM task_logs WHERE task_id = ? ORDER BY id DESC LIMIT ?",
            (int(task_id), int(limit)),
        ).fetchall()
    logs = [{key: row[key] for key in row.keys()} for row in reversed(rows)]
    return _ok({"total": len(logs), "logs": logs})


def _get_task_row(task_id: int) -> Optional[sqlite3.Row]:
    with _db_lock, _connect() as conn:
        return conn.execute("SELECT * FROM tasks WHERE id = ?", (task_id,)).fetchone()


def _update_task_status(task_id: int, status: str, **fields: Any) -> None:
    updates = ["status = ?", "updated_at = ?"]
    values: List[Any] = [status, _now()]
    for key, value in fields.items():
        updates.append(f"{key} = ?")
        values.append(value)
    values.append(task_id)
    with _db_lock, _connect() as conn:
        conn.execute(f"UPDATE tasks SET {', '.join(updates)} WHERE id = ?", values)
        conn.commit()


def _add_log(task_id: int, level: str, message: str, details: str = None) -> None:
    with _db_lock, _connect() as conn:
        conn.execute(
            "INSERT INTO task_logs(task_id, level, message, details, created_at) VALUES(?, ?, ?, ?, ?)",
            (task_id, level, message, details, _now()),
        )
        conn.commit()


def _task_worker(task_id: int, event: threading.Event) -> None:
    while not event.is_set():
        try:
            _run(_execute_task_once(task_id), timeout=300)
        except Exception as exc:
            _add_log(task_id, "error", f"Execution error: {exc}")

        row = _get_task_row(task_id)
        if not row or row["status"] != "running":
            event.set()
            break
        interval = max(int(row["query_interval"] or 5), 3)
        event.wait(interval)

    with _scheduler_lock:
        if _task_events.get(task_id) is event:
            _task_events.pop(task_id, None)
        _task_threads.pop(task_id, None)


async def _execute_task_once(task_id: int) -> None:
    from app.services.order_service import OrderService
    from app.services.query_service import QueryService

    row = _get_task_row(task_id)
    if not row or row["status"] != "running":
        return

    if int(row["max_retry_count"]) > 0 and int(row["retry_count"]) >= int(row["max_retry_count"]):
        _update_task_status(task_id, "failed", finished_at=_now(), result_message="Max retry count reached")
        _add_log(task_id, "error", "Max retry count reached")
        return

    with _db_lock, _connect() as conn:
        conn.execute("UPDATE tasks SET retry_count = retry_count + 1, updated_at = ? WHERE id = ?", (_now(), task_id))
        conn.commit()

    user = _user_by_id(int(row["user_id"]))
    if not user or not user["session_data"]:
        _update_task_status(task_id, "failed", finished_at=_now(), result_message="User not logged in")
        _add_log(task_id, "error", "User not logged in")
        return

    cookies = _cookies_from_user_row(user)
    query_service = QueryService(cookies)
    try:
        train_types = row["train_types"].split(",") if row["train_types"] else None
        start_time_range = tuple(row["start_time_range"].split("-", 1)) if row["start_time_range"] else None
        trains, error = await query_service.query(
            from_station=row["from_station"],
            to_station=row["to_station"],
            train_date=row["train_date"],
            train_types=train_types,
            start_time_range=start_time_range,
            only_has_ticket=False,
        )
    finally:
        await query_service.close()

    if error:
        _add_log(task_id, "warning", f"Query failed: {error}")
        return
    if not trains:
        _add_log(task_id, "info", "No trains matched")
        return

    if row["train_codes"]:
        wanted = set(row["train_codes"].split(","))
        trains = [train for train in trains if train.train_code in wanted]
        if not trains:
            _add_log(task_id, "info", "Specified trains not found")
            return

    seat_types = row["seat_types"].split(",") if row["seat_types"] else ["O"]
    scan_details = []
    for train in trains:
        seat_status = []
        for seat_type in seat_types:
            seat_name, count = _seat_status(train, seat_type)
            if not seat_name:
                continue
            seat_status.append(f"{seat_name}:{count}")
            if not _can_buy(count) or not train.secret_str:
                continue

            message = f"Found ticket {train.train_code} {seat_name}({count})"
            _add_log(task_id, "success" if not row["auto_submit"] else "info", message)
            if not row["auto_submit"]:
                continue

            order_service = OrderService(cookies)
            try:
                success, api_passengers, passenger_error = await order_service.query_passengers()
                if not success or not api_passengers:
                    _add_log(task_id, "warning", f"Passenger query failed: {passenger_error}")
                    continue

                target = {
                    (p.get("passenger_name"), p.get("passenger_id_no")): p
                    for p in json.loads(row["passengers"] or "[]")
                }
                matched = []
                for passenger in api_passengers:
                    key = (passenger.passenger_name, passenger.passenger_id_no)
                    if key in target:
                        passenger.ticket_type = target[key].get("passenger_type", passenger.ticket_type)
                        matched.append(passenger)

                if not matched:
                    _add_log(task_id, "warning", "No matching passengers found")
                    continue

                result = await order_service.buy_ticket(
                    train_info=train,
                    secret_str=train.secret_str,
                    passengers=matched,
                    seat_type=seat_type,
                )
                if result.success:
                    _update_task_status(
                        task_id,
                        "success",
                        finished_at=_now(),
                        order_id=result.order_id,
                        result_message=result.message,
                    )
                    _add_log(task_id, "success", f"Order submitted: {result.order_id}")
                    return
                _add_log(task_id, "warning", f"Order failed: {result.message}")
            finally:
                await order_service.close()
        scan_details.append(f"{train.train_code}[{', '.join(seat_status)}]")

    if scan_details:
        _add_log(task_id, "info", "Scan complete: " + " | ".join(scan_details))


def _user_by_id(user_id: int) -> Optional[sqlite3.Row]:
    with _db_lock, _connect() as conn:
        return conn.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()


def _cookies_from_user_row(row: sqlite3.Row) -> Dict[str, str]:
    try:
        session_data = json.loads(row["session_data"])
    except Exception:
        return {}
    if isinstance(session_data, dict) and isinstance(session_data.get("cookies"), dict):
        return session_data["cookies"]
    return session_data if isinstance(session_data, dict) else {}


def _seat_status(train, seat_type: str) -> Tuple[str, str]:
    seat_map = {
        "9": ("Business", train.business_seat),
        "M": ("First", train.first_seat),
        "O": ("Second", train.second_seat),
        "4": ("Soft sleeper", train.soft_sleeper),
        "3": ("Hard sleeper", train.hard_sleeper),
        "1": ("Hard seat", train.hard_seat),
    }
    return seat_map.get(seat_type, ("", "--"))


def _can_buy(value: str) -> bool:
    if not value or value in ("--", "无", "*", ""):
        return False
    if value == "有":
        return True
    try:
        return int(value) > 0
    except Exception:
        return False
