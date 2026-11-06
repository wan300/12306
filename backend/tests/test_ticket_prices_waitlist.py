import json
import unittest
from datetime import datetime, timedelta
from types import SimpleNamespace
from unittest.mock import AsyncMock
from urllib.parse import parse_qs

import httpx

from app.services.query_service import QueryService
from app.services.ticket_prices import decode_price_segments, parse_price_response, parse_yuan
from app.services.waitlist_service import WaitlistFailure, WaitlistService, validate_choices


class PriceTests(unittest.TestCase):
    def test_preferred_first_class_and_sleeper_fields_keep_their_actual_codes(self):
        parts = [""] * 56
        for index, value in {0: "test-secret", 2: "240000G0030A", 3: "G3", 6: "VNP", 7: "SHH", 8: "06:52", 9: "11:33",
                             10: "04:41", 11: "Y", 16: "01", 17: "05", 20: "无", 23: "2", 28: "无", 35: "ODIJ9",
                             37: "1", 39: "D123450000I089900002J050000000"}.items(): parts[index] = value
        t = QueryService()._parse_train_item("|".join(parts), {}, "2026-11-03")
        self.assertEqual(t.preferred_first_seat, "无")
        self.assertEqual(t.first_sleeper, "2")
        self.assertEqual(t.second_sleeper, "无")
        self.assertEqual(t.prices_fen["D"], 123450)
        self.assertEqual(parse_price_response({"D": "¥1234.5"}), {"D": 123450})

    def test_live_format_preserves_units_and_standing_inventory(self):
        self.assertEqual(decode_price_segments("9174400005M083600000O049800000O049803001"),
                         {"9": 174400, "M": 83600, "O": 49800, "WZ": 49800})

    def test_malformed_and_missing_price_never_becomes_zero(self):
        for value in (None, "", "O04980", "O04980xxxx", "O000000000", "O049800000INVALID"):
            self.assertEqual(decode_price_segments(value), {})
        for value in (None, 498, "", "-1", "NaN", "0", "1.001", "1e3"):
            self.assertIsNone(parse_yuan(value))

    def test_formatted_price_keys_ignore_differently_scaled_numeric_aliases(self):
        self.assertEqual(parse_price_response({"A9": "¥1744.0", "9": "17440", "O": "¥498.5", "WZ": "￥498.50"}),
                         {"9": 174400, "O": 49850, "WZ": 49850})
        self.assertEqual(parse_price_response({"9": "17440"}), {})
        self.assertEqual(parse_price_response([]), {})


class PriceEndpointTests(unittest.IsolatedAsyncioTestCase):
    async def test_quote_is_bound_to_date_train_and_actual_segment(self):
        requests = []
        def handle(request):
            requests.append(request)
            return httpx.Response(200, json={"status": True, "data": {"O": "¥498.0"}})
        service = QueryService()
        client = httpx.AsyncClient(transport=httpx.MockTransport(handle), trust_env=False)
        service.get_client = AsyncMock(return_value=client)
        date = datetime.now().strftime("%Y-%m-%d")
        try:
            result = await service.query_prices("240000G5310A", "01", "08", "OM9", date)
            self.assertEqual(result["data"]["prices_fen"], {"O": 49800})
            self.assertEqual(dict(requests[0].url.params), dict(train_no="240000G5310A", from_station_no="01", to_station_no="08", seat_types="OM9", train_date=date))
            self.assertEqual(requests[0].url.path, "/otn/leftTicket/queryTicketPrice")
        finally:
            await client.aclose()

    async def test_invalid_parameters_or_sixteenth_day_do_not_hit_network(self):
        service = QueryService()
        service.get_client = AsyncMock()
        date = datetime.now().strftime("%Y-%m-%d")
        for args in (({}, "01", "08", "O", date), ("240000G5310A", "08", "01", "O", date),
                     ("240000G5310A", "01", "08", "O", (datetime.now() + timedelta(days=15)).strftime("%Y-%m-%d"))):
            self.assertFalse((await service.query_prices(*args))["success"])
        service.get_client.assert_not_awaited()

    async def test_transport_failure_has_safe_error_without_raw_exception(self):
        def fail(request):
            raise httpx.ConnectError("private-token", request=request)
        client = httpx.AsyncClient(transport=httpx.MockTransport(fail), trust_env=False)
        service = QueryService(); service.get_client = AsyncMock(return_value=client)
        try:
            result = await service.query_prices("240000G5310A", "01", "08", "O", datetime.now().strftime("%Y-%m-%d"))
            self.assertEqual(result["code"], "PRICE_QUERY_FAILED")
            self.assertNotIn("private-token", json.dumps(result))
        finally:
            await client.aclose()


