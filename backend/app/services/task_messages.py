"""Chinese task messages, including records written by earlier app versions."""
import re

_EXACT = {
    "Task created": "任务已创建，等待启动",
    "Task updated": "任务设置已保存",
    "Task started": "任务已启动，开始自动查票",
    "Task paused": "任务已暂停",
    "Task cancelled": "任务已取消",
    "Max retry count reached": "已达到设置的查询次数，任务已结束",
    "User not logged in": "登录已失效，请重新登录后启动任务",
    "No trains matched": "本次未查到符合行程条件的车次",
    "Specified trains not found": "本次未查到指定车次，请检查日期和筛选条件",
    "No matching passengers found": "未找到所选乘车人，请重新选择乘车人后再试",
    "等待超时": "订单结果查询超时，请先核对官方未完成订单再继续",
}
_SEATS = {"Business": "商务座", "First": "一等座", "Second": "二等座",
          "Soft sleeper": "软卧", "Hard sleeper": "硬卧", "Hard seat": "硬座"}


def _reason(value, fallback):
    # Server Chinese explanations are useful; transport details and URLs are not.
    if "http" in value.lower() or "Traceback" in value or not re.search(r"[\u4e00-\u9fff]", value):
        return fallback
    return value


def task_message(message):
    if not message:
        return message
    if message in _EXACT:
        return _EXACT[message]
    found = re.fullmatch(r"Found ticket (\S+) (.+)\(([^()]*)\)", message)
    if found:
        train, seat, count = found.groups()
        count = "有票" if count == "有" else f"余票 {count} 张"
        return f"发现可购车票：{train} · {_SEATS.get(seat, seat)} · {count}"
    if message.startswith("Order submitted: "):
        return "订单已生成，待支付。订单号：" + message.split(": ", 1)[1] + "；请在官方规定时间内手动付款"
    if message.startswith("Scan complete: "):
        value = message[len("Scan complete: "):]
        for english, chinese in _SEATS.items():
            value = value.replace(english + ":", chinese + "：")
        value = value.replace(" | ", "\n").replace("[", "：").replace("]", "").replace(", ", "，")
        return "本轮查票结果：\n" + value
    for prefix, label, fallback in [
        ("Execution error: ", "任务运行异常", "请稍后重试"),
        ("Query failed: ", "查票失败", "网络或服务暂不可用，请稍后重试"),
        ("Passenger query failed: ", "获取乘车人失败", "请检查登录状态后重试"),
        ("Order failed: ", "订单未提交成功", "请核对官方未完成订单及登录状态"),
    ]:
        if message.startswith(prefix):
            detail = task_message(message[len(prefix):])
            return label + "：" + _reason(detail, fallback)
    return message
