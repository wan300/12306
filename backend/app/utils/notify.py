import base64
import hashlib
import hmac
import json
import os
import re
import threading
import time
import urllib.parse
import smtplib
from email.mime.text import MIMEText
from email.header import Header
from email.utils import formataddr
from functools import partial

import requests
import logging

# 统一日志
logger = logging.getLogger("notify")
_notify_local = threading.local()


def channel(name: str):
    def decorator(func):
        def wrapper(*args, **kwargs):
            prev = getattr(_notify_local, "channel", None)
            _notify_local.channel = name
            try:
                return func(*args, **kwargs)
            finally:
                _notify_local.channel = prev
        return wrapper
    return decorator


def _notify_log(message: str):
    try:
        text = str(message)
    except Exception:
        text = ""
    level = logging.INFO
    # 简单按关键词判定日志级别
    if "失败" in text or "错误" in text:
        level = logging.ERROR
    elif "警告" in text or "warning" in text:
        level = logging.WARNING
    ch = getattr(_notify_local, "channel", None)
    if ch:
        logging.getLogger(f"notify.{ch}").log(level, text)
    else:
        logger.log(level, text)


def print(text, *args, **kw):
    """
    将模块内的打印统一输出到 logging（notify logger），保持现有调用不变。
    """
    try:
        parts = [str(text)] + [str(a) for a in args]
        combined = " ".join(parts)
        _notify_log(combined)
    except Exception:
        logger.info(str(text))


# 通知服务
# fmt: off
push_config = {
    'HITOKOTO': True,                  # 启用一言（随机句子）

    'BARK_PUSH': '',                    # bark IP 或设备码，例：https://api.day.app/DxHcxxxxxRxxxxxxcm/
    'BARK_ARCHIVE': '',                 # bark 推送是否存档
    'BARK_GROUP': '',                   # bark 推送分组
    'BARK_SOUND': '',                   # bark 推送声音
    'BARK_ICON': '',                    # bark 推送图标
    'BARK_LEVEL': '',                   # bark 推送时效性
    'BARK_URL': '',                     # bark 推送跳转URL

    'CONSOLE': False,                    # 控制台输出

    'DD_BOT_SECRET': '',                # 钉钉机器人的 DD_BOT_SECRET
    'DD_BOT_TOKEN': '',                 # 钉钉机器人的 DD_BOT_TOKEN

    'FSKEY': '',                        # 飞书机器人的 FSKEY

    'GOBOT_URL': '',                    # go-cqhttp
                                        # 推送到个人QQ：http://127.0.0.1/send_private_msg
                                        # 群：http://127.0.0.1/send_group_msg
    'GOBOT_QQ': '',                     # go-cqhttp 的推送群或用户
                                        # GOBOT_URL 设置 /send_private_msg 时填入 user_id=个人QQ
                                        #               /send_group_msg   时填入 group_id=QQ群
    'GOBOT_TOKEN': '',                  # go-cqhttp 的 access_token

    'GOTIFY_URL': '',                   # gotify地址,如https://push.example.de:8080
    'GOTIFY_TOKEN': '',                 # gotify的消息应用token
    'GOTIFY_PRIORITY': 0,               # 推送消息优先级,默认为0

    'IGOT_PUSH_KEY': '',                # iGot 聚合推送的 IGOT_PUSH_KEY

    'PUSH_KEY': '',                     # server 酱的 PUSH_KEY，兼容旧版与 Turbo 版

    'DEER_KEY': '',                     # PushDeer 的 PUSHDEER_KEY
    'DEER_URL': '',                     # PushDeer 的 PUSHDEER_URL

    'CHAT_URL': '',                     # synology chat url
    'CHAT_TOKEN': '',                   # synology chat token

    'PUSH_PLUS_TOKEN': '',              # pushplus 推送的用户令牌
    'PUSH_PLUS_USER': '',               # pushplus 推送的群组编码
    'PUSH_PLUS_TEMPLATE': 'html',       # pushplus 发送模板，支持html,txt,json,markdown,cloudMonitor,jenkins,route,pay
    'PUSH_PLUS_CHANNEL': 'wechat',      # pushplus 发送渠道，支持wechat,webhook,cp,mail,sms
    'PUSH_PLUS_WEBHOOK': '',            # pushplus webhook编码，可在pushplus公众号上扩展配置出更多渠道
    'PUSH_PLUS_CALLBACKURL': '',        # pushplus 发送结果回调地址，会把推送最终结果通知到这个地址上
    'PUSH_PLUS_TO': '',                 # pushplus 好友令牌，微信公众号渠道填写好友令牌，企业微信渠道填写企业微信用户id

    'WE_PLUS_BOT_TOKEN': '',            # 微加机器人的用户令牌
    'WE_PLUS_BOT_RECEIVER': '',         # 微加机器人的消息接收者
    'WE_PLUS_BOT_VERSION': 'pro',       # 微加机器人的调用版本

    'QMSG_KEY': '',                     # qmsg 酱的 QMSG_KEY
    'QMSG_TYPE': '',                    # qmsg 酱的 QMSG_TYPE

    'QYWX_ORIGIN': '',                  # 企业微信代理地址

    'QYWX_AM': '',                      # 企业微信应用

    'QYWX_KEY': '',                     # 企业微信机器人

    'TG_BOT_TOKEN': '',                 # tg 机器人的 TG_BOT_TOKEN，例：1407203283:AAG9rt-XXXXXXXXXXXXXXXXXXaRQ
    'TG_USER_ID': '',                   # tg 机器人的 TG_USER_ID，例：1434078534
    'TG_API_HOST': '',                  # tg 代理 api
    'TG_PROXY_AUTH': '',                # tg 代理认证参数
    'TG_PROXY_HOST': '',                # tg 机器人的 TG_PROXY_HOST
    'TG_PROXY_PORT': '',                # tg 机器人的 TG_PROXY_PORT

    'AIBOTK_KEY': '',                   # 智能微秘书 个人中心的apikey 文档地址：http://wechat.aibotk.com/docs/about
    'AIBOTK_TYPE': '',                  # 智能微秘书 发送目标 room 或 contact
    'AIBOTK_NAME': '',                  # 智能微秘书  发送群名 或者好友昵称和type要对应好

    'SMTP_SERVER': '',                  # SMTP 发送邮件服务器，形如 smtp.exmail.qq.com:465
    'SMTP_SSL': 'false',                # SMTP 发送邮件服务器是否使用 SSL，填写 true 或 false
    'SMTP_EMAIL': '',                   # SMTP 收发件邮箱，通知将会由自己发给自己
    'SMTP_PASSWORD': '',                # SMTP 登录密码，也可能为特殊口令，视具体邮件服务商说明而定
    'SMTP_NAME': '',                    # SMTP 收发件人姓名，可随意填写

    'PUSHME_KEY': '',                   # PushMe 的 PUSHME_KEY
    'PUSHME_URL': '',                   # PushMe 的 PUSHME_URL

    'CHRONOCAT_QQ': '',                 # qq号
    'CHRONOCAT_TOKEN': '',              # CHRONOCAT 的token
    'CHRONOCAT_URL': '',                # CHRONOCAT的url地址

    'WEBHOOK_URL': '',                  # 自定义通知 请求地址
    'WEBHOOK_BODY': '',                 # 自定义通知 请求体
    'WEBHOOK_HEADERS': '',              # 自定义通知 请求头
    'WEBHOOK_METHOD': '',               # 自定义通知 请求方法
    'WEBHOOK_CONTENT_TYPE': '',         # 自定义通知 content-type

    'NTFY_URL': '',                     # ntfy地址,如https://ntfy.sh
    'NTFY_TOPIC': '',                   # ntfy的消息应用topic
    'NTFY_PRIORITY':'3',                # 推送消息优先级,默认为3

    'WXPUSHER_APP_TOKEN': '',           # wxpusher 的 appToken 官方文档: https://wxpusher.zjiecode.com/docs/ 管理后台: https://wxpusher.zjiecode.com/admin/
    'WXPUSHER_TOPIC_IDS': '',           # wxpusher 的 主题ID，多个用英文分号;分隔 topic_ids 与 uids 至少配置一个才行
    'WXPUSHER_UIDS': '',                # wxpusher 的 用户ID，多个用英文分号;分隔 topic_ids 与 uids 至少配置一个才行

    'MEDIASABER_HOST': '',              # Media Saber 服务器地址，例：https://your-domain.com
    'MEDIASABER_APIKEY': '',            # Media Saber API密钥
}
# fmt: on