def choice(date=None, train="240000G5310A", seat="O"):
    return dict(date=date or (datetime.now() + timedelta(days=1)).strftime("%Y-%m-%d"),
                train_no=train, from_code="VNP", to_code="AOH", seat_code=seat)


class WaitlistValidationTests(unittest.TestCase):
    def test_deduplication_and_three_dates_sixty_distinct_trains(self):
        now = datetime(2026, 11, 2)
        c = choice("2026-11-03")
        self.assertEqual(len(validate_choices([c, c, choice("2026-11-03", seat="M")], now)), 2)
        sixty = [choice("2026-11-03", f"TRAIN{i:06}") for i in range(60)]
        self.assertEqual(len(validate_choices(sixty + [choice("2026-11-03", "TRAIN000000", "M")], now)), 61)
        with self.assertRaises(WaitlistFailure): validate_choices(sixty + [choice("2026-11-03", "TRAIN000060")], now)
        with self.assertRaises(WaitlistFailure): validate_choices([choice(f"2026-11-0{i}") for i in range(3, 7)], now)

    def test_bad_dates_seat_codes_and_payloads_rejected(self):
        now = datetime(2026, 11, 2)
        for choices in (None, [], [None], [choice("2026-11-17")], [choice("2026-11-03", seat="OM")],
                        [choice("2026-11-03", seat="WZ")], [choice("2026-11-03", seat="")]):
            with self.assertRaises(WaitlistFailure): validate_choices(choices, now)


