"""Allowlisted waitlist presentation data and new-train form encoding.

Field names/units follow the official lineUp_payConfirm and lineUp_toPay views.
No raw passenger identifiers, cookies or upstream tokens cross this boundary.
"""
import re
from datetime import datetime, timedelta, timezone
from .ticket_prices import parse_yuan

NEW_TRAIN_SEATS = {"O": "二等座", "M": "一等座", "9": "商务座", "1": "硬座", "4": "软卧", "3": "硬卧"}


def new_train_form(config, trains):
    if config is None:
        return {"add_train_flag": "N", "tmp_train_date": "", "tmp_train_time": "", "add_train_seat_type_code": ""}
    if not isinstance(config, dict):
        raise ValueError("Invalid new-train conditions")
    seats, ranges = config.get("seat_codes"), config.get("ranges")
    dates = {str(t.get("train_date", "")).replace("-", "") for t in trains}
    if not isinstance(seats, list) or not seats or len(seats) > 6 or any(not isinstance(s, str) or s not in NEW_TRAIN_SEATS for s in seats) or len(set(seats)) != len(seats):
        raise ValueError("Invalid new-train seats")
    if not isinstance(ranges, list) or not 1 <= len(ranges) <= 3:
        raise ValueError("Invalid new-train dates")
    seen, date_values, time_values = set(), [], []
    for item in ranges:
        if not isinstance(item, dict):
            raise ValueError("Invalid new-train range")
        date = str(item.get("date", "")).replace("-", "")
        start, end = item.get("start_hour"), item.get("end_hour")
        if not re.fullmatch(r"\d{8}", date) or date not in dates or date in seen or type(start) is not int or type(end) is not int or not 0 <= start < end <= 24:
            raise ValueError("Invalid new-train range")
        seen.add(date); date_values.append(date); time_values.append(f"{start:02d}{end:02d}")
    # Website joins each date/hour range with a trailing #, unlike native RPC.
    return {"add_train_flag": "Y", "tmp_train_date": "#".join(date_values) + "#",
            "tmp_train_time": "#".join(time_values) + "#", "add_train_seat_type_code": "".join(seats)}


def text(value):
    return str(value) if value is not None else ""


def date_text(value):
    value = text(value)
    if re.fullmatch(r"\d{8}(\d{6})?", value):
        return f"{value[:4]}-{value[4:6]}-{value[6:8]}"
    if re.fullmatch(r"\d{13}", value):
        try:
            return datetime.fromtimestamp(int(value) / 1000, timezone(timedelta(hours=8))).strftime("%Y-%m-%d")
        except (ValueError, OverflowError, OSError):
            return ""
    if re.match(r"\d{4}-\d{2}-\d{2}", value):
        return value[:10]
    return value


def clock_text(value):
    value = text(value)
    return value[:2] + ":" + value[2:] if re.fullmatch(r"\d{4}", value) else value


def pending_details(order):
    needs = order.get("needs") or []
    passengers = order.get("passengers") or []
    trains = []
    for n in needs if isinstance(needs, list) else []:
        if not isinstance(n, dict):
            continue
        trains.append({"train_code": text(n.get("board_train_code")), "date": date_text(n.get("train_date")),
            "from_station": text(n.get("from_station_name")), "to_station": text(n.get("to_station_name")),
            "start_time": clock_text(n.get("start_time")), "arrive_time": clock_text(n.get("arrive_time")),
            "arrive_date": date_text(n.get("arrive_date")), "seat_name": text(n.get("seat_name")),
            "amount_fen": parse_yuan(text(n.get("batch_ticket_price")))})
    safe_passengers = []
    for p in passengers if isinstance(passengers, list) else []:
        if not isinstance(p, dict):
            continue
        ident = text(p.get("passenger_id_no"))
        # pre_passenger_price is in jiao; the official template divides by ten.
        price = text(p.get("pre_passenger_price"))
        safe_passengers.append({"name": text(p.get("passenger_name")),
            "masked_id": ident[:3] + "********" + ident[-4:] if len(ident) >= 8 else "已核验",
            "id_type": text(p.get("passenger_id_name")), "ticket_type": text(p.get("ticket_type")),
            "amount_fen": int(price) * 10 if price.isdigit() and int(price) > 0 else None})
    tmps = order.get("tmps") or []
    seat_codes = text(order.get("add_train_seat_type_code"))
    if not seat_codes:
        # Official hb_add_time_temp renders getSeatList(obj.needs) when the
        # pending-order response omits the submission-only seat field.
        seat_codes = "".join(text(n.get("seat_type") or n.get("seat_type_code"))
                             for n in needs if isinstance(n, dict)) if isinstance(needs, list) else ""
    return {"trains": trains, "passengers": safe_passengers,
        "deadline_minutes": text(order.get("realize_limit_time_diff")),
        "accept_standing": order.get("if_receive_wseat") if order.get("if_receive_wseat") in ("Y", "N") else "",
        "accept_new_trains": order.get("accept_tmp_train") if order.get("accept_tmp_train") in ("Y", "N") else "",
        "new_train_ranges": [{"date": date_text(t.get("tmp_train_date")), "hours": text(t.get("tmp_train_time"))}
                             for t in tmps if isinstance(t, dict)],
        "new_train_seats": list(dict.fromkeys(NEW_TRAIN_SEATS[c] for c in seat_codes if c in NEW_TRAIN_SEATS)),
        "reserve_time": date_text(order.get("reserve_time"))}