for k in push_config:
    if os.getenv(k):
        v = os.getenv(k)
        push_config[k] = v


@channel("bark")
def bark(title: str, content: str, config=None) -> None:
    """
    使用 bark 推送消息。
    """
    cfg = config or push_config
    if not cfg.get("BARK_PUSH"):
        return
    print("bark 服务启动")

    if cfg.get("BARK_PUSH").startswith("http"):
        url = f'{cfg.get("BARK_PUSH")}'
    else:
        url = f'https://api.day.app/{cfg.get("BARK_PUSH")}'

    bark_params = {
        "BARK_ARCHIVE": "isArchive",
        "BARK_GROUP": "group",
        "BARK_SOUND": "sound",
        "BARK_ICON": "icon",
        "BARK_LEVEL": "level",
        "BARK_URL": "url",
    }
    data = {
        "title": title,
        "body": content,
    }
    for pair in filter(
        lambda pairs: pairs[0].startswith("BARK_")
        and pairs[0] != "BARK_PUSH"
        and pairs[1]
        and bark_params.get(pairs[0]),
        cfg.items(),
    ):
        data[bark_params.get(pair[0])] = pair[1]
    headers = {"Content-Type": "application/json;charset=utf-8"}
    response = requests.post(
        url=url, data=json.dumps(data), headers=headers, timeout=15
    ).json()

    if response["code"] == 200:
        print("bark 推送成功！")
    else:
        print("bark 推送失败！")


@channel("console")
def console(title: str, content: str, config=None) -> None:
    """
    使用 控制台 推送消息。
    """
    print(f"{title}\n\n{content}")


@channel("dingding")
def dingding_bot(title: str, content: str, config=None) -> None:
    """
    使用 钉钉机器人 推送消息。
    """
    cfg = config or push_config
    if not cfg.get("DD_BOT_SECRET") or not cfg.get("DD_BOT_TOKEN"):
        return
    print("钉钉机器人 服务启动")

    timestamp = str(round(time.time() * 1000))
    secret_enc = cfg.get("DD_BOT_SECRET").encode("utf-8")
    string_to_sign = "{}\n{}".format(timestamp, cfg.get("DD_BOT_SECRET"))
    string_to_sign_enc = string_to_sign.encode("utf-8")
    hmac_code = hmac.new(
        secret_enc, string_to_sign_enc, digestmod=hashlib.sha256
    ).digest()
    sign = urllib.parse.quote_plus(base64.b64encode(hmac_code))
    url = f'https://oapi.dingtalk.com/robot/send?access_token={cfg.get("DD_BOT_TOKEN")}&timestamp={timestamp}&sign={sign}'
    headers = {"Content-Type": "application/json;charset=utf-8"}
    data = {"msgtype": "text", "text": {"content": f"{title}\n\n{content}"}}
    response = requests.post(
        url=url, data=json.dumps(data), headers=headers, timeout=15
    ).json()

    if not response["errcode"]:
        print("钉钉机器人 推送成功！")
    else:
        print("钉钉机器人 推送失败！")