class WaitlistProtocolTests(unittest.IsolatedAsyncioTestCase):
    async def test_fresh_preparation_does_not_poll_a_submission_queue(self):
        self.responses["afterNate/chechFace"] = dict(login_flag=True)
        response = await self.service.prepare([self.c])
        self.assertTrue(response["success"])
        self.assertNotIn("afterNate/queryQueue", [path for path, _ in self.calls])
        self.assertNotIn("afterNatePay/payOrderInit", [path for path, _ in self.calls])

    async def test_passport_redirect_requires_login_without_exposing_html(self):
        await self.service.client.aclose()
        def handle(request):
            if request.url.path == "/otn/passport":
                return httpx.Response(200, text="<html>private-login-page</html>")
            return httpx.Response(302, headers={"location": "/otn/passport"})
        self.service.client = httpx.AsyncClient(transport=httpx.MockTransport(handle), follow_redirects=True, trust_env=False)
        response = await self.service.status()
        self.assertEqual(response["code"], "LOGIN_REQUIRED")
        self.assertNotIn("private-login-page", str(response))

    async def test_completed_submission_reads_confirmation_data_without_starting_payment(self):
        self.responses["afterNate/queryQueue"] = dict(flag=True, isAsync=True, status="1")
        self.responses["afterNatePay/payOrderInit"] = dict(order=dict(reserve_no="TEST-ORDER", prepay_amount="498.00"), loseTime=123456)
        response = await self.service.status()
        self.assertEqual(response["data"]["state"], "pending_payment")
        self.assertEqual(response["data"]["amount_fen"], 49800)
        self.assertEqual([p for p, _ in self.calls], ["afterNate/queryQueue", "afterNatePay/payOrderInit"])

    async def test_stale_completed_queue_requires_official_check_not_another_submission(self):
        self.responses["afterNate/queryQueue"] = dict(flag=True, isAsync=True, status="1")
        self.responses["afterNatePay/payOrderInit"] = dict(order=None, msg="查询候补订单失败")
        response = await self.service.status()
        self.assertEqual(response["code"], "VERIFY_ORDER")
        self.assertEqual(response["data"]["state"], "unknown")
        self.assertFalse(any(p.endswith("confirmHB") for p, _ in self.calls))

    async def test_non_json_service_response_never_creates_a_submission(self):
        await self.service.client.aclose()
        self.service.client = httpx.AsyncClient(transport=httpx.MockTransport(lambda request: httpx.Response(200, text="<html>Unavailable</html>")), trust_env=False)
        response = await self.service.prepare([self.c])
        self.assertEqual(response["code"], "WAITLIST_UNAVAILABLE")
        self.assertFalse(self.service.submission_attempted)
        self.query.query.assert_awaited_once()

    async def asyncSetUp(self):
        self.c = choice()
        self.calls = []
        self.init = dict(hbTrainList=[dict(train_date=self.c["date"], train_no=self.c["train_no"],
                        from_station_telecode="VNP", to_station_telecode="AOH", seat_type_code="O")],
                        if_check_slide_passcode="0", jzdhDiffSelect=[20, 360], hb_passenger_max_num=19, canChooseWseat="N")
        self.responses = {
            "afterNate/queryQueue": dict(flag=False, isAsync=False),
            "afterNate/chechFace": dict(login_flag=True, face_flag=True),
            "afterNate/submitOrderRequest": dict(flag=True),
            "afterNate/passengerInitApi": self.init,
            "confirmPassenger/getPassengerDTOs": dict(normal_passengers=[dict(passenger_name="测试乘客", passenger_id_no="TEST_ID_1234",
                passenger_id_type_code="1", passenger_type="1", allEncStr="test-encrypted-value")]),
            "afterNate/confirmHB": dict(flag=True, isAsync=True),
        }
        def handle(request):
            path = request.url.path.removeprefix("/otn/")
            self.calls.append((path, parse_qs(request.content.decode(), keep_blank_values=True)))
            response = self.responses[path]
            if isinstance(response, Exception): raise response
            return httpx.Response(200, json=dict(status=True, data=response))
        client = httpx.AsyncClient(transport=httpx.MockTransport(handle), trust_env=False)
        train = SimpleNamespace(train_no=self.c["train_no"], from_station_code="VNP", to_station_code="AOH",
                                waitlist_supported=True, waitlist_restricted_seats="", second_seat="无",
                                start_time="12:00", secret_str="test%2Bsecret")
        self.query = SimpleNamespace(query=AsyncMock(return_value=([train], None)), close=AsyncMock())
        self.service = WaitlistService({"test-cookie": "test-only"}, client=client, query_factory=lambda _: self.query)

    async def asyncTearDown(self):
        await self.service.close()

    async def ready(self):
        result = await self.service.prepare([self.c]); self.assertTrue(result["success"], result)
        return result["data"]["passengers"][0]["id"]

    async def test_preparation_refreshes_inventory_and_returns_only_opaque_passenger_ids(self):
        response = await self.service.prepare([self.c])
        self.assertTrue(response["success"])
        serialized = json.dumps(response)
        for secret in ("test+secret", "test-encrypted-value", "TEST_ID_1234", "test-only"):
            self.assertNotIn(secret, serialized)
        self.assertEqual(dict(self.calls)["afterNate/chechFace"]["secretList"], ["test%2Bsecret#O|"])
        self.assertEqual(dict(self.calls)["afterNate/submitOrderRequest"]["secretList"], ["test%2Bsecret#O|"])
        self.query.query.assert_awaited_once_with("VNP", "AOH", self.c["date"])
        self.assertFalse(any(path.endswith("confirmHB") for path, _ in self.calls))

    async def test_real_submission_wire_format_and_duplicate_guard_without_payment(self):
        passenger_id = await self.ready()
        response = await self.service.submit([passenger_id], "360")
        self.assertTrue(response["success"])
        body = dict(self.calls)["afterNate/confirmHB"]
        self.assertEqual(body["passengerInfo"], ["1#测试乘客#1#TEST_ID_1234#test-encrypted-value#0;"])
        self.assertEqual(body["hbTrain"], [self.c["train_no"] + "#"])
        self.assertEqual(body["realize_limit_time_diff"], ["360"])
        self.assertEqual(body["if_receive_wseat"], ["N"])
        self.assertEqual((await self.service.submit([passenger_id], "360"))["code"], "ALREADY_SUBMITTED")
        self.assertEqual(sum(p == "afterNate/confirmHB" for p, _ in self.calls), 1)
        self.assertFalse(any("Pay/" in p or "paycheck" in p for p, _ in self.calls))

    async def test_timeout_cannot_trigger_duplicate_submit(self):
        passenger_id = await self.ready()
        self.responses["afterNate/confirmHB"] = httpx.ReadTimeout("private-secret")
        response = await self.service.submit([passenger_id], "360")
        self.assertEqual(response["code"], "SUBMISSION_UNKNOWN")
        self.assertNotIn("private-secret", json.dumps(response))
        self.assertEqual((await self.service.submit([passenger_id], "360"))["code"], "ALREADY_SUBMITTED")

    async def test_new_train_conditions_are_validated_and_sent_without_payment(self):
        passenger_id = await self.ready()
        invalid = {"seat_codes": ["O"], "ranges": [{"date": self.c["date"], "start_hour": 24, "end_hour": 24}]}
        self.assertEqual((await self.service.submit([passenger_id], "360", new_trains=invalid))["code"], "INVALID_NEW_TRAIN_OPTIONS")
        self.assertFalse(self.service.submission_attempted)
        config = {"seat_codes": ["O"], "ranges": [{"date": self.c["date"], "start_hour": 6, "end_hour": 7}]}
        self.assertTrue((await self.service.submit([passenger_id], "360", new_trains=config))["success"])
        body = dict(self.calls)["afterNate/confirmHB"]
        self.assertEqual(body["add_train_flag"], ["Y"])
        self.assertEqual(body["tmp_train_date"], [self.c["date"].replace("-", "") + "#"])
        self.assertEqual(body["tmp_train_time"], ["0607#"])
        self.assertFalse(any("Pay/" in p or "paycheck" in p for p, _ in self.calls))

    async def test_invalid_passenger_deadline_and_standing_do_not_send_order(self):
        passenger_id = await self.ready()
        for ids, deadline, standing in (([], "360", False), ([{}], "360", False), ([passenger_id]*2, "360", False),
                                         ([passenger_id], "999", False), ([passenger_id], "360", True)):
            self.assertFalse((await self.service.submit(ids, deadline, standing))["success"])
        self.assertFalse(any(p == "afterNate/confirmHB" for p, _ in self.calls))

    async def test_identity_and_captcha_gates_never_submit(self):
        self.responses["afterNate/chechFace"]["face_flag"] = False
        self.assertEqual((await self.service.prepare([self.c]))["code"], "IDENTITY_REQUIRED")
        self.responses["afterNate/chechFace"]["face_flag"] = True
        self.init["if_check_slide_passcode"] = "1"
        self.assertEqual((await self.service.prepare([self.c]))["code"], "VERIFICATION_REQUIRED")
        self.assertFalse(any(p == "afterNate/confirmHB" for p, _ in self.calls))

    async def test_changed_draft_in_another_session_blocks_final_submission(self):
        passenger_id = await self.ready()
        self.responses["afterNate/passengerInitApi"] = {**self.init, "hbTrainList": []}
        self.assertEqual((await self.service.submit([passenger_id], "360"))["code"], "SELECTION_CHANGED")
        self.assertFalse(any(p == "afterNate/confirmHB" for p, _ in self.calls))

    async def test_current_pending_order_prevents_another_draft_and_uses_actual_amount(self):
        self.responses["afterNate/queryQueue"] = dict(flag=True, isAsync=False,
            order=dict(reserve_no="TEST_ORDER", prepay_amount="498.0"), loseTime=1793577600000)
        response = await self.service.status()
        self.assertEqual(response["data"]["state"], "pending_payment")
        self.assertEqual(response["data"]["amount_fen"], 49800)
        self.assertEqual((await self.service.prepare([self.c]))["code"], "EXISTING_ORDER")
        self.query.query.assert_not_awaited()

    async def test_queued_failed_unknown_and_empty_orders_are_distinct(self):
        for payload, state in ((dict(flag=True, isAsync=True, status=0), "processing"),
                               (dict(flag=True, isAsync=True, status=-1), "failed"),
                               (dict(flag=False, isAsync=False), "none"),
                               ({}, "unknown")):
            self.responses["afterNate/queryQueue"] = payload
            self.assertEqual((await self.service.status())["data"]["state"], state)

    async def test_no_cookies_never_reaches_network(self):
        self.service.cookies = {}
        self.assertEqual((await self.service.prepare([self.c]))["code"], "LOGIN_REQUIRED")
        self.assertEqual((await self.service.status())["code"], "LOGIN_REQUIRED")
        self.assertEqual(self.calls, [])


if __name__ == "__main__":
    unittest.main()
