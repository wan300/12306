package com.railhelper.ticket

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

fun ticketPrice(fen: Int): String {
    require(fen > 0)
    return if (fen % 100 == 0) (fen / 100).toString()
    else String.format(Locale.ROOT, "%.2f", fen / 100.0).trimEnd('0')
}

fun minimumTrainPrice(train: TrainInfo): Int? {
    val seats = trainSeats(train)
    val available = seats.filter { train.canBuy && hasTicketCount(it.count) }
    val quoted = if (available.isNotEmpty()) available else seats
    return quoted.mapNotNull { train.pricesFen[it.code] }.filter { it > 0 }.minOrNull()
}

fun trainSegmentKey(train: TrainInfo): String = "${train.trainNo.ifBlank { train.trainCode }}|${train.fromStationCode.ifBlank { train.fromStation }}|${train.toStationCode.ifBlank { train.toStation }}|${train.fromStationNo}|${train.toStationNo}"

enum class WaitlistStatus { UNAVAILABLE, OPEN, FULL }

fun departureMillis(date: String, time: String): Long? = runCatching {
    require(date.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && time.matches(Regex("\\d{2}:\\d{2}")))
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply {
        isLenient = false
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }.parse("$date $time")?.time
}.getOrNull()

fun waitlistStatus(train: TrainInfo, seat: TicketSeat, date: String, student: Boolean = false,
                   now: Long = System.currentTimeMillis()): WaitlistStatus {
    val departure = departureMillis(date, train.startTime) ?: return WaitlistStatus.UNAVAILABLE
    if (!train.waitlistSupported || student || train.trainNo.isBlank() || seat.code == "WZ" ||
        seat.code !in setOf("O", "M", "9", "P", "6", "4", "3", "2", "1", "I", "J", "F", "S", "A", "D") ||
        seat.count !in setOf("无", "0") || departure - now <= 20 * 60_000L) return WaitlistStatus.UNAVAILABLE
    return if (seat.code in train.waitlistRestrictedSeats) WaitlistStatus.FULL else WaitlistStatus.OPEN
}

data class WaitlistChoice(val date: String, val train: TrainInfo, val seat: TicketSeat) {
    val key: String get() = "$date|${trainSegmentKey(train)}|${seat.code}"
    val trainDateKey: String get() = "$date|${train.trainNo.ifBlank { train.trainCode }}"
    val priceFen: Int? get() = train.pricesFen[seat.code]
}

fun waitlistAddError(choices: List<WaitlistChoice>, next: WaitlistChoice): String? = when {
    choices.any { it.key == next.key } -> null
    choices.map { it.date }.toSet().plus(next.date).size > 3 -> "每份候补清单最多选择3个日期"
    choices.map { it.trainDateKey }.toSet().plus(next.trainDateKey).size > 60 -> "每份候补清单最多选择60个车次"
    else -> null
}

fun toggleWaitlist(choices: List<WaitlistChoice>, next: WaitlistChoice): List<WaitlistChoice> =
    if (choices.any { it.key == next.key }) choices.filterNot { it.key == next.key }
    else if (waitlistAddError(choices, next) == null) choices + next else choices

fun reconcileWaitlist(choices: List<WaitlistChoice>, result: TicketQueryResult): List<WaitlistChoice> = choices.mapNotNull { choice ->
    if (choice.date != result.date) return@mapNotNull choice
    val train = result.trains.find { trainSegmentKey(it) == trainSegmentKey(choice.train) } ?: return@mapNotNull choice
    val seat = trainSeats(train).find { it.code == choice.seat.code } ?: return@mapNotNull null
    if (waitlistStatus(train, seat, result.date, result.studentTicket) == WaitlistStatus.OPEN) WaitlistChoice(choice.date, train, seat) else null
}