@channel("feishu")
def feishu_bot(title: str, content: str, config=None) -> None:
    """
    使用 飞书机器人 推送消息。
    """
    cfg = config or push_config
    if not cfg.get("FSKEY"):
        return
    print("飞书 服务启动")

    url = f'https://open.feishu.cn/open-apis/bot/v2/hook/{cfg.get("FSKEY")}'
    data = {"msg_type": "text", "content": {"text": f"{title}\n\n{content}"}}
    response = requests.post(url, data=json.dumps(data), timeout=15).json()

    if response.get("StatusCode") == 0 or response.get("code") == 0:
        print("飞书 推送成功！")
    else:
        print("飞书 推送失败！错误信息如下：\n", response)


@channel("go_cqhttp")
def go_cqhttp(title: str, content: str, config=None) -> None:
    """
    使用 go_cqhttp 推送消息。
    """
    cfg = config or push_config
    if not cfg.get("GOBOT_URL") or not cfg.get("GOBOT_QQ"):
        return
    print("go-cqhttp 服务启动")

    url = f'{cfg.get("GOBOT_URL")}?access_token={cfg.get("GOBOT_TOKEN")}&{cfg.get("GOBOT_QQ")}&message=标题:{title}\n内容:{content}'
    response = requests.get(url, timeout=15).json()

    if response["status"] == "ok":
        print("go-cqhttp 推送成功！")
    else:
        print("go-cqhttp 推送失败！")


@channel("gotify")
def gotify(title: str, content: str, config=None) -> None:
    """
    使用 gotify 推送消息。
    """
    cfg = config or push_config
    if not cfg.get("GOTIFY_URL") or not cfg.get("GOTIFY_TOKEN"):
        return
    print("gotify 服务启动")

    url = f'{cfg.get("GOTIFY_URL")}/message?token={cfg.get("GOTIFY_TOKEN")}'
    data = {
        "title": title,
        "message": content,
        "priority": cfg.get("GOTIFY_PRIORITY"),
    }
    response = requests.post(url, data=data, timeout=15).json()

    if response.get("id"):
        print("gotify 推送成功！")
    else:
        print("gotify 推送失败！")


@channel("igot")
def iGot(title: str, content: str, config=None) -> None:
    """
    使用 iGot 推送消息。
    """
    cfg = config or push_config
    if not cfg.get("IGOT_PUSH_KEY"):
        return
    print("iGot 服务启动")

    url = f'https://push.hellyw.com/{cfg.get("IGOT_PUSH_KEY")}'
    data = {"title": title, "content": content}
    headers = {"Content-Type": "application/x-www-form-urlencoded"}
    response = requests.post(url, data=data, headers=headers, timeout=15).json()

    if response["ret"] == 0:
        print("iGot 推送成功！")
    else:
        print(f'iGot 推送失败！{response["errMsg"]}')


@channel("serverj")
def serverJ(title: str, content: str, config=None) -> None:
    """
    通过 serverJ 推送消息。
    """
    cfg = config or push_config
    if not cfg.get("PUSH_KEY"):
        return
    print("serverJ 服务启动")

    data = {"text": title, "desp": content.replace("\n", "\n\n")}

    match = re.match(r"sctp(\d+)t", cfg.get("PUSH_KEY"))
    if match:
        num = match.group(1)
        url = f'https://{num}.push.ft07.com/send/{cfg.get("PUSH_KEY")}.send'
    else:
        url = f'https://sctapi.ftqq.com/{cfg.get("PUSH_KEY")}.send'

    response = requests.post(url, data=data, timeout=15).json()

    if response.get("errno") == 0 or response.get("code") == 0:
        print("serverJ 推送成功！")
    else:
        print(f'serverJ 推送失败！错误码：{response["message"]}')


@channel("pushdeer")
def pushdeer(title: str, content: str, config=None) -> None:
    """
    通过PushDeer 推送消息
    """
    cfg = config or push_config
    if not cfg.get("DEER_KEY"):
        return
    print("PushDeer 服务启动")
    data = {
        "text": title,
        "desp": content,
        "type": "markdown",
        "pushkey": cfg.get("DEER_KEY"),
    }
    url = "https://api2.pushdeer.com/message/push"
    if cfg.get("DEER_URL"):
        url = cfg.get("DEER_URL")

    response = requests.post(url, data=data, timeout=15).json()

    if len(response.get("content").get("result")) > 0:
        print("PushDeer 推送成功！")
    else:
        print("PushDeer 推送失败！错误信息：", response)


@channel("chat")
def chat(title: str, content: str, config=None) -> None:
    """
    通过Chat 推送消息
    """
    cfg = config or push_config
    if not cfg.get("CHAT_URL") or not cfg.get("CHAT_TOKEN"):
        return
    print("chat 服务启动")
    data = "payload=" + json.dumps({"text": title + "\n" + content})
    url = cfg.get("CHAT_URL") + cfg.get("CHAT_TOKEN")
    response = requests.post(url, data=data, timeout=15)

    if response.status_code == 200:
        print("Chat 推送成功！")
    else:
        print("Chat 推送失败！错误信息：", response)


@channel("pushplus")
def pushplus_bot(title: str, content: str, config=None) -> None:
    """
    通过 pushplus 推送消息。
    """
    cfg = config or push_config
    if not cfg.get("PUSH_PLUS_TOKEN"):
        return
    print("PUSHPLUS 服务启动")

    url = "https://www.pushplus.plus/send"
    data = {
        "token": cfg.get("PUSH_PLUS_TOKEN"),
        "title": title,
        "content": content,
        "topic": cfg.get("PUSH_PLUS_USER"),
        "template": cfg.get("PUSH_PLUS_TEMPLATE"),
        "channel": cfg.get("PUSH_PLUS_CHANNEL"),
        "webhook": cfg.get("PUSH_PLUS_WEBHOOK"),
        "callbackUrl": cfg.get("PUSH_PLUS_CALLBACKURL"),
        "to": cfg.get("PUSH_PLUS_TO"),
    }
    body = json.dumps(data).encode(encoding="utf-8")
    headers = {"Content-Type": "application/json"}
    response = requests.post(url=url, data=body, headers=headers, timeout=15).json()

    code = response["code"]
    if code == 200:
        print("PUSHPLUS 推送请求成功，可根据流水号查询推送结果:" + response["data"])
        print(
            "注意：请求成功并不代表推送成功，如未收到消息，请到pushplus官网使用流水号查询推送最终结果"
        )
    elif code == 900 or code == 903 or code == 905 or code == 999:
        print(response["msg"])

    else:
        url_old = "http://pushplus.hxtrip.com/send"
        headers["Accept"] = "application/json"
        response = requests.post(url=url_old, data=body, headers=headers, timeout=15).json()

        if response["code"] == 200:
            print("PUSHPLUS(hxtrip) 推送成功！")

        else:
            print("PUSHPLUS 推送失败！")


