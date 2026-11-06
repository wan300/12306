#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
查票服务

封装 12306 查票逻辑，提供标准化的查询接口
"""

import re
import json
import asyncio
from datetime import datetime, timedelta
from typing import Dict, List, Optional, Tuple
from dataclasses import dataclass, asdict, field
from pathlib import Path
import httpx

from ..core.config import get_settings
from .ticket_prices import decode_price_segments, parse_price_response

settings = get_settings()


@dataclass
class Station:
    """车站信息"""
    name: str
    code: str
    pinyin: str
    short_pinyin: str
    city: str = ""


@dataclass 
class TrainInfo:
    """车次信息"""
    train_no: str
    train_code: str
    start_station: str
    end_station: str
    from_station: str
    to_station: str
    from_station_code: str
    to_station_code: str
    start_time: str
    arrive_time: str
    duration: str
    can_buy: bool
    train_date: str
    
    # 各席别余票
    business_seat: str = "--"
    premier_first: str = "--"
    first_seat: str = "--"
    second_seat: str = "--"
    advanced_soft_sleeper: str = "--"
    soft_sleeper: str = "--"
    hard_sleeper: str = "--"
    soft_seat: str = "--"
    hard_seat: str = "--"
    no_seat: str = "--"
    first_sleeper: str = "--"
    second_sleeper: str = "--"
    
    is_support_card: bool = False
    remark: str = ""
    secret_str: str = ""
    from_station_no: str = ""
    to_station_no: str = ""
    seat_types: str = ""
    prices_fen: Dict[str, int] = field(default_factory=dict)
    price_source: str = ""
    waitlist_supported: bool = False
    waitlist_restricted_seats: str = ""
    preferred_first_seat: str = "--"
    moving_sleeper: str = "--"
    quiet_carriage: bool = False
    
    def to_dict(self) -> dict:
        """转换为字典"""
        return asdict(self)


class StationManager:
    """车站管理器（单例）"""
    
    _instance: Optional["StationManager"] = None
    _stations: Dict[str, Station] = {}
    _code_to_name: Dict[str, str] = {}
    _name_to_code: Dict[str, str] = {}
    _loaded: bool = False
    
    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance
    
    def load_from_file(self, filepath: str) -> bool:
        """从 station_name.js 文件加载站点数据"""
        if StationManager._loaded:
            return True
        
        try:
            with open(filepath, 'r', encoding='utf-8') as f:
                content = f.read()
            self._parse_stations(content)
            StationManager._loaded = True
            return True
        except Exception as e:
            print(f"加载站点文件失败: {e}")
            return False
    
    def _parse_stations(self, content: str):
        """解析站点数据"""
        match = re.search(r"var station_names\s*=\s*'([^']+)'", content)
        if match:
            content = match.group(1)
        
        parts = content.split('@')
        
        for part in parts:
            if not part.strip():
                continue
            
            fields = part.split('|')
            if len(fields) >= 7:
                try:
                    short_py = fields[0]
                    name = fields[1]
                    code = fields[2]
                    pinyin = fields[3]
                    city = fields[7] if len(fields) > 7 else ""
                    
                    station = Station(
                        name=name,
                        code=code,
                        pinyin=pinyin,
                        short_pinyin=short_py,
                        city=city
                    )
                    
                    StationManager._stations[name] = station
                    StationManager._code_to_name[code] = name
                    StationManager._name_to_code[name] = code
                    
                except Exception:
                    continue
    
    def get_station_code(self, name: str) -> Optional[str]:
        """根据站名获取电报码"""
        return StationManager._name_to_code.get(name)
    
    def get_station_name(self, code: str) -> Optional[str]:
        """根据电报码获取站名"""
        return StationManager._code_to_name.get(code)
    
    def search_station(self, keyword: str, limit: int = 20) -> List[Station]:
        """搜索站点"""
        results = []
        keyword = keyword.lower()
        
        for name, station in StationManager._stations.items():
            if (keyword in name.lower() or 
                keyword in station.pinyin.lower() or
                keyword in station.short_pinyin.lower()):
                results.append(station)
                if len(results) >= limit:
                    break
        
        return results
    
    def get_all_stations(self) -> List[Station]:
        """获取所有站点"""
        return list(StationManager._stations.values())


class QueryService:
    """查票服务"""
    
    BASE_URL = "https://kyfw.12306.cn"
    
    HEADERS = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept": "application/json, text/javascript, */*; q=0.01",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        "Accept-Encoding": "gzip, deflate, br",
        "Referer": "https://kyfw.12306.cn/otn/leftTicket/init",
        "X-Requested-With": "XMLHttpRequest",
    }
    
    def __init__(self, cookies: Dict[str, str] = None):
        """
        初始化查票服务
        
        Args:
            cookies: 登录后的 cookies（可选，部分查询需要）
        """
        self._cookies = cookies or {}
        self._client: Optional[httpx.AsyncClient] = None
        self._query_url: Optional[str] = None
        
        # 初始化车站管理器
        self.station_manager = StationManager()
        station_file = Path(settings.STATION_FILE)
        if station_file.exists():
            self.station_manager.load_from_file(str(station_file))
    
    async def get_client(self) -> httpx.AsyncClient:
        """获取异步 HTTP 客户端"""
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(
                headers=self.HEADERS,
                timeout=15.0,
                verify=False,
                follow_redirects=True
            )
            if self._cookies:
                self._client.cookies.update(self._cookies)
        return self._client
    
    async def close(self):
        """关闭客户端连接"""
        if self._client:
            await self._client.aclose()
            self._client = None
    
    async def _get_query_url(self) -> str:
        """获取实际的查询 URL"""
        if self._query_url:
            return self._query_url
        
        client = await self.get_client()
        
        try:
            init_url = f"{self.BASE_URL}/otn/leftTicket/init"
            resp = await client.get(init_url)
            
            match = re.search(r"var CLeftTicketUrl\s*=\s*'([^']+)'", resp.text)
            if match:
                self._query_url = match.group(1)
            else:
                self._query_url = "leftTicket/queryG"
        except Exception:
            self._query_url = "leftTicket/queryG"
        
        return self._query_url
    
    async def query(
        self,
        from_station: str,
        to_station: str,
        train_date: str,
        ticket_type: str = "ADULT",
        train_types: List[str] = None,
        seat_types: List[str] = None,
        start_time_range: Tuple[str, str] = None,
        only_has_ticket: bool = False
    ) -> Tuple[List[TrainInfo], str]:
        """
        查询余票
        
        Args:
            from_station: 出发站
            to_station: 到达站
            train_date: 出发日期 (YYYY-MM-DD)
            ticket_type: 票种 (ADULT/0X00)
            train_types: 车次类型筛选
            seat_types: 席别筛选
            start_time_range: 出发时间范围
            only_has_ticket: 只显示有票车次
            
        Returns:
            (trains, error_message)
        """
        # 转换站名为代码
        from_code = self._get_station_code(from_station)
        to_code = self._get_station_code(to_station)
        
        if not from_code:
            return [], f"未找到出发站: {from_station}"
        if not to_code:
            return [], f"未找到到达站: {to_station}"
        
        # 验证日期
        try:
            date_obj = datetime.strptime(train_date, "%Y-%m-%d")
            today = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
            if date_obj < today:
                return [], "出发日期不能早于今天"
            if date_obj > today + timedelta(days=14):
                return [], "出发日期不能超过15天"
        except ValueError:
            return [], f"日期格式错误: {train_date}"
        
        # 执行查询
        client = await self.get_client()
        query_url = await self._get_query_url()
        url = f"{self.BASE_URL}/otn/{query_url}"
        
        params = {
            "leftTicketDTO.train_date": train_date,
            "leftTicketDTO.from_station": from_code,
            "leftTicketDTO.to_station": to_code,
            "purpose_codes": ticket_type
        }
        
        try:
            resp = await client.get(url, params=params)
            resp.raise_for_status()
            data = resp.json()
        except httpx.RequestError as e:
            return [], f"查询请求失败: {e}"
        except json.JSONDecodeError:
            return [], "解析响应失败"
        
        # 解析结果
        trains = self._parse_response(data, train_date)
        
        # 应用筛选条件
        trains = self._filter_trains(
            trains,
            train_types=train_types,
            seat_types=seat_types,
            start_time_range=start_time_range,
            only_has_ticket=only_has_ticket
        )
        
        return trains, ""
    
    def _get_station_code(self, station: str) -> Optional[str]:
        """获取站点代码"""
        if len(station) == 3 and station.isupper():
            return station
        return self.station_manager.get_station_code(station)
    
    def _parse_response(self, data: dict, train_date: str) -> List[TrainInfo]:
        """解析查询响应"""
        trains = []
        
        if not data.get("status"):
            return trains
        
        result = data.get("data", {}).get("result", [])
        station_map = data.get("data", {}).get("map", {})
        
        for item in result:
            try:
                train = self._parse_train_item(item, station_map, train_date)
                if train:
                    trains.append(train)
            except Exception:
                continue
        
        return trains
    
    def _parse_train_item(self, item: str, station_map: dict, train_date: str) -> Optional[TrainInfo]:
        """解析单条车次信息"""
        parts = item.split("|")
        if len(parts) < 35:
            return None
        
        secret_str = parts[0]
        train_no = parts[2]
        train_code = parts[3]
        start_station_code = parts[4]
        end_station_code = parts[5]
        from_station_code = parts[6]
        to_station_code = parts[7]
        start_time = parts[8]
        arrive_time = parts[9]
        duration = parts[10]
        can_buy = parts[11] == "Y"
        
        from_station = station_map.get(from_station_code, from_station_code)
        to_station = station_map.get(to_station_code, to_station_code)
        start_station = station_map.get(start_station_code, start_station_code)
        end_station = station_map.get(end_station_code, end_station_code)
        
        # 席别信息
        def get_seat(idx: int) -> str:
            if idx < len(parts):
                val = parts[idx]
                return val if val and val != "" else "--"
            return "--"
        
        return TrainInfo(
            train_no=train_no,
            train_code=train_code,
            start_station=start_station,
            end_station=end_station,
            from_station=from_station,
            to_station=to_station,
            from_station_code=from_station_code,
            to_station_code=to_station_code,
            start_time=start_time,
            arrive_time=arrive_time,
            duration=duration,
            can_buy=can_buy,
            train_date=train_date,
            business_seat=get_seat(32) if len(parts) > 32 else "--",
            premier_first=get_seat(25) if len(parts) > 25 else "--",
            first_seat=get_seat(31) if len(parts) > 31 else "--",
            second_seat=get_seat(30) if len(parts) > 30 else "--",
            advanced_soft_sleeper=get_seat(21) if len(parts) > 21 else "--",
            soft_sleeper=get_seat(23) if len(parts) > 23 else "--",
            hard_sleeper=get_seat(28) if len(parts) > 28 else "--",
            soft_seat=get_seat(24) if len(parts) > 24 else "--",
            hard_seat=get_seat(29) if len(parts) > 29 else "--",
            no_seat=get_seat(26) if len(parts) > 26 else "--",
            preferred_first_seat=get_seat(20),
            moving_sleeper=get_seat(33),
            first_sleeper=get_seat(23) if len(parts) > 35 and "I" in parts[35] else "--",
            second_sleeper=get_seat(28) if len(parts) > 35 and "J" in parts[35] else "--",
            is_support_card=parts[18] == "1" if len(parts) > 18 else False,
            remark=parts[1] if len(parts) > 1 else "",
            secret_str=secret_str,
            from_station_no=parts[16],
            to_station_no=parts[17],
            seat_types=parts[35] if len(parts) > 35 else "",
            prices_fen=decode_price_segments(parts[39] if len(parts) > 39 else ""),
            price_source="leftTicket/yp_info_new" if len(parts) > 39 and decode_price_segments(parts[39]) else "",
            waitlist_supported=(len(parts) > 37 and parts[37] == "1" and bool(secret_str) and secret_str != "null"),
            waitlist_restricted_seats=parts[38] if len(parts) > 38 else "",
            quiet_carriage=(len(parts) > 46 and len(parts[46].split("#")) > 2 and parts[46].split("#")[2].startswith("Q")),
        )

    async def query_prices(self, train_no: str, from_station_no: str,
                           to_station_no: str, seat_types: str, train_date: str) -> dict:
        """Refresh one train/segment/date quote; never turn missing data into zero."""
        fail = lambda code, message: {"success": False, "code": code, "message": message,
                                      "data": {"prices_fen": {}}}
        if not all(isinstance(v, str) for v in (train_no, from_station_no, to_station_no, seat_types, train_date)):
            return fail("INVALID_PRICE_REQUEST", "车次信息不完整，请重新查询车票")
        if not (re.fullmatch(r"[A-Za-z0-9]{6,32}", train_no or "")
                and re.fullmatch(r"[0-9]{1,3}", from_station_no or "")
                and re.fullmatch(r"[0-9]{1,3}", to_station_no or "")
                and int(from_station_no) < int(to_station_no)
                and re.fullmatch(r"[A-Za-z0-9]{1,32}", seat_types or "")
                and re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", train_date or "")):
            return fail("INVALID_PRICE_REQUEST", "车次信息不完整，请重新查询车票")
        try:
            date = datetime.strptime(train_date, "%Y-%m-%d").date()
            if not 0 <= (date - datetime.now().date()).days <= 14:
                return fail("INVALID_PRICE_DATE", "出发日期不可用，请重新选择")
        except ValueError:
            return fail("INVALID_PRICE_DATE", "出发日期不可用，请重新选择")
        try:
            client = await self.get_client()
            response = await client.get(f"{self.BASE_URL}/otn/leftTicket/queryTicketPrice", params={
                "train_no": train_no, "from_station_no": from_station_no,
                "to_station_no": to_station_no, "seat_types": seat_types, "train_date": train_date,
            })
            response.raise_for_status()
            payload = response.json()
            prices = parse_price_response(payload.get("data")) if payload.get("status") is True else {}
            if not prices:
                return fail("PRICE_UNAVAILABLE", "暂未获取到票价，请稍后重试")
            return {"success": True, "code": "OK", "message": "", "data": {
                "train_no": train_no, "from_station_no": from_station_no,
                "to_station_no": to_station_no, "train_date": train_date,
                "prices_fen": prices, "price_source": "leftTicket/queryTicketPrice",
            }}
        except (httpx.HTTPError, ValueError, TypeError, AttributeError):
            return fail("PRICE_QUERY_FAILED", "票价查询失败，请稍后重试")
    
    def _filter_trains(
        self,
        trains: List[TrainInfo],
        train_types: List[str] = None,
        seat_types: List[str] = None,
        start_time_range: Tuple[str, str] = None,
        only_has_ticket: bool = False
    ) -> List[TrainInfo]:
        """应用筛选条件"""
        result = trains
        
        # 车次类型筛选
        if train_types:
            result = [t for t in result if t.train_code[0] in train_types]
        
        # 时间范围筛选
        if start_time_range:
            start_min, start_max = start_time_range
            result = [
                t for t in result
                if start_min <= t.start_time <= start_max
            ]
        
        # 只显示有票
        if only_has_ticket:
            def has_ticket(train: TrainInfo) -> bool:
                seat_fields = [
                    train.business_seat, train.premier_first, train.first_seat,
                    train.second_seat, train.soft_sleeper, train.hard_sleeper,
                    train.soft_seat, train.hard_seat, train.no_seat
                ]
                for seat in seat_fields:
                    if seat and seat not in ("--", "无", "*", ""):
                        try:
                            if int(seat) > 0:
                                return True
                        except ValueError:
                            if seat == "有":
                                return True
                return False
            
            result = [t for t in result if has_ticket(t)]
        
        return result
    
    def search_stations(self, keyword: str, limit: int = 20) -> List[Station]:
        """搜索车站"""
        return self.station_manager.search_station(keyword, limit)
