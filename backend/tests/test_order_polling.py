import asyncio
from unittest.mock import AsyncMock, patch

import httpx
import unittest

from app.services.order_service import OrderService, OrderToken


class OrderPollingTests(unittest.TestCase):
    def test_terminal_failure_returns_server_reason_without_timeout(self):
        for wait in (-1, -2, -3):
            self.check_terminal(wait)

    def check_terminal(self, wait):
        service = OrderService()
        service._order_token = OrderToken(repeat_submit_token="test")
        response = httpx.Response(200, json={"status": True, "data": {"waitTime": wait, "msg": "学生资质未核验"}})
        service.get_client = AsyncMock(return_value=type("Client", (), {"get": AsyncMock(return_value=response)})())
        result = asyncio.run(service.query_order_wait_time())
        assert not result.success
        assert result.message == "学生资质未核验"
        assert result.requires_review == (wait == -1)


    def test_order_number_is_success_even_with_terminal_wait_flag(self):
        service = OrderService()
        service._order_token = OrderToken(repeat_submit_token="test")
        response = httpx.Response(200, json={"status": True, "data": {"waitTime": -1, "orderId": "TEST123"}})
        service.get_client = AsyncMock(return_value=type("Client", (), {"get": AsyncMock(return_value=response)})())
        result = asyncio.run(service.query_order_wait_time())
        assert result.success and result.order_id == "TEST123"
        assert result.message == "订单已生成，待支付"


    def test_unreadable_result_and_timeout_require_manual_review_before_resubmit(self):
        service = OrderService()
        service._order_token = OrderToken(repeat_submit_token="test")
        response = httpx.Response(200, text="<html>unavailable</html>")
        service.get_client = AsyncMock(return_value=type("Client", (), {"get": AsyncMock(return_value=response)})())
        assert asyncio.run(service.query_order_wait_time()).requires_review
        assert asyncio.run(service.query_order_wait_time(max_wait=0)).requires_review