@channel("weplus")
def weplus_bot(title: str, content: str, config=None) -> None:
    """
    通过 微加机器人 推送消息。
    """
    cfg = config or push_config
    if not cfg.get("WE_PLUS_BOT_TOKEN"):
        return
    print("微加机器人 服务启动")

    template = "txt"
    if len(content) > 800:
        template = "html"

    url = "https://www.weplusbot.com/send"
    data = {
        "token": cfg.get("WE_PLUS_BOT_TOKEN"),
        "title": title,
        "content": content,
        "template": template,
        "receiver": cfg.get("WE_PLUS_BOT_RECEIVER"),
        "version": cfg.get("WE_PLUS_BOT_VERSION"),
    }
    body = json.dumps(data).encode(encoding="utf-8")
    headers = {"Content-Type": "application/json"}
    response = requests.post(url=url, data=body, headers=headers, timeout=15).json()

    if response["code"] == 200:
        print("微加机器人 推送成功！")
    else:
        print("微加机器人 推送失败！")


@channel("qmsg")
def qmsg_bot(title: str, content: str, config=None) -> None:
    """
    使用 qmsg 推送消息。
    """
    cfg = config or push_config
    if not cfg.get("QMSG_KEY") or not cfg.get("QMSG_TYPE"):
        return
    print("qmsg 服务启动")

    url = f'https://qmsg.zendee.cn/{cfg.get("QMSG_TYPE")}/{cfg.get("QMSG_KEY")}'
    payload = {"msg": f'{title}\n\n{content.replace("----", "-")}'.encode("utf-8")}
    response = requests.post(url=url, params=payload, timeout=15).json()

    if response["code"] == 0:
        print("qmsg 推送成功！")
    else:
        print(f'qmsg 推送失败！{response["reason"]}')


@channel("wecom_app")
def wecom_app(title: str, content: str, config=None) -> None:
    """
    通过 企业微信 APP 推送消息。
    """
    cfg = config or push_config
    if not cfg.get("QYWX_AM"):
        return
    QYWX_AM_AY = re.split(",", cfg.get("QYWX_AM"))
    if 4 < len(QYWX_AM_AY) > 5:
        print("QYWX_AM 设置错误!!")
        return
    print("企业微信 APP 服务启动")

    corpid = QYWX_AM_AY[0]
    corpsecret = QYWX_AM_AY[1]
    touser = QYWX_AM_AY[2]
    agentid = QYWX_AM_AY[3]
    try:
        media_id = QYWX_AM_AY[4]
    except IndexError:
        media_id = ""
    wx = WeCom(corpid, corpsecret, agentid, config=cfg)
    # 如果没有配置 media_id 默认就以 text 方式发送
    if not media_id:
        message = title + "\n\n" + content
        response = wx.send_text(message, touser)
    else:
        response = wx.send_mpnews(title, content, media_id, touser)

    if response == "ok":
        print("企业微信推送成功！")
    else:
        print("企业微信推送失败！错误信息如下：\n", response)


class WeCom:
    def __init__(self, corpid, corpsecret, agentid, config=None):
        self.CORPID = corpid
        self.CORPSECRET = corpsecret
        self.AGENTID = agentid
        self.ORIGIN = "https://qyapi.weixin.qq.com"
        cfg = config or push_config
        if cfg.get("QYWX_ORIGIN"):
            self.ORIGIN = cfg.get("QYWX_ORIGIN")

    def get_access_token(self):
        url = f"{self.ORIGIN}/cgi-bin/gettoken"
        values = {
            "corpid": self.CORPID,
            "corpsecret": self.CORPSECRET,
        }
        req = requests.post(url, params=values, timeout=15)
        data = json.loads(req.text)
        return data["access_token"]

    def send_text(self, message, touser="@all"):
        send_url = (
            f"{self.ORIGIN}/cgi-bin/message/send?access_token={self.get_access_token()}"
        )
        send_values = {
            "touser": touser,
            "msgtype": "text",
            "agentid": self.AGENTID,
            "text": {"content": message},
            "safe": "0",
        }
        send_msges = bytes(json.dumps(send_values), "utf-8")
        respone = requests.post(send_url, send_msges, timeout=15)
        respone = respone.json()
        return respone["errmsg"]

    def send_mpnews(self, title, message, media_id, touser="@all"):
        send_url = (
            f"{self.ORIGIN}/cgi-bin/message/send?access_token={self.get_access_token()}"
        )
        send_values = {
            "touser": touser,
            "msgtype": "mpnews",
            "agentid": self.AGENTID,
            "mpnews": {
                "articles": [
                    {
                        "title": title,
                        "thumb_media_id": media_id,
                        "author": "Author",
                        "content_source_url": "",
                        "content": message.replace("\n", "<br/>"),
                        "digest": message,
                    }
                ]
            },
        }
        send_msges = bytes(json.dumps(send_values), "utf-8")
        respone = requests.post(send_url, send_msges, timeout=15)
        respone = respone.json()
        return respone["errmsg"]


