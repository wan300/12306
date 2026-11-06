"""12306 waitlist preparation, explicit submission and status (no payment API).

Opaque query tokens and complete passenger records live only in this short-lived
service. UI callers select opaque passenger IDs, never construct order strings.
"""

import asyncio
import logging
import re
import time
import uuid
from datetime import datetime, timedelta

import httpx

from .order_service import OrderService
from .query_service import QueryService
from .ticket_prices import parse_yuan


def result(ok, code, message="", data=None):
    return {"success": ok, "code": code, "message": message, "data": data or {}}


class WaitlistFailure(Exception):
    def __init__(self, code, message):
        super().__init__(code)
        self.code, self.message = code, message


def upstream_failure(payload):
    data = payload.get("data") if isinstance(payload, dict) else None
    data = data if isinstance(data, dict) else {}
    # Classify upstream text; never return it (it may contain identities/tokens).
    text = str(payload.get("messages", "")) + str(data.get("msg", ""))
    if data.get("login_flag") is False or payload.get("validateMessagesShowId") == "_validatorMessage_login" or any(x in text for x in ("未登录", "登录已", "重新登录")):
        return WaitlistFailure("LOGIN_REQUIRED", "请先登录12306账号，再办理候补")
    if data.get("face_flag") is False or any(x in text for x in ("人证", "人脸", "身份核验", "人证一致")):
        return WaitlistFailure("IDENTITY_REQUIRED", "请在铁路12306完成人证核验后重试")
    if any(x in text for x in ("验证码", "滑块", "验证失败", "安全验证")):
        return WaitlistFailure("VERIFICATION_REQUIRED", "本次候补需要安全验证，请在铁路12306完成后重试")
    if any(x in text for x in ("服务时间", "营业时间", "维护", "23:00", "5:00")):
        return WaitlistFailure("SERVICE_UNAVAILABLE", "当前候补服务暂不可用，请在服务时间内重试")
    return WaitlistFailure("WAITLIST_REJECTED", "12306暂未受理本次候补，请刷新车次或稍后重试")


def validate_choices(choices, now=None):
    if not isinstance(choices, list) or not choices or len(choices) > 600:
        raise WaitlistFailure("INVALID_SELECTION", "请选择候补车次和席别")
    now = now or datetime.now()
    dates, trains, keys = set(), set(), set()
    clean = []
    for item in choices:
        if not isinstance(item, dict):
            raise WaitlistFailure("INVALID_SELECTION", "候补车次信息不完整，请重新选择")
        names = ("date", "train_no", "from_code", "to_code", "seat_code")
        if any(not isinstance(item.get(key), str) for key in names):
            raise WaitlistFailure("INVALID_SELECTION", "候补车次信息不完整，请重新选择")
        date, train, origin, destination, seat = (item[key] for key in names)
        try:
            valid_date = bool(re.fullmatch(r"\d{4}-\d{2}-\d{2}", date)) and 0 <= (datetime.strptime(date, "%Y-%m-%d").date() - now.date()).days <= 14
        except ValueError:
            valid_date = False
        if not valid_date or not re.fullmatch(r"[A-Za-z0-9]{6,32}", train) or not re.fullmatch(r"[A-Z]{3}", origin) or not re.fullmatch(r"[A-Z]{3}", destination) or origin == destination or seat not in set("OM9P64321IJFSAD"):
            raise WaitlistFailure("INVALID_SELECTION", "候补日期、车次或席别不可用，请重新选择")
        key = (date, train, origin, destination, seat)
        if key in keys:
            continue
        keys.add(key); dates.add(date); trains.add((date, train))
        clean.append({key: item[key] for key in names})
    if len(dates) > 3 or len(trains) > 60:
        raise WaitlistFailure("SELECTION_LIMIT", "每份候补订单最多选择3个日期、60个车次")
    return clean


