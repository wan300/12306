"""Normalize published 12306 price fields to integer fen, without estimates."""

import re
from decimal import Decimal, InvalidOperation


def decode_price_segments(value: str) -> dict[str, int]:
    # Official yp_info_new: seat code + five digits (tenths of yuan) + inventory.
    # Inventory >= 3000 denotes standing inventory, even when its code is O/1.
    if not isinstance(value, str) or not value or len(value) % 10:
        return {}
    prices: dict[str, int] = {}
    for offset in range(0, len(value), 10):
        segment = value[offset:offset + 10]
        if not re.fullmatch(r"[A-Z0-9][0-9]{9}", segment):
            return {}
        code = "WZ" if int(segment[6:]) >= 3000 else segment[0]
        fen = int(segment[1:6]) * 10
        if fen > 0:
            prices[code] = min(prices.get(code, fen), fen)
    return prices


def parse_yuan(value: object) -> int | None:
    if not isinstance(value, str):
        return None
    text = value.strip().removeprefix("¥").removeprefix("￥")
    if not re.fullmatch(r"[0-9]{1,6}(?:\.[0-9]{1,2})?", text):
        return None
    try:
        fen = int(Decimal(text) * 100)
        return fen if fen > 0 else None
    except (ValueError, InvalidOperation):
        return None


def parse_price_response(data: object) -> dict[str, int]:
    if not isinstance(data, dict):
        return {}
    # Numeric convenience keys such as "9": "17440" use different units.
    # Only the official formatted-price keys are consumed here.
    mapping = {**{f"A{code}": code for code in "123456789"},
               **{code: code for code in ("M", "O", "P", "S", "F", "I", "J", "A", "D", "WZ")}}
    return {code: fen for source, code in mapping.items()
            if (fen := parse_yuan(data.get(source))) is not None}