@channel("wecom_bot")
def wecom_bot(title: str, content: str, config=None) -> None:
    """
    通过 企业微信机器人 推送消息。
    """
    cfg = config or push_config
    if not cfg.get("QYWX_KEY"):
        return
    print("企业微信机器人服务启动")

    origin = "https://qyapi.weixin.qq.com"
    if cfg.get("QYWX_ORIGIN"):
        origin = cfg.get("QYWX_ORIGIN")

    url = f"{origin}/cgi-bin/webhook/send?key={cfg.get('QYWX_KEY')}"
    headers = {"Content-Type": "application/json;charset=utf-8"}
    data = {"msgtype": "text", "text": {"content": f"{title}\n\n{content}"}}
    response = requests.post(
        url=url, data=json.dumps(data), headers=headers, timeout=15
    ).json()

    if response["errcode"] == 0:
        print("企业微信机器人推送成功！")
    else:
        print("企业微信机器人推送失败！")


@channel("telegram")
def telegram_bot(title: str, content: str, config=None) -> None:
    """
    使用 telegram 机器人 推送消息。
    """
    cfg = config or push_config
    if not cfg.get("TG_BOT_TOKEN") or not cfg.get("TG_USER_ID"):
        return
    print("tg 服务启动")

    if cfg.get("TG_API_HOST"):
        url = f"{cfg.get('TG_API_HOST')}/bot{cfg.get('TG_BOT_TOKEN')}/sendMessage"
    else:
        url = (
            f"https://api.telegram.org/bot{cfg.get('TG_BOT_TOKEN')}/sendMessage"
        )
    headers = {"Content-Type": "application/x-www-form-urlencoded"}
    payload = {
        "chat_id": str(cfg.get("TG_USER_ID")),
        "text": f"{title}\n\n{content}",
        "disable_web_page_preview": "true",
    }
    proxies = None
    if cfg.get("TG_PROXY_HOST") and cfg.get("TG_PROXY_PORT"):
        if cfg.get("TG_PROXY_AUTH") is not None and "@" not in cfg.get(
            "TG_PROXY_HOST"
        ):
            cfg["TG_PROXY_HOST"] = (
                cfg.get("TG_PROXY_AUTH")
                + "@"
                + cfg.get("TG_PROXY_HOST")
            )
        proxyStr = "http://{}:{}".format(
            cfg.get("TG_PROXY_HOST"), cfg.get("TG_PROXY_PORT")
        )
        proxies = {"http": proxyStr, "https": proxyStr}
    response = requests.post(
        url=url, headers=headers, params=payload, proxies=proxies, timeout=15
    ).json()

    if response["ok"]:
        print("tg 推送成功！")
    else:
        print("tg 推送失败！")


@channel("aibotk")
def aibotk(title: str, content: str, config=None) -> None:
    """
    使用 智能微秘书 推送消息。
    """
    cfg = config or push_config
    if (
        not cfg.get("AIBOTK_KEY")
        or not cfg.get("AIBOTK_TYPE")
        or not cfg.get("AIBOTK_NAME")
    ):
        return
    print("智能微秘书 服务启动")

    if cfg.get("AIBOTK_TYPE") == "room":
        url = "https://api-bot.aibotk.com/openapi/v1/chat/room"
        data = {
            "apiKey": cfg.get("AIBOTK_KEY"),
            "roomName": cfg.get("AIBOTK_NAME"),
            "message": {"type": 1, "content": f"【青龙快讯】\n\n{title}\n{content}"},
        }
    else:
        url = "https://api-bot.aibotk.com/openapi/v1/chat/contact"
        data = {
            "apiKey": cfg.get("AIBOTK_KEY"),
            "name": cfg.get("AIBOTK_NAME"),
            "message": {"type": 1, "content": f"【青龙快讯】\n\n{title}\n{content}"},
        }
    body = json.dumps(data).encode(encoding="utf-8")
    headers = {"Content-Type": "application/json"}
    response = requests.post(url=url, data=body, headers=headers, timeout=15).json()
    print(response)
    if response["code"] == 0:
        print("智能微秘书 推送成功！")
    else:
        print(f'智能微秘书 推送失败！{response["error"]}')


@channel("smtp")
def smtp(title: str, content: str, config=None) -> None:
    """
    使用 SMTP 邮件 推送消息。
    """
    cfg = config or push_config
    if (
        not cfg.get("SMTP_SERVER")
        or not cfg.get("SMTP_SSL")
        or not cfg.get("SMTP_EMAIL")
        or not cfg.get("SMTP_PASSWORD")
        or not cfg.get("SMTP_NAME")
    ):
        return
    print("SMTP 邮件 服务启动")

    message = MIMEText(content, "plain", "utf-8")
    message["From"] = formataddr(
        (
            Header(cfg.get("SMTP_NAME"), "utf-8").encode(),
            cfg.get("SMTP_EMAIL"),
        )
    )
    message["To"] = formataddr(
        (
            Header(cfg.get("SMTP_NAME"), "utf-8").encode(),
            cfg.get("SMTP_EMAIL"),
        )
    )
    message["Subject"] = Header(title, "utf-8")

    try:
        smtp_server = (
            smtplib.SMTP_SSL(cfg.get("SMTP_SERVER"))
            if cfg.get("SMTP_SSL") == "true"
            else smtplib.SMTP(cfg.get("SMTP_SERVER"))
        )
        smtp_server.login(
            cfg.get("SMTP_EMAIL"), cfg.get("SMTP_PASSWORD")
        )
        smtp_server.sendmail(
            cfg.get("SMTP_EMAIL"),
            cfg.get("SMTP_EMAIL"),
            message.as_bytes(),
        )
        smtp_server.close()
        print("SMTP 邮件 推送成功！")
    except Exception as e:
        print(f"SMTP 邮件 推送失败！{e}")


@channel("pushme")
def pushme(title: str, content: str, config=None) -> None:
    """
    使用 PushMe 推送消息。
    """
    cfg = config or push_config
    if not cfg.get("PUSHME_KEY"):
        return
    print("PushMe 服务启动")

    url = (
        cfg.get("PUSHME_URL")
        if cfg.get("PUSHME_URL")
        else "https://push.i-i.me/"
    )
    data = {
        "push_key": cfg.get("PUSHME_KEY"),
        "title": title,
        "content": content,
        "date": cfg.get("date") if cfg.get("date") else "",
        "type": cfg.get("type") if cfg.get("type") else "",
    }
    response = requests.post(url, data=data, timeout=15)

    if response.status_code == 200 and response.text == "success":
        print("PushMe 推送成功！")
    else:
        print(f"PushMe 推送失败！{response.status_code} {response.text}")