class WaitlistService:
    BASE = "https://kyfw.12306.cn/otn/"
    TTL = 10 * 60

    def __init__(self, cookies, *, client=None, query_factory=QueryService):
        self.cookies = cookies
        self.client = client
        self.query_factory = query_factory
        self.created = time.monotonic()
        self.session_id = uuid.uuid4().hex
        self.passengers = {}
        self.trains = []
        self.deadlines = []
        self.passenger_limit = 0
        self.allow_standing = False
        self.prepared = False
        self.submission_attempted = False
        self.pending_order_data = None
        self.lock = asyncio.Lock()

    async def close(self):
        if self.client:
            await self.client.aclose()
        self.passengers.clear()

    async def _post(self, path, data=None):
        if not self.cookies:
            raise WaitlistFailure("LOGIN_REQUIRED", "请先登录12306账号，再办理候补")
        if self.client is None:
            self.client = httpx.AsyncClient(headers=OrderService.HEADERS, cookies=self.cookies,
                                           timeout=20, follow_redirects=True)
        referer = "view/lineUp_order.html" if path.startswith("afterNateOrder/") else (
            "leftTicket/init" if path in ("afterNate/chechFace", "afterNate/submitOrderRequest") else "view/lineUp_toPay.html")
        response = await self.client.post(self.BASE + path, data=data or {}, headers={"Referer": self.BASE + referer})
        response.raise_for_status()
        if "login" in response.url.path.lower() or response.url.path.rstrip("/") == "/otn/passport":
            raise WaitlistFailure("LOGIN_REQUIRED", "登录已过期，请重新登录12306账号")
        if response.url.path == "/mormhweb/logFiles/error.html":
            raise WaitlistFailure("ENDPOINT_UNAVAILABLE", "候补接口暂不可用，请稍后重试或在铁路12306查看")
        try:
            payload = response.json()
        except ValueError:
            logging.getLogger(__name__).warning("waitlist_response endpoint=%s http=%s format=non_json", path, response.status_code)
            raise WaitlistFailure("WAITLIST_UNAVAILABLE", "候补服务暂不可用，请稍后重试") from None
        if not isinstance(payload, dict):
            raise WaitlistFailure("WAITLIST_UNAVAILABLE", "候补服务暂不可用，请稍后重试")
        if payload.get("status") is not True:
            logging.getLogger(__name__).warning("waitlist_rejected endpoint=%s stage=envelope", path)
            raise upstream_failure(payload)
        if not isinstance(payload.get("data"), dict):
            logging.getLogger(__name__).warning("waitlist_response endpoint=%s http=%s data_type=%s", path, response.status_code, type(payload.get("data")).__name__)
            raise WaitlistFailure("WAITLIST_UNAVAILABLE", "候补服务暂不可用，请稍后重试")
        if path in ("afterNate/chechFace", "afterNate/submitOrderRequest"):
            data = payload["data"]
            logging.getLogger(__name__).warning("waitlist_gate endpoint=%s flags=%s", path,
                {key: data.get(key) if isinstance(data.get(key), bool) or data.get(key) in ("true", "false", "Y", "N", "1", "0") else type(data.get(key)).__name__ for key in ("login_flag", "face_flag", "flag")})
        return payload

    async def prepare(self, choices):
        async with self.lock:
            try:
                clean = validate_choices(choices)
                if not self.cookies:
                    raise WaitlistFailure("LOGIN_REQUIRED", "请先登录12306账号，再办理候补")
                if self.pending_order_data:
                    return result(False, "EXISTING_ORDER", "当前有待支付候补订单，请先核对", self.pending_order_data)
                # The official flow checks eligibility before preparing the draft.
                # Submission queue / pay-page data are session-scoped and cannot
                # serve as an account preflight for a fresh, unsubmitted session.
                queries, secret_list = {}, []
                query = self.query_factory(self.cookies)
                try:
                    for choice in clean:
                        route = (choice["date"], choice["from_code"], choice["to_code"])
                        if route not in queries:
                            trains, error = await query.query(route[1], route[2], route[0])
                            if error:
                                raise WaitlistFailure("REQUERY_FAILED", "无法刷新候补余票，请稍后重试")
                            queries[route] = trains
                        train = next((t for t in queries[route] if t.train_no == choice["train_no"] and t.from_station_code == route[1] and t.to_station_code == route[2]), None)
                        seat_field = {"O": "second_seat", "M": "first_seat", "9": "business_seat", "P": "premier_first", "D": "preferred_first_seat", "6": "advanced_soft_sleeper", "A": "advanced_soft_sleeper", "4": "soft_sleeper", "3": "hard_sleeper", "2": "soft_seat", "1": "hard_seat", "I": "first_sleeper", "J": "second_sleeper", "F": "moving_sleeper", "S": "second_seat"}.get(choice["seat_code"])
                        if not train or not seat_field or not train.waitlist_supported or getattr(train, seat_field, "--") not in ("无", "0") or choice["seat_code"] in train.waitlist_restricted_seats:
                            raise WaitlistFailure("SELECTION_CHANGED", "所选席别的余票或候补状态已变化，请重新选择")
                        departure = datetime.strptime(choice["date"] + " " + train.start_time, "%Y-%m-%d %H:%M")
                        if departure <= datetime.now() + timedelta(minutes=20):
                            raise WaitlistFailure("TOO_LATE", "所选车次已超过候补办理时间，请重新选择")
                        # Official waitlist AJAX sends secretStr unchanged as a
                        # form value; decoding it here corrupts the opaque token.
                        secret_list.append(train.secret_str + "#" + choice["seat_code"] + "|")
                finally:
                    await query.close()
                face = await self._post("afterNate/chechFace", {"secretList": "".join(secret_list)})
                # Official chechFace flow treats only an explicit false face_flag
                # as a verification gate; verified members may omit this field.
                if face["data"].get("login_flag") is not True or face["data"].get("face_flag") is False:
                    raise upstream_failure(face)
                selected = await self._post("afterNate/submitOrderRequest", {"secretList": "".join(secret_list)})
                if selected["data"].get("flag") is not True:
                    raise upstream_failure(selected)
                init = (await self._post("afterNate/passengerInitApi"))["data"]
                if str(init.get("if_check_slide_passcode", "0")) != "0" or str(init.get("checkcode", "")) == "94":
                    raise WaitlistFailure("VERIFICATION_REQUIRED", "本次候补需要安全验证，请在铁路12306完成后重试")
                self.trains = init.get("hbTrainList", [])
                if not isinstance(self.trains, list) or any(not isinstance(t, dict) for t in self.trains):
                    raise WaitlistFailure("WAITLIST_UNAVAILABLE", "候补清单暂不可用，请稍后重试")
                actual = {(t.get("train_date"), t.get("train_no"), t.get("from_station_telecode"), t.get("to_station_telecode"), t.get("seat_type_code")) for t in self.trains}
                expected = {(c["date"], c["train_no"], c["from_code"], c["to_code"], c["seat_code"]) for c in clean}
                if actual != expected:
                    raise WaitlistFailure("SELECTION_CHANGED", "服务端候补清单已变化，请重新选择")
                self.deadlines = [str(v) for v in init.get("jzdhDiffSelect", []) if str(v).isdigit() and int(v) >= 20]
                self.passenger_limit = min(int(init.get("hb_passenger_max_num", 19)), 19)
                self.allow_standing = init.get("canChooseWseat") == "Y"
                if not self.deadlines or self.passenger_limit < 1:
                    raise WaitlistFailure("WAITLIST_UNAVAILABLE", "暂未获取到候补办理条件，请稍后重试")
                info = (await self._post("confirmPassenger/getPassengerDTOs"))["data"]
                self.passengers = {uuid.uuid4().hex: p for p in info.get("normal_passengers", [])
                                   if isinstance(p, dict) and p.get("allEncStr") and p.get("passenger_name") and p.get("passenger_id_no")}
                self.prepared = True
                self.created = time.monotonic()
                return result(True, "READY", data={"session_id": self.session_id,
                    "deadline_minutes": self.deadlines, "max_passengers": self.passenger_limit,
                    "allow_standing": self.allow_standing,
                    "passengers": [{"id": key, "name": p["passenger_name"],
                        "masked_id": p["passenger_id_no"][:3] + "********" + p["passenger_id_no"][-4:],
                        "ticket_type": p.get("passenger_type", "1")} for key, p in self.passengers.items()]})
            except WaitlistFailure as exc:
                return result(False, exc.code, exc.message)
            except (httpx.HTTPError, ValueError, TypeError, KeyError, AttributeError):
                return result(False, "WAITLIST_PREPARE_FAILED", "无法获取候补信息，请稍后重试")

    async def submit(self, passenger_ids, deadline_minutes, accept_standing=False, new_trains=None):
        async with self.lock:
            if not self.prepared or time.monotonic() - self.created > self.TTL:
                return result(False, "SESSION_EXPIRED", "候补信息已过期，请返回清单重新确认")
            if self.submission_attempted:
                return result(False, "ALREADY_SUBMITTED", "已发送过提交请求，请刷新订单状态，勿重复提交", {"state": "processing"})
            if not isinstance(passenger_ids, list) or not 1 <= len(passenger_ids) <= self.passenger_limit or any(not isinstance(p, str) for p in passenger_ids) or len(set(passenger_ids)) != len(passenger_ids) or any(p not in self.passengers for p in passenger_ids):
                return result(False, "INVALID_PASSENGERS", "请重新选择有效乘车人")
            if str(deadline_minutes) not in self.deadlines:
                return result(False, "INVALID_DEADLINE", "请选择有效的截止兑现时间")
            if accept_standing and not self.allow_standing:
                return result(False, "INVALID_STANDING_OPTION", "当前候补订单不支持接受无座")
            from .waitlist_details import new_train_form
            try:
                extra_train_fields = new_train_form(new_trains, self.trains)
            except ValueError:
                return result(False, "INVALID_NEW_TRAIN_OPTIONS", "请核对新增列车的日期、席别和出发时段")
            rows = []
            for key in passenger_ids:
                p = self.passengers[key]
                values = [str(p.get("passenger_type", "1")), str(p["passenger_name"]), str(p.get("passenger_id_type_code", "1")), str(p["passenger_id_no"]), str(p["allEncStr"]), "0"]
                if values[0] not in ("1", "2", "3", "4") or any(any(c in v for c in "#;|") for v in values):
                    return result(False, "INVALID_PASSENGERS", "乘车人信息需要核验，请在铁路12306完善后重试")
                rows.append("#".join(values) + ";")
            data = {"passengerInfo": "".join(rows), "jzParam": "",
                    "hbTrain": "".join(t["train_no"] + "#" for t in self.trains), "lkParam": "",
                    "sessionId": "", "sig": "", "scene": "nc_login", "encryptedData": "",
                    "if_receive_wseat": "Y" if accept_standing else "N",
                    "realize_limit_time_diff": str(deadline_minutes), "plans": "",
                    "tmp_train_date": "", "tmp_train_time": "", "add_train_flag": "N",
                    "add_train_seat_type_code": ""}
            data.update(extra_train_fields)
            if any(self.passengers[key].get("passenger_type") == "2" for key in passenger_ids):
                data["kid_seat_flag"] = "N"
            self.submission_attempted = True  # A timeout is ambiguous; never resend automatically.
            try:
                current = (await self._post("afterNate/passengerInitApi"))["data"]
                fields = ("train_date", "train_no", "from_station_telecode", "to_station_telecode", "seat_type_code")
                expected = {tuple(t.get(k) for k in fields) for t in self.trains}
                actual = {tuple(t.get(k) for k in fields) for t in current.get("hbTrainList", [])}
                if expected != actual:
                    raise WaitlistFailure("SELECTION_CHANGED", "服务端候补清单已变化，请返回重新确认")
                if str(current.get("if_check_slide_passcode", "0")) != "0" or str(current.get("checkcode", "")) == "94":
                    raise WaitlistFailure("VERIFICATION_REQUIRED", "本次候补需要安全验证，请在铁路12306完成后重试")
                response = await self._post("afterNate/confirmHB", data)
                if response["data"].get("flag") is not True or response["data"].get("msg"):
                    raise upstream_failure(response)
                return result(True, "SUBMITTED", "候补请求已提交，正在查询订单状态", {"state": "processing"})
            except WaitlistFailure as exc:
                return result(False, exc.code, exc.message, {"state": "rejected"})
            except (httpx.HTTPError, ValueError, TypeError, AttributeError):
                return result(False, "SUBMISSION_UNKNOWN", "提交结果尚未确认，请刷新订单状态，勿重复提交", {"state": "unknown"})

    async def status(self):
        try:
            # Account order lookup also recovers an ambiguous submission after app restart.
            # This is read-only; it never enters a payment session or initiates payment.
            queue = (await self._post("afterNate/queryQueue"))["data"]
            logging.getLogger(__name__).warning(
                "waitlist_queue flag=%s async=%s status=%s has_order=%s",
                queue.get("flag") if isinstance(queue.get("flag"), bool) else "missing",
                queue.get("isAsync") if isinstance(queue.get("isAsync"), bool) else "missing",
                queue.get("status") if str(queue.get("status", "")).lstrip("-").isdigit() else "missing",
                isinstance(queue.get("order"), dict))
            if queue.get("flag") is True and str(queue.get("status")) == "0":
                return result(True, "PROCESSING", "候补请求正在处理中", {"state": "processing"})
            if str(queue.get("status")) == "-1":
                return result(False, "QUEUE_FAILED", "候补未成功受理，请在铁路12306核对订单状态", {"state": "failed"})
            if queue.get("isAsync") is False and queue.get("flag") is False:
                return result(True, "NO_PENDING_ORDER", "当前账号暂无待支付候补订单", {"state": "none"})
            if (queue.get("isAsync") is False and "flag" not in queue) or str(queue.get("status")) == "2":
                page = (await self._post("afterNatePay/payOrderInit"))["data"]
                logging.getLogger(__name__).warning("waitlist_pending_page has_order=%s flag=%s empty=%s", isinstance(page.get("order"), dict), page.get("flag") if isinstance(page.get("flag"), bool) else "missing", not page)
                logging.getLogger(__name__).warning("waitlist_pending_shape order_type=%s fields=%s", type(page.get("order")).__name__, ",".join(k for k in ("order", "flag", "msg", "loseTime", "beginTime", "wait_pay_time") if k in page))
                message = str(page.get("msg", ""))
                no_order = any(term in message for term in ("无待支付", "没有待支付", "无未支付", "没有未支付", "无未完成", "没有未完成", "未查询到", "订单不存在"))
                logging.getLogger(__name__).warning("waitlist_pending_reason no_order=%s terms=%s", no_order, ",".join(term for term in ("订单", "没有", "不存在", "未支付", "待支付", "未完成", "查询", "提交", "错误", "繁忙", "失败", "登录") if term in message))
                if page.get("order") is None and no_order:
                    return result(True, "NO_PENDING_ORDER", "当前账号暂无待支付候补订单", {"state": "none"})
                if isinstance(page.get("order"), dict):
                    queue = dict(page, flag=True, isAsync=False)
            if queue.get("flag") is not True or (queue.get("isAsync") is not False and str(queue.get("status")) != "1"):
                return result(True, "STATUS_UNKNOWN", "订单状态尚未确认，请在铁路12306核对", {"state": "unknown"})
            order = queue.get("order")
            if not isinstance(order, dict) or not order.get("reserve_no"):
                # Official confirmation-page data only. This does not invoke
                # paycheck, a payment provider or any payment authorization.
                queue = (await self._post("afterNatePay/payOrderInit"))["data"]
                order = queue.get("order")
            if not isinstance(order, dict) or not order.get("reserve_no"):
                return result(True, "VERIFY_ORDER", "未查询到对应订单，请在铁路12306核对是否已过期或仍待支付，勿直接重复提交", {"state": "unknown"})
            # Amount and order number come only from the actual pending-payment order.
            amount = parse_yuan(str(order.get("prepay_amount", "")))
            from .waitlist_details import pending_details
            self.pending_order_data = {
                "state": "pending_payment", "order_id": str(order["reserve_no"]),
                "amount_fen": amount, "payment_deadline_ms": queue.get("loseTime"),
                "details": pending_details(order),
            }
            return result(True, "PENDING_PAYMENT", "当前账号有待支付候补订单，尚未付款", self.pending_order_data)
        except WaitlistFailure as exc:
            return result(False, exc.code, exc.message)
        except (httpx.HTTPError, ValueError, TypeError, AttributeError):
            return result(False, "STATUS_UNAVAILABLE", "暂时无法查询候补订单，请稍后刷新")
