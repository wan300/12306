#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
订单服务

封装 12306 购票下单逻辑
"""

import re
import json
import time
import asyncio
import urllib.parse
from dataclasses import dataclass, field
from typing import Optional, List, Dict, Any, Tuple, Callable
from datetime import datetime
import httpx

from .query_service import TrainInfo


@dataclass
class Passenger:
    """乘车人信息"""
    passenger_name: str
    passenger_id_no: str
    passenger_id_type_code: str = "1"  # 身份证
    passenger_type: str = "1"  # 乘客身份类型（1:成人, 2:儿童, 3:学生, 4:残军）
    ticket_type: str = "1"     # 购票类型（1:成人票, 2:儿童票, 3:学生票, 4:残军票）
    mobile_no: str = ""
    all_enc_str: str = ""
    passenger_flag: str = "0"
    index_id: str = ""
    
    @classmethod
    def from_api_response(cls, data: dict) -> "Passenger":
        """从 API 响应创建"""
        p_type = data.get("passenger_type", "1")
        return cls(
            passenger_name=data.get("passenger_name", ""),
            passenger_id_no=data.get("passenger_id_no", ""),
            passenger_id_type_code=data.get("passenger_id_type_code", "1"),
            passenger_type=p_type,
            ticket_type=p_type, # 默认购票类型等于身份类型，后续可覆盖
            mobile_no=data.get("mobile_no", ""),
            all_enc_str=data.get("allEncStr", ""),
            passenger_flag=data.get("passenger_flag", "0"),
            index_id=data.get("index_id", ""),
        )
    
    def to_dict(self) -> dict:
        return {
            "passenger_name": self.passenger_name,
            "passenger_id_no": self.passenger_id_no,
            "passenger_id_type_code": self.passenger_id_type_code,
            "passenger_type": self.passenger_type,
            "ticket_type": self.ticket_type,
            "mobile_no": self.mobile_no,
        }


@dataclass
class OrderToken:
    """订单 Token 信息"""
    repeat_submit_token: str = ""
    key_check_is_change: str = ""
    left_ticket_str: str = ""
    train_location: str = ""
    tour_flag: str = "dc"
    purpose_codes: str = "00"
    is_async: str = "1"
    
    train_no: str = ""
    station_train_code: str = ""
    from_station_telecode: str = ""
    to_station_telecode: str = ""
    train_date: str = ""
    seat_types: str = ""


@dataclass
class OrderResult:
    """订单结果"""
    success: bool = False
    order_id: str = ""
    message: str = ""
    wait_time: int = -1
    wait_count: int = 0
    requires_review: bool = False


class OrderService:
    """订单服务"""
    
    BASE_URL = "https://kyfw.12306.cn"
    
    SUBMIT_ORDER_URL = f"{BASE_URL}/otn/leftTicket/submitOrderRequest"
    INIT_DC_URL = f"{BASE_URL}/otn/confirmPassenger/initDc"
    GET_PASSENGERS_URL = f"{BASE_URL}/otn/confirmPassenger/getPassengerDTOs"
    QUERY_PASSENGERS_URL = f"{BASE_URL}/otn/passengers/query"
    CHECK_ORDER_URL = f"{BASE_URL}/otn/confirmPassenger/checkOrderInfo"
    GET_QUEUE_COUNT_URL = f"{BASE_URL}/otn/confirmPassenger/getQueueCount"
    CONFIRM_ORDER_URL = f"{BASE_URL}/otn/confirmPassenger/confirmSingleForQueue"
    QUERY_ORDER_URL = f"{BASE_URL}/otn/confirmPassenger/queryOrderWaitTime"
    
    HEADERS = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Accept': 'application/json, text/javascript, */*; q=0.01',
        'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
        'Accept-Encoding': 'gzip, deflate, br',
        'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
        'Origin': 'https://kyfw.12306.cn',
        'Referer': 'https://kyfw.12306.cn/otn/leftTicket/init',
        'X-Requested-With': 'XMLHttpRequest',
    }
    
    def __init__(self, cookies: Dict[str, str] = None):
        """
        初始化订单服务
        
        Args:
            cookies: 登录后的 cookies
        """
        self._cookies = cookies or {}
        self._client: Optional[httpx.AsyncClient] = None
        self._order_token: Optional[OrderToken] = None
        self._passengers: List[Passenger] = []
    
    async def get_client(self) -> httpx.AsyncClient:
        """获取异步 HTTP 客户端"""
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(
                headers=self.HEADERS,
                timeout=30.0,
                verify=False,
                follow_redirects=True
            )
            if self._cookies:
                self._client.cookies.update(self._cookies)
        return self._client
    
    def set_cookies(self, cookies: Dict[str, str]):
        """设置 cookies"""
        self._cookies = cookies
        if self._client:
            self._client.cookies.update(cookies)
    
    async def close(self):
        """关闭连接"""
        if self._client:
            await self._client.aclose()
            self._client = None
    
    # ==================== 0. 常用数据获取 ====================

    async def query_passengers(self) -> Tuple[bool, List[Passenger], str]:
        """获取乘车人列表 (无需 init_dc)"""
        if not self._client:
            await self.get_client()
            
        try:
            response = await self._client.post(
                self.QUERY_PASSENGERS_URL,
                data={
                    "pageIndex": "1",
                    "pageSize": "100"
                }
            )
            
            # 检查是否是登录页面重定向
            if "login" in str(response.url) or "login.html" in str(response.url):
                 return False, [], "用户登录已过期，请重新登录"

            try:
                data = response.json()
            except json.JSONDecodeError:
                return False, [], "接口响应解析失败(非JSON)，可能是登录已过期"

            if data.get("status") and data.get("data") and "datas" in data["data"]:
                # /otn/passengers/query 返回结构 data: { datas: [...], ... }
                passengers_data = data["data"]["datas"]
                self._passengers = [Passenger.from_api_response(p) for p in passengers_data]
                return True, self._passengers, ""
            elif data.get("status") and data.get("data") and "normal_passengers" in data["data"]:
                 # 兼容 getPassengerDTOs 的返回
                 passengers_data = data["data"]["normal_passengers"]
                 self._passengers = [Passenger.from_api_response(p) for p in passengers_data]
                 return True, self._passengers, ""
            else:
                messages = data.get("messages", [])
                error = messages[0] if messages else "获取乘车人失败"
                if "未登录" in str(data) or "noLogin" in str(data):
                    error = "用户未登录或登录已过期"
                return False, [], error
                
        except Exception as e:
            return False, [], f"获取乘车人异常: {str(e)}"

    # ==================== 1. 提交订单请求 ====================
    
    async def submit_order_request(
        self,
        train_info: TrainInfo,
        secret_str: str,
        purpose_codes: str = "ADULT"
    ) -> Tuple[bool, str]:
        """提交订单请求"""
        client = await self.get_client()
        
        decoded_secret = urllib.parse.unquote(secret_str)
        
        data = {
            "secretStr": decoded_secret,
            "train_date": train_info.train_date,
            "back_train_date": train_info.train_date,
            "tour_flag": "dc",
            "purpose_codes": purpose_codes,
            "query_from_station_name": train_info.from_station,
            "query_to_station_name": train_info.to_station,
            "undefined": ""
        }
        
        try:
            response = await client.post(self.SUBMIT_ORDER_URL, data=data)
            
            if response.status_code != 200:
                return False, f"HTTP Error {response.status_code}"

            try:
                result = response.json()
            except json.JSONDecodeError:
                return False, "提交订单响应解析失败"
            
            if result.get("status"):
                return True, ""
            else:
                messages = result.get("messages", ["未知错误"])
                error_msg = messages[0] if messages else "未知错误"
                return False, error_msg
                
        except Exception as e:
            return False, str(e)
    
    # ==================== 2. 初始化订单页面 ====================
    
    async def init_dc(self) -> Tuple[bool, str]:
        """初始化订单页面，获取 Token"""
        client = await self.get_client()
        
        try:
            response = await client.post(self.INIT_DC_URL, data={"_json_att": ""})
            html_content = response.text
            
            # 检查是否重定向到错误页
            if "error.html" in str(response.url):
                cookie_keys = list(client.cookies.keys())
                if "RAIL_DEVICEID" not in cookie_keys:
                    return False, "设备指纹验证失败，请重新获取二维码登录"
                return False, "12306 拒绝请求，可能触发风控"

            # 检查是否重定向到登录页
            if "login" in str(response.url) or "login.html" in str(response.url):
                 return False, "用户未登录或登录已过期"

            # 简易检查 HTML 内容
            if "网络繁忙" in html_content:
                return False, "12306 系统繁忙"

            token = self._parse_init_dc_html(html_content)
            if token:
                self._order_token = token
                return True, ""
            else:
                if "如果您是个人自行注册的用户" in html_content:
                    return False, "登录已过期，请重新登录"
                if "RAIL_DEVICEID" not in str(client.cookies.keys()):
                    return False, "设备指纹缺失，请重新获取二维码"
                return False, "解析订单页面失败，请重试"
                
        except Exception as e:
            return False, str(e)
    
    def _parse_init_dc_html(self, html: str) -> Optional[OrderToken]:
        """解析 initDc 返回的 HTML"""
        token = OrderToken()
        
        # globalRepeatSubmitToken
        token_match = re.search(r"var globalRepeatSubmitToken\s*=\s*'([^']+)';", html)
        if token_match:
            token.repeat_submit_token = token_match.group(1)
        else:
            return None
        
        # ticketInfoForPassengerForm
        start_str = "var ticketInfoForPassengerForm="
        start_idx = html.find(start_str)
        
        if start_idx == -1:
            return None
        
        json_start = start_idx + len(start_str)
        end_idx = html.find("};", json_start)
        
        if end_idx == -1:
            return None
        
        js_object = html[json_start:end_idx + 1]
        
        # 提取关键字段
        patterns = {
            'key_check_is_change': r"'key_check_isChange':'([^']+)'",
            'left_ticket_str': r"'leftTicketStr':'([^']+)'",
            'train_location': r"'train_location':'([^']+)'",
            'tour_flag': r"'tour_flag':'([^']+)'",
            'purpose_codes': r"'purpose_codes':'([^']+)'",
            'is_async': r"'isAsync':'([^']+)'",
            'train_no': r"'train_no':'([^']+)'",
            'station_train_code': r"'station_train_code':'([^']+)'",
            'from_station_telecode': r"'from_station_telecode':'([^']+)'",
            'to_station_telecode': r"'to_station_telecode':'([^']+)'",
            'train_date': r"'train_date':'([^']+)'",
            'seat_types': r"'seat_types':'([^']+)'",
        }
        
        for field, pattern in patterns.items():
            match = re.search(pattern, js_object)
            if match:
                setattr(token, field, match.group(1))
        
        return token
    
    # ==================== 3. 获取乘车人 ====================
    
    async def get_passengers(self) -> Tuple[List[Passenger], str]:
        """获取乘车人列表"""
        if not self._order_token:
            return [], "请先调用 init_dc()"
        
        client = await self.get_client()
        
        try:
            data = {
                "REPEAT_SUBMIT_TOKEN": self._order_token.repeat_submit_token,
                "_json_att": ""
            }
            
            response = await client.post(self.GET_PASSENGERS_URL, data=data)
            result = response.json()
            
            if result.get("status") and result.get("data"):
                passengers_data = result["data"].get("normal_passengers", [])
                self._passengers = [Passenger.from_api_response(p) for p in passengers_data]
                return self._passengers, ""
            else:
                messages = result.get("messages", ["获取失败"])
                return [], messages[0] if messages else "获取失败"
                
        except Exception as e:
            return [], str(e)
    
    # ==================== 4. 校验订单 ====================
    
    async def check_order_info(
        self,
        passengers: List[Passenger],
        seat_type: str = "O"
    ) -> Tuple[bool, str]:
        """校验订单信息"""
        if not self._order_token:
            return False, "请先调用 init_dc()"
        
        client = await self.get_client()
        
        passenger_ticket_str = self._build_passenger_ticket_str(passengers, seat_type)
        old_passenger_str = self._build_old_passenger_str(passengers)
        
        try:
            data = {
                "cancel_flag": "2",
                "bed_level_order_num": "000000000000000000000000000000",
                "passengerTicketStr": passenger_ticket_str,
                "oldPassengerStr": old_passenger_str,
                "tour_flag": self._order_token.tour_flag,
                "randCode": "",
                "whatsSelect": "1",
                "_json_att": "",
                "REPEAT_SUBMIT_TOKEN": self._order_token.repeat_submit_token
            }
            
            response = await client.post(self.CHECK_ORDER_URL, data=data)
            result = response.json()
            
            if result.get("status") and result.get("data", {}).get("submitStatus"):
                return True, ""
            else:
                error_msg = result.get("data", {}).get("errMsg", "")
                if not error_msg:
                    messages = result.get("messages", [])
                    error_msg = messages[0] if messages else "校验失败"
                return False, error_msg
                
        except Exception as e:
            return False, str(e)
    
    def _build_passenger_ticket_str(
        self,
        passengers: List[Passenger],
        seat_type: str,
    ) -> str:
        """构造 passengerTicketStr"""
        parts = []
        for p in passengers:
            # 使用乘客对象中的 ticket_type (购票类型)
            part = f"{seat_type},0,{p.ticket_type},{p.passenger_name},{p.passenger_id_type_code},{p.passenger_id_no},{p.mobile_no},N,{p.all_enc_str}"
            parts.append(part)
        return "_".join(parts)
    
    def _build_old_passenger_str(self, passengers: List[Passenger]) -> str:
        """构造 oldPassengerStr"""
        parts = []
        for p in passengers:
            part = f"{p.passenger_name},{p.passenger_id_type_code},{p.passenger_id_no},{p.passenger_type}"
            parts.append(part)
        return "_".join(parts) + "_"
    
    # ==================== 5. 获取排队信息 ====================
    
    async def get_queue_count(self, seat_type: str = "O") -> Tuple[bool, int, int, str]:
        """获取排队信息"""
        if not self._order_token:
            return False, 0, 0, "请先调用 init_dc()"
        
        client = await self.get_client()
        
        # 构造日期格式
        train_date = self._order_token.train_date
        try:
            dt = datetime.strptime(train_date, "%Y%m%d")
            weekdays = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
            months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
            js_date = f"{weekdays[dt.weekday()]} {months[dt.month-1]} {dt.day:02d} {dt.year} 00:00:00 GMT+0800 (中国标准时间)"
        except:
            js_date = train_date
        
        try:
            data = {
                "train_date": js_date,
                "train_no": self._order_token.train_no,
                "stationTrainCode": self._order_token.station_train_code,
                "seatType": seat_type,
                "fromStationTelecode": self._order_token.from_station_telecode,
                "toStationTelecode": self._order_token.to_station_telecode,
                "leftTicket": self._order_token.left_ticket_str,
                "purpose_codes": self._order_token.purpose_codes,
                "train_location": self._order_token.train_location,
                "_json_att": "",
                "REPEAT_SUBMIT_TOKEN": self._order_token.repeat_submit_token
            }
            
            response = await client.post(self.GET_QUEUE_COUNT_URL, data=data)
            result = response.json()
            
            if result.get("status") and result.get("data"):
                data_info = result["data"]
                
                ticket_str = data_info.get("ticket", "0,0")
                tickets = ticket_str.split(",")
                ticket_count = int(tickets[0]) if tickets[0].isdigit() else 0
                queue_count = int(data_info.get("countT", 0))
                
                return True, ticket_count, queue_count, ""
            else:
                messages = result.get("messages", ["获取失败"])
                return False, 0, 0, messages[0] if messages else "获取失败"
                
        except Exception as e:
            return False, 0, 0, str(e)
    
    # ==================== 6. 确认订单 ====================
    
    async def confirm_order(
        self,
        passengers: List[Passenger],
        seat_type: str = "O",
        choose_seats: str = ""
    ) -> Tuple[bool, str]:
        """确认提交订单"""
        if not self._order_token:
            return False, "请先调用 init_dc()"
        
        client = await self.get_client()
        
        passenger_ticket_str = self._build_passenger_ticket_str(passengers, seat_type)
        old_passenger_str = self._build_old_passenger_str(passengers)
        
        try:
            data = {
                "passengerTicketStr": passenger_ticket_str,
                "oldPassengerStr": old_passenger_str,
                "randCode": "",
                "purpose_codes": self._order_token.purpose_codes,
                "key_check_isChange": self._order_token.key_check_is_change,
                "leftTicketStr": self._order_token.left_ticket_str,
                "train_location": self._order_token.train_location,
                "choose_seats": choose_seats,
                "seatDetailType": "000",
                "is_jy": "N",
                "is_cj": "Y",
                "encryptedData": "",
                "whatsSelect": "1",
                "roomType": "00",
                "dwAll": "N",
                "_json_att": "",
                "REPEAT_SUBMIT_TOKEN": self._order_token.repeat_submit_token
            }
            
            response = await client.post(self.CONFIRM_ORDER_URL, data=data)
            result = response.json()
            
            if result.get("status") and result.get("data", {}).get("submitStatus"):
                return True, ""
            else:
                error_msg = result.get("data", {}).get("errMsg", "")
                if not error_msg:
                    messages = result.get("messages", [])
                    error_msg = messages[0] if messages else "提交失败"
                return False, error_msg
                
        except Exception as e:
            return False, str(e)
    
    # ==================== 7. 查询订单结果 ====================
    
    async def query_order_wait_time(self, max_wait: int = 60) -> OrderResult:
        """轮询查询订单结果"""
        if not self._order_token:
            return OrderResult(success=False, message="请先调用 init_dc()")
        
        client = await self.get_client()
        start_time = time.time()
        
        while time.time() - start_time < max_wait:
            try:
                params = {
                    "random": str(int(time.time() * 1000)),
                    "tourFlag": self._order_token.tour_flag,
                    "_json_att": "",
                    "REPEAT_SUBMIT_TOKEN": self._order_token.repeat_submit_token
                }
                
                response = await client.get(self.QUERY_ORDER_URL, params=params)
                result = response.json()
                
                if result.get("status") and result.get("data"):
                    data = result["data"]
                    
                    wait_time = data.get("waitTime", -1)
                    order_id = data.get("orderId", "")
                    
                    if order_id:
                        return OrderResult(
                            success=True,
                            order_id=order_id,
                            message="订单已生成，待支付"
                        )
                    
                    if wait_time in (-1, -2, -3):
                        error_msg = data.get("msg") or data.get("errorMsg") or "订单未生成，请核对乘车人票种和官方未完成订单"
                        return OrderResult(success=False, message=error_msg, requires_review=wait_time == -1)
                    
                else:
                    messages = result.get("messages", [])
                    if messages:
                        return OrderResult(success=False, message=messages[0])
                
            except (ValueError, httpx.HTTPError):
                return OrderResult(success=False, message="订单结果暂无法确认，请先核对官方未完成订单，避免重复提交", requires_review=True)
            
            await asyncio.sleep(1)
        
        return OrderResult(success=False, message="等待超时，请核对官方未完成订单后再继续", requires_review=True)
    
    # ==================== 完整购票流程 ====================
    
    async def buy_ticket(
        self,
        train_info: TrainInfo,
        secret_str: str,
        passengers: List[Passenger] = None,
        passenger_indices: List[int] = None,
        seat_type: str = "O",
        choose_seats: str = "",
        on_progress: Optional[Callable[[str], None]] = None,
    ) -> OrderResult:
        """
        完整购票流程
        
        Args:
            train_info: 车次信息
            secret_str: 加密字符串
            passengers: 乘车人列表
            passenger_indices: 乘车人索引
            seat_type: 席别代码
            ticket_type: 票种代码 (已废弃，使用 passenger.ticket_type)
            choose_seats: 选座
        """
        # 1. 提交订单请求
        progress = on_progress or (lambda message: None)
        progress("正在提交车票预订请求")
        success, error = await self.submit_order_request(train_info, secret_str)
        if not success:
            return OrderResult(success=False, message=f"提交订单失败: {error}")
        
        # 2. 初始化订单页面
        progress("正在初始化订单信息")
        success, error = await self.init_dc()
        if not success:
            return OrderResult(success=False, message=f"初始化订单失败: {error}")
        
        # 3. 获取乘车人
        if passengers is None:
            all_passengers, error = await self.get_passengers()
            if not all_passengers:
                return OrderResult(success=False, message=f"获取乘车人失败: {error}")
            
            if passenger_indices:
                passengers = [all_passengers[i] for i in passenger_indices if i < len(all_passengers)]
            else:
                passengers = [all_passengers[0]]
        
        if not passengers:
            return OrderResult(success=False, message="未选择乘车人")
        
        # 4. 校验订单
        progress("正在核验乘车人、席别和票种")
        success, error = await self.check_order_info(passengers, seat_type)
        if not success:
            return OrderResult(success=False, message=f"订单校验失败: {error}")
        
        # 5. 获取排队信息
        progress("正在查询订单排队情况")
        await self.get_queue_count(seat_type)
        
        # 6. 确认订单
        progress("正在确认订单，请等待结果")
        success, error = await self.confirm_order(passengers, seat_type, choose_seats)
        if not success:
            return OrderResult(success=False, message=f"确认订单失败: {error}")
        
        # 7. 等待出票结果
        progress("订单已进入结果查询，正在等待订单号")
        return await self.query_order_wait_time()


# 席别代码映射
SEAT_TYPE_MAP = {
    "商务座": "9",
    "优选一等座": "P",
    "一等座": "M",
    "二等座": "O",
    "高级软卧": "6",
    "软卧": "4",
    "一等卧": "I",
    "二等卧": "J",
    "硬卧": "3",
    "软座": "2",
    "硬座": "1",
    "无座": "O",
}


def get_seat_type_code(seat_name: str) -> str:
    """根据席别名称获取代码"""
    return SEAT_TYPE_MAP.get(seat_name, "O")