@channel("chronocat")
def chronocat(title: str, content: str, config=None) -> None:
    """
    使用 CHRONOCAT 推送消息。
    """
    cfg = config or push_config
    if (
        not cfg.get("CHRONOCAT_URL")
        or not cfg.get("CHRONOCAT_QQ")
        or not cfg.get("CHRONOCAT_TOKEN")
    ):
        return

    print("CHRONOCAT 服务启动")

    user_ids = re.findall(r"user_id=(\d+)", cfg.get("CHRONOCAT_QQ"))
    group_ids = re.findall(r"group_id=(\d+)", cfg.get("CHRONOCAT_QQ"))

    url = f'{cfg.get("CHRONOCAT_URL")}/api/message/send'
    headers = {
        "Content-Type": "application/json",
        "Authorization": f'Bearer {cfg.get("CHRONOCAT_TOKEN")}',
    }

    for chat_type, ids in [(1, user_ids), (2, group_ids)]:
        if not ids:
            continue
        for chat_id in ids:
            data = {
                "peer": {"chatType": chat_type, "peerUin": chat_id},
                "elements": [
                    {
                        "elementType": 1,
                        "textElement": {"content": f"{title}\n\n{content}"},
                    }
                ],
            }
            response = requests.post(url, headers=headers, data=json.dumps(data), timeout=15)
            if response.status_code == 200:
                if chat_type == 1:
                    print(f"QQ个人消息:{ids}推送成功！")
                else:
                    print(f"QQ群消息:{ids}推送成功！")
            else:
                if chat_type == 1:
                    print(f"QQ个人消息:{ids}推送失败！")
                else:
                    print(f"QQ群消息:{ids}推送失败！")


@channel("ntfy")
def ntfy(title: str, content: str, config=None) -> None:
    """
    通过 Ntfy 推送消息
    """
    cfg = config or push_config

    def encode_rfc2047(text: str) -> str:
        """将文本编码为符合 RFC 2047 标准的格式"""
        encoded_bytes = base64.b64encode(text.encode("utf-8"))
        encoded_str = encoded_bytes.decode("utf-8")
        return f"=?utf-8?B?{encoded_str}?="

    if not cfg.get("NTFY_TOPIC"):
        return
    print("ntfy 服务启动")
    priority = "3"
    if not cfg.get("NTFY_PRIORITY"):
        print("ntfy 服务的NTFY_PRIORITY 未设置!!默认设置为3")
    else:
        priority = cfg.get("NTFY_PRIORITY")

    # 使用 RFC 2047 编码 title
    encoded_title = encode_rfc2047(title)

    data = content.encode(encoding="utf-8")
    headers = {"Title": encoded_title, "Priority": priority}  # 使用编码后的 title

    url = cfg.get("NTFY_URL") + "/" + cfg.get("NTFY_TOPIC")
    response = requests.post(url, data=data, headers=headers, timeout=15)
    if response.status_code == 200:  # 使用 response.status_code 进行检查
        print("Ntfy 推送成功！")
    else:
        print("Ntfy 推送失败！错误信息：", response.text)


@channel("wxpusher")
def wxpusher_bot(title: str, content: str, config=None) -> None:
    """
    通过 wxpusher 推送消息。
    支持的环境变量:
    - WXPUSHER_APP_TOKEN: appToken
    - WXPUSHER_TOPIC_IDS: 主题ID, 多个用英文分号;分隔
    - WXPUSHER_UIDS: 用户ID, 多个用英文分号;分隔
    """
    cfg = config or push_config
    if not cfg.get("WXPUSHER_APP_TOKEN"):
        return

    url = "https://wxpusher.zjiecode.com/api/send/message"

    # 处理topic_ids和uids，将分号分隔的字符串转为数组
    topic_ids = []
    if cfg.get("WXPUSHER_TOPIC_IDS"):
        topic_ids = [
            int(id.strip())
            for id in cfg.get("WXPUSHER_TOPIC_IDS").split(";")
            if id.strip()
        ]

    uids = []
    if cfg.get("WXPUSHER_UIDS"):
        uids = [
            uid.strip()
            for uid in cfg.get("WXPUSHER_UIDS").split(";")
            if uid.strip()
        ]

    # topic_ids uids 至少有一个
    if not topic_ids and not uids:
        print("wxpusher 服务的 WXPUSHER_TOPIC_IDS 和 WXPUSHER_UIDS 至少设置一个!!")
        return

    print("wxpusher 服务启动")

    data = {
        "appToken": cfg.get("WXPUSHER_APP_TOKEN"),
        "content": f"<h1>{title}</h1><br/><div style='white-space: pre-wrap;'>{content}</div>",
        "summary": title,
        "contentType": 2,
        "topicIds": topic_ids,
        "uids": uids,
        "verifyPayType": 0,
    }

    headers = {"Content-Type": "application/json"}
    response = requests.post(url=url, json=data, headers=headers, timeout=15).json()

    if response.get("code") == 1000:
        print("wxpusher 推送成功！")
    else:
        print(f"wxpusher 推送失败！错误信息：{response.get('msg')}")


