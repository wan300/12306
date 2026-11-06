import unittest

from app.services.task_messages import task_message


class TaskMessagesTests(unittest.TestCase):
    def test_historical_ticket_and_lifecycle_messages(self):
        self.assertEqual(task_message("Task started"), "任务已启动，开始自动查票")
        self.assertEqual(task_message("Found ticket G547 Second(8)"), "发现可购车票：G547 · 二等座 · 余票 8 张")
        self.assertIn("待支付", task_message("Order submitted: TEST123"))
        self.assertIn("TEST123", task_message("Order submitted: TEST123"))

    def test_multiline_scan_preserves_train_codes_and_seat_results(self):
        translated = task_message("Scan complete: G547[Second:9, First:无] | G1[Hard sleeper:有]")
        self.assertIn("G547：二等座：9，一等座：无", translated)
        self.assertIn("\nG1：硬卧：有", translated)

    def test_actionable_chinese_errors_are_preserved_but_transport_details_hidden(self):
        self.assertEqual(task_message("Order failed: 学生优惠资质未核验"), "订单未提交成功：学生优惠资质未核验")
        self.assertNotIn("private.invalid", task_message("Query failed: GET https://private.invalid?token=secret"))
        self.assertIn("官方未完成订单", task_message("Order failed: 等待超时"))

    def test_translation_is_idempotent_and_handles_empty_result(self):
        for value in [None, "", "Task paused", "Order failed: 等待超时", "Scan complete: G1[Second:无]"]:
            self.assertEqual(task_message(task_message(value)), task_message(value))
