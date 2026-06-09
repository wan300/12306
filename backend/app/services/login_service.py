#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
登录服务

封装 12306 扫码登录逻辑，支持多用户 Session 管理
"""

import json
import base64
import time
import asyncio
import random
import string
from pathlib import Path
from datetime import datetime, timedelta
from typing import Optional, Dict, Any, Tuple, Callable
from dataclasses import dataclass, field
import httpx

from ..core.config import get_settings
from ..utils.sm4 import sm4_encrypt_ecb_base64

settings = get_settings()


class QRCodeStatus:
    """二维码扫描状态"""
    WAITING = 0       # 等待扫码
    SCANNED = 1       # 已扫码，等待确认
    CONFIRMED = 2     # 确认登录（成功）
    EXPIRED = 3       # 二维码过期
    ERROR = 5         # 系统异常


@dataclass
class LoginSession:
    """登录会话信息"""
    cookies: Dict[str, str] = field(default_factory=dict)
    uamtk: str = ""
    apptk: str = ""
    username: str = ""
    is_logged_in: bool = False
    login_time: Optional[datetime] = None
    
    def to_dict(self) -> dict:
        """转换为可序列化的字典"""
        return {
            "cookies": self.cookies,
            "uamtk": self.uamtk,
            "apptk": self.apptk,
            "username": self.username,
            "is_logged_in": self.is_logged_in,
            "login_time": self.login_time.isoformat() if self.login_time else None
        }
    
    @classmethod
    def from_dict(cls, data: dict) -> "LoginSession":
        """从字典创建会话"""
        session = cls()
        session.cookies = data.get("cookies", {})
        session.uamtk = data.get("uamtk", "")
        session.apptk = data.get("apptk", "")
        session.username = data.get("username", "")
        session.is_logged_in = data.get("is_logged_in", False)
        login_time = data.get("login_time")
        session.login_time = datetime.fromisoformat(login_time) if login_time else None
        return session


class LoginService:
    """登录服务类（支持多用户）"""
    
    # API 端点
    BASE_URL = "https://kyfw.12306.cn"
    PASSPORT_URL = "https://kyfw.12306.cn/passport"
    
    QR_CREATE_URL = f"{PASSPORT_URL}/web/create-qr64"
    QR_CHECK_URL = f"{PASSPORT_URL}/web/checkqr"
    PASSWORD_CHECK_URL = f"{PASSPORT_URL}/web/checkLoginVerify"
    PASSWORD_LOGIN_URL = f"{PASSPORT_URL}/web/login"
    SLIDE_PASSCODE_URL = f"{PASSPORT_URL}/web/slide-passcode"
    SMS_CODE_URL = f"{PASSPORT_URL}/web/getMessageCode"
    UAMTK_URL = f"{PASSPORT_URL}/web/auth/uamtk"
    UAMAUTHCLIENT_URL = f"{BASE_URL}/otn/uamauthclient"
    USER_INFO_URL = f"{BASE_URL}/otn/index/initMy12306Api"
    
    APP_ID = "otn"
    SM4_KEY = "tiekeyuankp12306"
    
    HEADERS = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Accept': 'application/json, text/javascript, */*; q=0.01',
        'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
        'Accept-Encoding': 'gzip, deflate, br',
        'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
        'Origin': 'https://kyfw.12306.cn',
        'Referer': 'https://kyfw.12306.cn/otn/resources/login.html',
        'X-Requested-With': 'XMLHttpRequest',
    }
    
    # 用户会话存储（内存 + 文件持久化）
    _sessions: Dict[str, LoginSession] = {}
    _session_dir: Path = None
    
    def __init__(self, user_id: str = "default"):
        """
        初始化登录服务
        
        Args:
            user_id: 用户标识（用于多用户支持）
        """
        self.user_id = user_id
        self._client: Optional[httpx.AsyncClient] = None
        
        # 确保会话目录存在
        if LoginService._session_dir is None:
            LoginService._session_dir = Path(settings.SESSION_DIR)
            LoginService._session_dir.mkdir(parents=True, exist_ok=True)
        
        # 加载用户会话
        self._load_session()
    
    @property
    def session(self) -> LoginSession:
        """获取当前用户的会话"""
        if self.user_id not in LoginService._sessions:
            LoginService._sessions[self.user_id] = LoginSession()
        return LoginService._sessions[self.user_id]
    
    @session.setter
    def session(self, value: LoginSession):
        """设置当前用户的会话"""
        LoginService._sessions[self.user_id] = value
    
    async def get_client(self) -> httpx.AsyncClient:
        """获取异步 HTTP 客户端"""
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(
                headers=self.HEADERS,
                timeout=30.0,
                verify=False,
                follow_redirects=True
            )
            if self.session.cookies:
                self._client.cookies.update(self.session.cookies)
        return self._client
    
    async def close(self):
        """关闭客户端连接"""
        if self._client:
            await self._client.aclose()
            self._client = None
    
    # ==================== 会话管理 ====================
    
    def _get_session_file(self) -> Path:
        """获取会话文件路径"""
        return LoginService._session_dir / f"session_{self.user_id}.json"
    
    def _load_session(self) -> bool:
        """加载会话"""
        session_file = self._get_session_file()
        if session_file.exists():
            try:
                with open(session_file, 'r', encoding='utf-8') as f:
                    data = json.load(f)
                self.session = LoginSession.from_dict(data)
                return True
            except Exception:
                pass
        return False
    
    def _save_session(self):
        """保存会话"""
        session_file = self._get_session_file()
        try:
            with open(session_file, 'w', encoding='utf-8') as f:
                json.dump(self.session.to_dict(), f, ensure_ascii=False, indent=2)
        except Exception:
            pass
    
    def clear_session(self):
        """清除会话"""
        self.session = LoginSession()
        session_file = self._get_session_file()
        if session_file.exists():
            session_file.unlink()

    @staticmethod
    def _response_json(response: httpx.Response) -> Dict[str, Any]:
        """Parse plain JSON or a simple JSONP callback response."""
        text = response.text.strip()
        if text and "(" in text and text.endswith(")"):
            start = text.find("(")
            text = text[start + 1:-1]
            return json.loads(text)
        return response.json()

    @staticmethod
    def _result_code(payload: Dict[str, Any], key: str = "result_code") -> str:
        value = payload.get(key)
        return "" if value is None else str(value)

    @staticmethod
    def _result_message(payload: Dict[str, Any], fallback: str = "12306 请求失败") -> str:
        messages = payload.get("messages")
        if isinstance(messages, list) and messages:
            return str(messages[0])
        if messages:
            return str(messages)
        return str(payload.get("result_message") or payload.get("message") or fallback)

    def _ensure_device_fingerprint_cookie(self) -> None:
        if "RAIL_DEVICEID" not in self.session.cookies or "RAIL_EXPIRATION" not in self.session.cookies:
            synthetic = self._generate_synthetic_device_fingerprint()
            self.session.cookies.update(synthetic)
            self._save_session()
        if self._client and not self._client.is_closed:
            self._client.cookies.update(self.session.cookies)
    
    # ==================== 设备指纹 ====================
    
    def _generate_synthetic_device_fingerprint(self) -> Dict[str, str]:
        """生成合成的设备指纹（不推荐，可能被拦截）"""
        ts = int(time.time() * 1000)
        random_suffix = ''.join(random.choices(string.ascii_letters + string.digits, k=20))
        rail_deviceid = f"AlgID_X{ts}{random_suffix}"
        exp = int(time.time() * 1000) + 315360000000
        
        return {
            "RAIL_DEVICEID": rail_deviceid,
            "RAIL_EXPIRATION": str(exp)
        }

    def _get_device_fingerprint_sync(self, headless: bool = False) -> Dict[str, str]:
        """
        使用同步 Playwright 获取设备指纹 Cookie（在线程池中运行）
        默认使用非无头模式以提高成功率
        """
        try:
            from playwright.sync_api import sync_playwright
        except ImportError:
            print("[登录] 未安装 Playwright，使用合成指纹（可能被拦截）")
            return self._generate_synthetic_device_fingerprint()
        
        cookies_dict = {}
        
        try:
            with sync_playwright() as p:
                # 使用非无头模式，更真实的浏览器环境
                browser = p.chromium.launch(
                    headless=headless,
                    args=['--disable-blink-features=AutomationControlled']
                )
                context = browser.new_context(
                    user_agent=self.HEADERS['User-Agent'],
                    viewport={'width': 1920, 'height': 1080},
                    ignore_https_errors=True,
                    locale='zh-CN'
                )
                page = context.new_page()
                
                # 反爬虫脚本
                page.add_init_script("""
                    Object.defineProperty(navigator, 'webdriver', {get: () => undefined});
                    Object.defineProperty(navigator, 'plugins', {get: () => [1, 2, 3, 4, 5]});
                """)
                
                try:
                    print("[登录] 正在访问12306获取设备指纹...")
                    # 访问首页，更容易触发设备指纹生成
                    page.goto("https://kyfw.12306.cn/otn/resources/login.html", wait_until="domcontentloaded", timeout=10000)
                    
                    # 等待页面完全加载和JS执行
                    time.sleep(2)
                    
                    # 检查cookie
                    def check_cookie():
                        current_cookies = context.cookies()
                        for c in current_cookies:
                            if c['name'] == 'RAIL_DEVICEID':
                                return True
                        return False

                    # 轮询检查，最多8秒（减少等待时间）
                    for i in range(8):
                        if check_cookie():
                            print("[登录] ✓ 成功获取设备指纹")
                            break
                        if i % 3 == 0:
                            # 尝试触发更多JS执行
                            page.evaluate("() => { document.body.click(); }")
                        time.sleep(1)
                    else:
                        print("[登录] ⚠ 未能获取到设备指纹，将使用合成指纹")
                    
                    cookies = context.cookies()
                    for cookie in cookies:
                        cookies_dict[cookie['name']] = cookie['value']
                    
                except Exception as e:
                    print(f"[登录] 浏览器访问失败: {e}")
                finally:
                    browser.close()
        except Exception as e:
            print(f"[登录] Playwright 执行失败: {e}")

        # 检查是否获取到设备指纹
        if "RAIL_DEVICEID" not in cookies_dict:
            print("[登录] ⚠ 使用合成设备指纹（可能被12306拦截）")
            synthetic = self._generate_synthetic_device_fingerprint()
            cookies_dict.update(synthetic)
        else:
            print(f"[登录] ✓ 设备指纹: {cookies_dict['RAIL_DEVICEID'][:20]}...")
            
        return cookies_dict
    
    async def get_device_fingerprint(self, headless: bool = False) -> Dict[str, str]:
        """
        使用 Playwright 获取设备指纹 Cookie
        默认使用非无头模式（headless=False）以提高成功率
        在 Windows 上，Playwright 异步模式与 uvicorn 事件循环不兼容，
        因此使用线程池执行同步版本。
        """
        # 在线程池中运行同步的 Playwright
        loop = asyncio.get_event_loop()
        cookies_dict = await loop.run_in_executor(
            None,  # 使用默认线程池
            self._get_device_fingerprint_sync,
            headless
        )
        
        self.session.cookies.update(cookies_dict)
        self._save_session()
        
        return cookies_dict

    # ==================== 二维码登录 ====================
    
    async def get_qr_code(self) -> Tuple[Optional[str], Optional[str], Optional[str]]:
        """
        获取登录二维码
        
        Returns:
            (uuid, image_base64, error_message)
        """
        # 检查是否已有设备指纹 (RAIL_DEVICEID)
        if "RAIL_DEVICEID" not in self.session.cookies:
            # 直接使用合成指纹（避免长时间等待导致前端超时）
            print("[登录] 使用合成设备指纹（快速获取二维码）")
            synthetic = self._generate_synthetic_device_fingerprint()
            self.session.cookies.update(synthetic)
            self._save_session()
        
        # ... (rest of function)
        client = await self.get_client()
        
        try:
            response = await client.post(self.QR_CREATE_URL, data={"appid": self.APP_ID})
            result = response.json()
            
            if result.get("result_code") == "0":
                uuid = result.get("uuid")
                image_b64 = result.get("image")
                return uuid, image_b64, None
            else:
                error = result.get("result_message", "未知错误")
                return None, None, error
                
        except Exception as e:
            return None, None, str(e)
    
    async def check_qr_status(self, uuid: str) -> Tuple[int, Optional[str]]:
        """
        检查二维码扫描状态
        
        Returns:
            (status_code, uamtk)
        """
        client = await self.get_client()
        
        try:
            data = {
                "RAIL_DEVICEID": self.session.cookies.get("RAIL_DEVICEID", ""),
                "RAIL_EXPIRATION": self.session.cookies.get("RAIL_EXPIRATION", ""),
                "uuid": uuid,
                "appid": self.APP_ID
            }
            
            response = await client.post(self.QR_CHECK_URL, data=data)
            result = response.json()
            
            result_code = int(result.get("result_code", -1))
            uamtk = result.get("uamtk")
            
            return result_code, uamtk
            
        except Exception:
            return QRCodeStatus.ERROR, None

    # ==================== 账号密码登录 ====================

    async def check_password_login(self, username: str) -> Dict[str, Any]:
        """Check which verification mode 12306 requires before password login."""
        self._ensure_device_fingerprint_cookie()
        client = await self.get_client()
        response = await client.post(
            self.PASSWORD_CHECK_URL,
            data={"username": username.strip(), "appid": self.APP_ID},
        )
        return self._response_json(response)

    async def get_slide_passcode(self, username: str) -> Dict[str, Any]:
        """Request a noCaptcha token for the embedded slide verification widget."""
        self._ensure_device_fingerprint_cookie()
        client = await self.get_client()
        response = await client.post(
            self.SLIDE_PASSCODE_URL,
            data={"slideMode": "1", "appid": self.APP_ID, "username": username.strip()},
        )
        payload = self._response_json(response)
        data = payload.get("data") if isinstance(payload.get("data"), dict) else {}
        token = payload.get("if_check_slide_passcode_token") or data.get("if_check_slide_passcode_token") or ""
        payload["slide_token"] = token
        return payload

    async def send_password_sms_code(self, username: str, cast_num: str) -> Dict[str, Any]:
        """Send the pre-login SMS verification code."""
        client = await self.get_client()
        response = await client.post(
            self.SMS_CODE_URL,
            data={"appid": self.APP_ID, "username": username.strip(), "castNum": cast_num.strip()},
        )
        return self._response_json(response)

    async def request_uamtk(self) -> Dict[str, Any]:
        client = await self.get_client()
        response = await client.post(self.UAMTK_URL, data={"appid": self.APP_ID})
        return self._response_json(response)

    async def complete_authentication(self, uamtk_payload: Optional[Dict[str, Any]] = None) -> Tuple[bool, Dict[str, Any]]:
        """Finish 12306 unified auth and persist session cookies."""
        client = await self.get_client()
        payload = uamtk_payload if uamtk_payload is not None else await self.request_uamtk()
        if self._result_code(payload) != "0":
            return False, payload

        new_apptk = payload.get("newapptk") or payload.get("apptk")
        if not new_apptk:
            return False, {"result_message": "12306 未返回认证令牌", "response": payload}

        self.session.uamtk = new_apptk

        response = await client.post(self.UAMAUTHCLIENT_URL, data={"tk": new_apptk})
        result = self._response_json(response)
        if self._result_code(result) != "0":
            return False, result

        self.session.apptk = result.get("apptk", "")
        self.session.username = result.get("username", "")
        self.session.is_logged_in = True
        self.session.login_time = datetime.now()

        for cookie in client.cookies.jar:
            self.session.cookies[cookie.name] = cookie.value

        if "RAIL_DEVICEID" not in self.session.cookies:
            synthetic = self._generate_synthetic_device_fingerprint()
            self.session.cookies.update(synthetic)

        self._save_session()
        return True, result

    def _manual_auth_result(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        code = self._result_code(payload)
        mobile = str(payload.get("mobile") or "")
        messages = {
            "91": f"请用尾号{mobile}手机号发送短信 666 到 12306 后重试，或切换扫码登录。",
            "92": f"12306 要求手机号{mobile}下行短信核验，请切换扫码登录。",
            "94": "12306 要求线下身份核验，当前应用内无法完成。",
            "95": "12306 要求在官方 App 内完成登录核验，请切换扫码登录。",
            "97": "12306 要求额外登录控制验证，请切换扫码登录。",
        }
        return {
            "status": "manual_required",
            "result_code": code,
            "message": messages.get(code, self._result_message(payload, "12306 要求额外人工验证")),
            "raw": payload,
        }

    async def begin_password_login(self, username: str, password: str) -> Dict[str, Any]:
        """Start password login, returning success or the required verification mode."""
        username = username.strip()
        if not username or not password:
            return {"status": "error", "message": "请输入用户名和密码"}

        check_payload = await self.check_password_login(username)
        check_code = self._result_code(check_payload, "login_check_code")

        if check_code == "0":
            return await self.submit_password_login(username, password)

        if check_code in {"1", "2"}:
            slide_payload = await self.get_slide_passcode(username)
            slide_token = str(slide_payload.get("slide_token") or "")
            available = []
            if slide_token:
                available.append("slide")
            if check_code == "1":
                available.append("sms")
            if not available:
                return {
                    "status": "error",
                    "message": self._result_message(slide_payload, "12306 未返回可用验证方式"),
                    "raw": {"check": check_payload, "slide": slide_payload},
                }
            return {
                "status": "needs_verification",
                "verification_type": "choice" if len(available) > 1 else available[0],
                "available_verifications": available,
                "slide_token": slide_token,
                "login_check_code": check_code,
                "message": "请完成 12306 登录验证",
                "raw": {"check": check_payload, "slide": slide_payload},
            }

        if check_code == "3":
            return {
                "status": "needs_verification",
                "verification_type": "sms",
                "available_verifications": ["sms"],
                "login_check_code": check_code,
                "message": "请输入证件号后 4 位并获取短信验证码",
                "raw": {"check": check_payload},
            }

        return {
            "status": "error",
            "message": self._result_message(check_payload, "12306 登录前校验失败"),
            "raw": {"check": check_payload},
        }

    async def submit_password_login(
        self,
        username: str,
        password: str,
        verification: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        """Submit username/password with optional SMS or slide verification data."""
        self._ensure_device_fingerprint_cookie()
        client = await self.get_client()
        verification = verification or {}
        verification_type = str(verification.get("type") or verification.get("verification_type") or "")

        form_data: Dict[str, Any] = {
            "sessionId": "",
            "sig": "",
            "if_check_slide_passcode_token": "",
            "scene": "",
            "checkMode": "",
            "randCode": "",
            "username": username.strip(),
            "password": "@" + sm4_encrypt_ecb_base64(password, self.SM4_KEY),
            "appid": self.APP_ID,
        }

        if verification_type == "slide":
            form_data.update(
                {
                    "sessionId": verification.get("sessionId") or verification.get("session_id") or "",
                    "sig": verification.get("sig") or "",
                    "if_check_slide_passcode_token": (
                        verification.get("if_check_slide_passcode_token")
                        or verification.get("token")
                        or ""
                    ),
                    "scene": verification.get("scene") or "nc_login",
                    "checkMode": "1",
                }
            )
        elif verification_type == "sms":
            form_data.update(
                {
                    "checkMode": "0",
                    "randCode": verification.get("randCode") or verification.get("sms_code") or "",
                }
            )

        response = await client.post(
            self.PASSWORD_LOGIN_URL,
            data=form_data,
            headers={"isPasswordCopy": "N", "appFlag": ""},
        )
        payload = self._response_json(response)
        code = self._result_code(payload)

        if code == "0":
            auth_success, auth_payload = await self.complete_authentication()
            if auth_success:
                return {
                    "status": "success",
                    "message": f"登录成功，用户 {self.session.username or username}",
                    "username": self.session.username or username,
                    "raw": {"login": payload, "auth": auth_payload},
                }
            auth_code = self._result_code(auth_payload)
            if auth_code in {"91", "92", "94", "95", "97"}:
                return self._manual_auth_result(auth_payload)
            return {
                "status": "error",
                "message": self._result_message(auth_payload, "12306 认证失败"),
                "raw": {"login": payload, "auth": auth_payload},
            }

        if code in {"91", "92", "94", "95", "97"}:
            auth_payload = await self.request_uamtk()
            return self._manual_auth_result(auth_payload)

        if code == "101":
            return {
                "status": "error",
                "result_code": code,
                "message": "您的密码很久没有修改了，请先到 12306 官方渠道重新设置密码。",
                "raw": payload,
            }

        return {
            "status": "error",
            "result_code": code,
            "message": self._result_message(payload, "12306 账号密码登录失败"),
            "raw": payload,
        }
    
    async def poll_qr_status(
        self,
        uuid: str,
        interval: float = 2.0,
        timeout: float = 120.0,
        on_status_change: Optional[Callable[[int], None]] = None
    ) -> Tuple[bool, Optional[str]]:
        """
        轮询二维码扫描状态
        
        Returns:
            (success, uamtk)
        """
        start_time = time.time()
        last_status = None
        
        while True:
            if time.time() - start_time > timeout:
                return False, None
            
            status, uamtk = await self.check_qr_status(uuid)
            
            if status != last_status:
                last_status = status
                if on_status_change:
                    on_status_change(status)
            
            if status == QRCodeStatus.CONFIRMED:
                return True, uamtk
            elif status in (QRCodeStatus.EXPIRED, QRCodeStatus.ERROR):
                return False, None
            
            await asyncio.sleep(interval)
    
    # ==================== 登录认证 ====================
    
    async def authenticate(self) -> bool:
        """完成登录认证流程"""
        try:
            success, _ = await self.complete_authentication()
            return success
        except Exception:
            return False
    
    async def check_login_status(self) -> bool:
        """检查登录状态是否有效"""
        if not self.session.is_logged_in:
            return False
        
        client = await self.get_client()
        
        try:
            response = await client.post(
                self.USER_INFO_URL,
                data={"_json_att": ""}
            )
            result = response.json()
            
            if result.get("status") and result.get("data"):
                return True
            return False
            
        except Exception:
            return False
    
    # ==================== 完整登录流程 ====================
    
    async def login_with_qr(
        self,
        on_qr_code: Optional[Callable[[str, str], None]] = None,
        on_status_change: Optional[Callable[[int], None]] = None
    ) -> Tuple[bool, str]:
        """
        完整的扫码登录流程
        
        Args:
            on_qr_code: 二维码回调 (uuid, image_base64)
            on_status_change: 状态变化回调
            
        Returns:
            (success, message)
        """
        # 1. 获取设备指纹
        try:
            await self.get_device_fingerprint()
        except Exception as e:
            return False, f"获取设备指纹失败: {e}"
        
        # 2. 获取二维码
        uuid, image_b64, error = await self.get_qr_code()
        if error:
            return False, f"获取二维码失败: {error}"
        
        if on_qr_code:
            on_qr_code(uuid, image_b64)
        
        # 3. 轮询扫码状态
        success, uamtk = await self.poll_qr_status(
            uuid,
            on_status_change=on_status_change
        )
        
        if not success:
            return False, "扫码失败或超时"
        
        # 4. 完成认证
        if await self.authenticate():
            return True, f"登录成功，用户: {self.session.username}"
        else:
            return False, "认证失败"
    
    def get_cookies(self) -> Dict[str, str]:
        """获取当前会话的 cookies"""
        return self.session.cookies.copy()
    
    def is_logged_in(self) -> bool:
        """检查是否已登录"""
        return self.session.is_logged_in
    
    def get_username(self) -> Optional[str]:
        """获取用户名"""
        return self.session.username if self.session.is_logged_in else None
