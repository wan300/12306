import unittest
from unittest.mock import AsyncMock

from app.services.login_service import LoginService
from app.utils.sm4 import sm4_encrypt_ecb_base64


class Sm4PasswordEncryptionTest(unittest.TestCase):
    def test_matches_12306_login_script_examples(self):
        self.assertEqual(
            sm4_encrypt_ecb_base64("123456", "tiekeyuankp12306"),
            "grRrViQiBQgpTr59DNzcVw==",
        )
        self.assertEqual(
            sm4_encrypt_ecb_base64("abcXYZ09", "tiekeyuankp12306"),
            "fp3yD4yuJvPzfa0vosgBNQ==",
        )


class PasswordLoginBranchTest(unittest.IsolatedAsyncioTestCase):
    async def test_no_verification_submits_password_login(self):
        service = object.__new__(LoginService)
        service.check_password_login = AsyncMock(return_value={"login_check_code": "0"})
        service.submit_password_login = AsyncMock(return_value={"status": "success"})

        result = await LoginService.begin_password_login(service, "alice", "secret")

        self.assertEqual(result["status"], "success")
        service.submit_password_login.assert_awaited_once_with("alice", "secret")

    async def test_slide_verification_branch_returns_token(self):
        service = object.__new__(LoginService)
        service.check_password_login = AsyncMock(return_value={"login_check_code": "2"})
        service.get_slide_passcode = AsyncMock(return_value={"slide_token": "slide-token"})

        result = await LoginService.begin_password_login(service, "alice", "secret")

        self.assertEqual(result["status"], "needs_verification")
        self.assertEqual(result["verification_type"], "slide")
        self.assertEqual(result["slide_token"], "slide-token")

    async def test_sms_verification_branch(self):
        service = object.__new__(LoginService)
        service.check_password_login = AsyncMock(return_value={"login_check_code": "3"})

        result = await LoginService.begin_password_login(service, "alice", "secret")

        self.assertEqual(result["status"], "needs_verification")
        self.assertEqual(result["verification_type"], "sms")
        self.assertEqual(result["available_verifications"], ["sms"])


if __name__ == "__main__":
    unittest.main()
