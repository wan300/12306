import asyncio
import importlib.util
import json
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

path = Path(__file__).resolve().parents[2] / "android/app/src/main/python/mobile_bridge.py"
spec = importlib.util.spec_from_file_location("qr_test_mobile_bridge", path)
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


class QrSessionTest(unittest.TestCase):
    def test_create_poll_and_authentication_keep_one_http_cookie_jar(self):
        service = SimpleNamespace(get_qr_code=AsyncMock(return_value=("test-qr", "test-image", None)),
            check_qr_status=AsyncMock(return_value=(2, "opaque-auth")), authenticate=AsyncMock(return_value=True),
            close=AsyncMock(), clear_session=Mock(), session=SimpleNamespace(cookies={"test": "test-only"}))
        bridge._login_challenges.clear()
        with patch("app.services.login_service.LoginService", return_value=service) as factory, \
             patch.object(bridge, "_run", side_effect=lambda coro, **_: asyncio.run(coro)), \
             patch.object(bridge, "_validate_12306_cookies", AsyncMock(return_value=(True, {}, ""))), \
             patch.object(bridge, "_upsert_login_user", return_value={"id": 1}):
            created = json.loads(bridge.create_login_qrcode())
            result = json.loads(bridge.check_login_qrcode_status(created["data"]["challenge_id"]))
            self.assertTrue(result["data"]["is_success"])
            factory.assert_called_once()
            service.check_qr_status.assert_awaited_once_with("test-qr")
            service.authenticate.assert_awaited_once()
            service.close.assert_awaited_once()
            service.clear_session.assert_called_once()
            self.assertEqual(bridge._login_challenges, {})
            self.assertNotIn("test-only", json.dumps(result))

    def test_expired_qr_closes_client_and_discards_challenge(self):
        service = SimpleNamespace(check_qr_status=AsyncMock(return_value=(3, None)), close=AsyncMock(), clear_session=Mock())
        import time
        bridge._login_challenges["test"] = {"service": service, "session_key": "test", "created_at": time.time(), "uuid": "test-qr"}
        with patch.object(bridge, "_run", side_effect=lambda coro, **_: asyncio.run(coro)):
            result = json.loads(bridge.check_login_qrcode_status("test"))
            self.assertFalse(result["data"]["is_success"])
            self.assertEqual(result["data"]["status"], 3)
            service.close.assert_awaited_once()
            self.assertEqual(bridge._login_challenges, {})