@channel("mediasaber")
def mediasaber_bot(title: str, content: str, config=None) -> None:
    """
    使用 Media Saber 推送消息。
    """
    cfg = config or push_config
    if not cfg.get("MEDIASABER_HOST") or not cfg.get("MEDIASABER_APIKEY"):
        return
    print("Media Saber 服务启动")

    # 构建API URL
    host = cfg.get("MEDIASABER_HOST").rstrip('/')
    url = f"{host}/api/v1/message/openSend"
    
    # 构建请求数据
    data = {
        "title": title,
        "content": content
    }
    
    # 构建请求头
    headers = {
        "Content-Type": "application/json",
        "apiKey": cfg.get("MEDIASABER_APIKEY")
    }
    
    try:
        response = requests.post(
            url=url, 
            data=json.dumps(data), 
            headers=headers, 
            timeout=15
        )
        
        if response.status_code == 200:
            print("Media Saber 推送成功！")
        else:
            print(f"Media Saber 推送失败！状态码：{response.status_code}，响应：{response.text}")
    except Exception as e:
        print(f"Media Saber 推送失败！错误：{str(e)}")


def parse_headers(headers):
    if not headers:
        return {}

    parsed = {}
    lines = headers.split("\n")

    for line in lines:
        i = line.find(":")
        if i == -1:
            continue

        key = line[:i].strip().lower()
        val = line[i + 1 :].strip()
        # 规范化：移除键中的所有空格；值中折叠多余空格
        key = key.replace(" ", "")
        if val:
            val = " ".join(val.split())
        parsed[key] = parsed.get(key, "") + ", " + val if key in parsed else val

    return parsed


def parse_string(input_string, value_format_fn=None):
    matches = {}
    pattern = r"(\w+):\s*((?:(?!\n\w+:).)*)"
    regex = re.compile(pattern)
    for match in regex.finditer(input_string):
        key, value = match.group(1).strip(), match.group(2).strip()
        try:
            value = value_format_fn(value) if value_format_fn else value
            json_value = json.loads(value)
            matches[key] = json_value
        except:
            matches[key] = value
    return matches


def parse_body(body, content_type, value_format_fn=None):
    # 空或纯文本：直接占位符替换后返回
    if not body or content_type == "text/plain":
        return (value_format_fn(body) if (value_format_fn and body) else body)

    # 统一先做占位符替换
    transformed = value_format_fn(body) if value_format_fn else body

    # JSON：优先按原样JSON解析，失败再退回到 key:value 行解析
    if content_type == "application/json":
        if isinstance(transformed, (dict, list)):
            return json.dumps(transformed)
        if isinstance(transformed, str):
            s = transformed.strip()
            if (s.startswith("{") and s.endswith("}")) or (s.startswith("[") and s.endswith("]")):
                try:
                    obj = json.loads(s)
                    return json.dumps(obj)
                except Exception:
                    pass
        # 退回到 key:value 行文本解析
        parsed = parse_string(transformed, None)
        return json.dumps(parsed)

    # 表单：若已是形如 k=v&k2=v2 的字符串则直接使用；否则从 key:value 行构造
    if content_type == "application/x-www-form-urlencoded":
        if isinstance(transformed, str) and ("=" in transformed):
            return transformed
        parsed = parse_string(transformed, None)
        return urllib.parse.urlencode(parsed, doseq=True)

    # 其它类型：返回占位符替换后的原字符串/对象
    return transformed


@channel("webhook")
def custom_notify(title: str, content: str, config=None) -> None:
    """
    通过 自定义通知 推送消息。
    """
    cfg = config or push_config
    if not cfg.get("WEBHOOK_URL") or not cfg.get("WEBHOOK_METHOD"):
        return

    print("自定义通知服务启动")

    WEBHOOK_URL = cfg.get("WEBHOOK_URL")
    WEBHOOK_METHOD = cfg.get("WEBHOOK_METHOD")
    WEBHOOK_CONTENT_TYPE = cfg.get("WEBHOOK_CONTENT_TYPE")
    WEBHOOK_BODY = cfg.get("WEBHOOK_BODY")
    WEBHOOK_HEADERS = cfg.get("WEBHOOK_HEADERS")

    if "$title" not in WEBHOOK_URL and "$title" not in WEBHOOK_BODY:
        print("请求头或者请求体中必须包含 $title 和 $content")
        return

    headers = parse_headers(WEBHOOK_HEADERS)
    # 如未显式提供 Content-Type，则使用配置中的类型
    if WEBHOOK_CONTENT_TYPE:
        if 'content-type' not in headers and 'Content-Type' not in headers:
            headers['Content-Type'] = WEBHOOK_CONTENT_TYPE
    body = parse_body(
        WEBHOOK_BODY,
        WEBHOOK_CONTENT_TYPE,
        lambda v: v.replace("$title", title.replace("\n", "\\n")).replace(
            "$content", content.replace("\n", "\\n")
        ),
    )
    formatted_url = WEBHOOK_URL.replace(
        "$title", urllib.parse.quote_plus(title)
    ).replace("$content", urllib.parse.quote_plus(content))
    response = requests.request(
        method=WEBHOOK_METHOD, url=formatted_url, headers=headers, timeout=15, data=body
    )

    if response.status_code == 200:
        print("自定义通知推送成功！")
    else:
        print(f"自定义通知推送失败！{response.status_code} {response.text}")


def one() -> str:
    """
    获取一条一言。
    :return:
    """
    urls = [
        "https://v1.hitokoto.cn/",
        "https://yyapi.xpdbk.com/api/ian",
        "https://api.nxvav.cn/api/yiyan/",
    ]

    for url in urls:
        try:
            response = requests.get(url, timeout=15)
            content = ""
            source = "网络"

            try:
                res = response.json()
                if isinstance(res, dict):
                    content = res.get("hitokoto") or res.get("yiyan") or res.get("text") or res.get("content")
                    source = res.get("from") or res.get("source") or res.get("nick") or "网络"
            except Exception:
                # 解析失败，视为纯文本 (如 yyapi.xpdbk.com)
                content = response.text.strip()
            
            if content:
                # 移除可能存在的引号
                if content.startswith('"') and content.endswith('"'):
                    content = content[1:-1]
                return f"{content}    ----{source}"
                
        except Exception:
            continue
            
    return ""


