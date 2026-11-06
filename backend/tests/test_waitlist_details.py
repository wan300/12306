import unittest
from app.services.waitlist_details import new_train_form, pending_details, date_text
from app.services.query_service import QueryService


class WaitlistDetailsTests(unittest.TestCase):
    def test_pending_new_train_seats_follow_official_needs_fallback(self):
        order = dict(accept_tmp_train="Y", needs=[dict(seat_type="O"), dict(seat_type_code="OM")])
        self.assertEqual(pending_details(order)["new_train_seats"], ["二等座", "一等座"])
        self.assertEqual(pending_details({**order, "add_train_seat_type_code": "9"})["new_train_seats"], ["商务座"])

    def test_order_dates_accept_website_and_native_formats(self):
        self.assertEqual(date_text("20261105174557"), "2026-11-05")
        self.assertEqual(date_text("2026-11-06 06:08:00"), "2026-11-06")
        self.assertEqual(date_text(1793894400000), "2026-11-06")

    def test_new_train_wire_uses_selected_dates_hours_and_seats(self):
        trains = [{"train_date": "2026-11-06"}, {"train_date": "2026-11-07"}]
        config = {"seat_codes": ["O", "M"], "ranges": [
            {"date": "2026-11-06", "start_hour": 6, "end_hour": 7},
            {"date": "2026-11-07", "start_hour": 0, "end_hour": 24}]}
        self.assertEqual(new_train_form(config, trains), dict(add_train_flag="Y", tmp_train_date="20261106#20261107#", tmp_train_time="0607#0024#", add_train_seat_type_code="OM"))
        self.assertEqual(new_train_form(None, trains)["add_train_flag"], "N")

    def test_invalid_conditions_cannot_widen_real_order(self):
        trains = [{"train_date": "2026-11-06"}]
        base = {"date": "2026-11-06", "start_hour": 6, "end_hour": 7}
        for config in [False, {}, {"seat_codes": [], "ranges": [base]}, {"seat_codes": ["WZ"], "ranges": [base]},
                       {"seat_codes": ["O"], "ranges": []}, {"seat_codes": ["O"], "ranges": [base, base]},
                       *[{"seat_codes": ["O"], "ranges": [{**base, **change}]} for change in [
                           {"date": "2026-11-08"}, {"start_hour": 7}, {"end_hour": 25}, {"start_hour": True}, {"start_hour": -1}]]]:
            with self.subTest(config=config), self.assertRaises(ValueError):
                new_train_form(config, trains)

    def test_pending_details_preserve_values_and_mask_identity(self):
        data = pending_details(dict(needs=[dict(board_train_code="G531", train_date="20261106", arrive_date="20261106", start_time="0608", arrive_time="12:04", from_station_name="北京南", to_station_name="上海虹桥", seat_name="二等座", batch_ticket_price="498.0")],
            passengers=[dict(passenger_name="测试乘客", passenger_id_no="TEST_ID_123456", passenger_id_name="居民身份证", ticket_type="成人票", pre_passenger_price="4980", allEncStr="private-token")],
            if_receive_wseat="N", accept_tmp_train="Y", realize_limit_time_diff="360", tmps=[dict(tmp_train_date="20261106", tmp_train_time="0607")], add_train_seat_type_code="O"))
        self.assertEqual(data["trains"][0]["start_time"], "06:08")
        self.assertEqual(data["trains"][0]["amount_fen"], 49800)
        self.assertEqual(data["passengers"][0]["amount_fen"], 49800)
        self.assertEqual(data["new_train_ranges"], [{"date": "2026-11-06", "hours": "0607"}])
        self.assertNotIn("TEST_ID_123456", str(data))
        self.assertNotIn("private-token", str(data))
        self.assertEqual(pending_details({})["accept_new_trains"], "")

    def test_only_official_quiet_flag_marks_train(self):
        parts = [""] * 56
        for index, value in {0: "secret", 2: "TRAIN00001", 3: "G531", 6: "VNP", 7: "AOH", 8: "06:08", 9: "12:04", 11: "Y", 16: "01", 17: "05"}.items():
            parts[index] = value
        for flag, expected in [("5#1#Q1#0", True), ("5#1#R1#0", False), ("", False), ("Q", False)]:
            parts[46] = flag
            self.assertEqual(QueryService()._parse_train_item("|".join(parts), {}, "2026-11-06").quiet_carriage, expected)


if __name__ == "__main__":
    unittest.main()
