package com.railhelper.ticket

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Date-only values use UTC; today is derived from the device's local calendar. */
object TicketDates {
    private const val DAY = 86_400_000L
    private fun formatter() = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }
    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(java.util.Date())
    fun millis(date: String): Long {
        require(date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "Invalid calendar date" }
        return requireNotNull(formatter().parse(date)).time
    }
    fun format(millis: Long): String = formatter().format(java.util.Date(millis))
    fun plus(date: String, days: Int): String = format(millis(date) + days * DAY)
    fun bookable(date: String, today: String = today()): Boolean =
        millis(date) in millis(today)..millis(plus(today, 14))
    fun clamp(date: String, today: String = today()): String = runCatching {
        format(millis(date).coerceIn(millis(today), millis(plus(today, 14))))
    }.getOrDefault(today)
    fun calendar(date: String): Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = millis(date)
    }
    fun weekday(date: String): String = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")[calendar(date).get(Calendar.DAY_OF_WEEK) - 1]
    fun monthDay(date: String): String = calendar(date).let { "${it.get(Calendar.MONTH) + 1}月${it.get(Calendar.DAY_OF_MONTH)}日" }
    fun strip(selected: String, today: String = today()): List<String> {
        val valid = clamp(selected, today)
        val start = minOf(millis(valid), millis(plus(today, 9)))
        return (0..5).map { format(start + it * DAY) }
    }
}

data class TicketStation(val name: String, val code: String, val pinyin: String, val initials: String) {
    val letter: String get() = pinyin.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
}

val ticketHotStations = listOf("北京", "上海", "广州", "深圳", "天津", "重庆", "成都", "长沙", "武汉", "杭州", "南京", "西安")

fun parseTicketStations(source: String): List<TicketStation> = source.split('@').mapNotNull { entry ->
    val fields = entry.split('|')
    if (fields.size < 7 || fields[1].isBlank() || fields[2].length != 3) null
    else TicketStation(fields[1], fields[2], fields[3], fields[4])
}.distinctBy { it.code }.sortedBy { it.pinyin }

fun findTicketStations(stations: List<TicketStation>, input: String): List<TicketStation> {
    val key = input.trim().lowercase(Locale.ROOT)
    if (key.isEmpty()) return emptyList()
    return stations.filter { key in it.name || key in it.pinyin || key in it.initials || key == it.code.lowercase(Locale.ROOT) }
        .sortedWith(compareBy<TicketStation> { if (it.name == key || it.pinyin == key || it.initials == key) 0 else 1 }
            .thenBy { if (it.name.startsWith(key) || it.pinyin.startsWith(key)) 0 else 1 }
            .thenBy { if (it.name in ticketHotStations) 0 else 1 }.thenBy { it.pinyin })
        .take(80)
}

data class TicketSeat(val code: String, val label: String, val count: String)

fun hasTicketCount(value: String): Boolean = value == "有" || (value.toIntOrNull() ?: 0) > 0

/** The legacy bridge can include exception text; only expose known, actionable failures. */
fun ticketQueryError(message: String): String = when {
    message.startsWith("未找到出发站") -> "未找到出发站，请重新选择"
    message.startsWith("未找到到达站") -> "未找到到达站，请重新选择"
    message.startsWith("出发日期不能") || message.startsWith("日期格式错误") -> "出发日期不可用，请重新选择"
    else -> "暂时无法查询车票，请稍后重试"
}

fun trainSeats(train: TrainInfo): List<TicketSeat> = listOf(
    TicketSeat("O", "二等", train.secondSeat), TicketSeat("M", "一等", train.firstSeat),
    TicketSeat("9", "商务", train.businessSeat), TicketSeat("4", "软卧", train.softSleeper),
    TicketSeat("3", "硬卧", train.hardSleeper), TicketSeat("1", "硬座", train.hardSeat),
    TicketSeat("WZ", "无座", train.noSeat),
).filter { it.count.isNotBlank() && it.count !in listOf("--", "*") }.plus(train.otherSeats).map { seat ->
    when {
        seat.code == "4" && "I" in train.seatTypes -> seat.copy(code = "I", label = "一等卧")
        seat.code == "3" && "J" in train.seatTypes -> seat.copy(code = "J", label = "二等卧")
        seat.code == "6" && "A" in train.seatTypes -> seat.copy(code = "A", label = "高级动卧")
        seat.code == "O" && "S" in train.seatTypes -> seat.copy(code = "S", label = "二等包座")
        else -> seat
    }
}.distinctBy { it.code }.sortedBy { listOf("O", "S", "M", "D", "P", "9", "A", "6", "I", "4", "J", "3", "F", "2", "1", "WZ").indexOf(it.code) }

data class TicketFilters(
    val category: String = "all",
    val availableOnly: Boolean = false,
    val seatCodes: Set<String> = emptySet(),
    val fromStations: Set<String> = emptySet(),
    val toStations: Set<String> = emptySet(),
) {
    val active: Boolean get() = this != TicketFilters()
}

fun filterTicketTrains(trains: List<TrainInfo>, filters: TicketFilters, sort: String): List<TrainInfo> = trains.filter { train ->
    val highSpeed = train.trainCode.firstOrNull() in setOf('G', 'D', 'C')
    val seats = trainSeats(train).filter { filters.seatCodes.isEmpty() || it.code in filters.seatCodes }
    (filters.category == "all" || (filters.category == "high") == highSpeed) &&
        (filters.fromStations.isEmpty() || train.fromStation in filters.fromStations) &&
        (filters.toStations.isEmpty() || train.toStation in filters.toStations) &&
        (filters.seatCodes.isEmpty() || seats.isNotEmpty()) &&
        (!filters.availableOnly || (train.canBuy && seats.any { hasTicketCount(it.count) }))
}.sortedWith(if (sort == "duration") compareBy<TrainInfo> { durationInMinutes(it.duration) }.thenBy { it.startTime }
    else compareBy<TrainInfo> { it.startTime }.thenBy { durationInMinutes(it.duration) })

fun durationInMinutes(value: String): Int {
    val parts = value.split(':')
    val hours = parts.getOrNull(0)?.toIntOrNull() ?: return Int.MAX_VALUE
    val minutes = parts.getOrNull(1)?.toIntOrNull() ?: return Int.MAX_VALUE
    return if (parts.size == 2 && hours >= 0 && minutes in 0..59) hours * 60 + minutes else Int.MAX_VALUE
}

fun ticketDuration(value: String): String = durationInMinutes(value).let {
    if (it == Int.MAX_VALUE) value else if (it < 60) "${it}分" else "${it / 60}小时${it % 60}分"
}