def add_notify_function(config=None):
    notify_function = []
    cfg = config or push_config
    
    # 检查通知渠道是否启用（通过ENABLE_前缀的配置项）
    def is_channel_enabled(channel_name):
        enable_key = f"ENABLE_{channel_name.upper()}"
        return cfg.get(enable_key, True)  # 默认为启用状态
    
    if cfg.get("BARK_PUSH") and is_channel_enabled("bark"):
        notify_function.append(partial(bark, config=cfg))
    if cfg.get("CONSOLE") and is_channel_enabled("console"):
        notify_function.append(partial(console, config=cfg))
    if cfg.get("DD_BOT_TOKEN") and cfg.get("DD_BOT_SECRET") and is_channel_enabled("dingding"):
        notify_function.append(partial(dingding_bot, config=cfg))
    if cfg.get("FSKEY") and is_channel_enabled("feishu"):
        notify_function.append(partial(feishu_bot, config=cfg))
    if cfg.get("GOBOT_URL") and cfg.get("GOBOT_QQ") and is_channel_enabled("go_cqhttp"):
        notify_function.append(partial(go_cqhttp, config=cfg))
    if cfg.get("GOTIFY_URL") and cfg.get("GOTIFY_TOKEN") and is_channel_enabled("gotify"):
        notify_function.append(partial(gotify, config=cfg))
    if cfg.get("IGOT_PUSH_KEY") and is_channel_enabled("igot"):
        notify_function.append(partial(iGot, config=cfg))
    if cfg.get("PUSH_KEY") and is_channel_enabled("serverj"):
        notify_function.append(partial(serverJ, config=cfg))
    if cfg.get("DEER_KEY") and is_channel_enabled("pushdeer"):
        notify_function.append(partial(pushdeer, config=cfg))
    if cfg.get("CHAT_URL") and cfg.get("CHAT_TOKEN") and is_channel_enabled("chat"):
        notify_function.append(partial(chat, config=cfg))
    if cfg.get("PUSH_PLUS_TOKEN") and is_channel_enabled("pushplus"):
        notify_function.append(partial(pushplus_bot, config=cfg))
    if cfg.get("WE_PLUS_BOT_TOKEN") and is_channel_enabled("weplus"):
        notify_function.append(partial(weplus_bot, config=cfg))
    if cfg.get("QMSG_KEY") and cfg.get("QMSG_TYPE") and is_channel_enabled("qmsg"):
        notify_function.append(partial(qmsg_bot, config=cfg))
    if cfg.get("QYWX_AM") and is_channel_enabled("wecom_app"):
        notify_function.append(partial(wecom_app, config=cfg))
    if cfg.get("QYWX_KEY") and is_channel_enabled("wecom_bot"):
        notify_function.append(partial(wecom_bot, config=cfg))
    if cfg.get("TG_BOT_TOKEN") and cfg.get("TG_USER_ID") and is_channel_enabled("telegram"):
        notify_function.append(partial(telegram_bot, config=cfg))
    if (
        cfg.get("AIBOTK_KEY")
        and cfg.get("AIBOTK_TYPE")
        and cfg.get("AIBOTK_NAME")
        and is_channel_enabled("aibotk")
    ):
        notify_function.append(partial(aibotk, config=cfg))
    if (
        cfg.get("SMTP_SERVER")
        and cfg.get("SMTP_SSL")
        and cfg.get("SMTP_EMAIL")
        and cfg.get("SMTP_PASSWORD")
        and cfg.get("SMTP_NAME")
        and is_channel_enabled("smtp")
    ):
        notify_function.append(partial(smtp, config=cfg))
    if cfg.get("PUSHME_KEY") and is_channel_enabled("pushme"):
        notify_function.append(partial(pushme, config=cfg))
    if (
        cfg.get("CHRONOCAT_URL")
        and cfg.get("CHRONOCAT_QQ")
        and cfg.get("CHRONOCAT_TOKEN")
        and is_channel_enabled("chronocat")
    ):
        notify_function.append(partial(chronocat, config=cfg))
    if cfg.get("WEBHOOK_URL") and cfg.get("WEBHOOK_METHOD") and is_channel_enabled("webhook"):
        notify_function.append(partial(custom_notify, config=cfg))
    if cfg.get("NTFY_TOPIC") and is_channel_enabled("ntfy"):
        notify_function.append(partial(ntfy, config=cfg))
    if cfg.get("WXPUSHER_APP_TOKEN") and (
        cfg.get("WXPUSHER_TOPIC_IDS") or cfg.get("WXPUSHER_UIDS")
    ) and is_channel_enabled("wxpusher"):
        notify_function.append(partial(wxpusher_bot, config=cfg))
    if cfg.get("MEDIASABER_HOST") and cfg.get("MEDIASABER_APIKEY") and is_channel_enabled("mediasaber"):
        notify_function.append(partial(mediasaber_bot, config=cfg))
    if not notify_function:
        print(f"无推送渠道，请检查通知变量是否正确")
    return notify_function


def send(title: str, content: str, ignore_default_config: bool = False, **kwargs):
    effective_config = push_config.copy()
    if ignore_default_config:
        effective_config = kwargs
    else:
        effective_config.update(kwargs)

    if not content:
        print(f"{title} 推送内容为空！")
        return

    # 根据标题跳过一些消息推送，环境变量：SKIP_PUSH_TITLE 用回车分隔
    skipTitle = os.getenv("SKIP_PUSH_TITLE")
    if skipTitle:
        if title in re.split("\n", skipTitle):
            print(f"{title} 在SKIP_PUSH_TITLE环境变量内，跳过推送！")
            return

    hitokoto = effective_config.get("HITOKOTO")
    content += "\n\n" + one() if hitokoto != "false" else ""

    notify_function = add_notify_function(effective_config)
    # partial objects have a 'func' attribute that points to the original function
    # and the original function has __name__
    ts = [
        threading.Thread(target=mode, args=(title, content), name=mode.func.__name__)
        for mode in notify_function
    ]
    [t.start() for t in ts]
    [t.join() for t in ts]


def main():
    send("title", "content")


if __name__ == "__main__":
    main()
