package com.railhelper.ticket

import org.junit.Assert.*
import org.junit.Test

class TicketWaitlistRulesTest {
    private fun train() = TrainInfo("G531", "北京南", "上海虹桥", "12:00", "18:00", "06:00", true,
        "4", "无", "无", "--", "--", "--", "无", trainNo = "240000G5310A", fromStationCode = "VNP",
        toStationCode = "AOH", fromStationNo = "01", toStationNo = "08", waitlistSupported = true,
        pricesFen = mapOf("O" to 49800, "M" to 83600, "9" to 174400))
    private val date = "2026-11-03"
    private val now = departureMillis(date, "08:00")!!
    private val seat = TicketSeat("O", "二等", "无")

    @Test fun preferredFirstClassAndSleeperAliasesUseRealSeatCodes() {
        val t = train().copy(seatTypes = "OMD9IJ", softSleeper = "无", hardSleeper = "2",
            otherSeats = listOf(TicketSeat("D", "优选一等", "无"), TicketSeat("I", "一等卧", "无")))
        val seats = trainSeats(t)
        assertEquals(1, seats.count { it.code == "I" })
        assertFalse(seats.any { it.code in setOf("3", "4") })
        assertEquals("二等卧", seats.single { it.code == "J" }.label)
        assertEquals(WaitlistStatus.OPEN, waitlistStatus(t, seats.single { it.code == "D" }, date, now = now))
    }

    @Test fun currencyPreservesHalfYuanAndCentsWithoutZeroOrFloatingArtifacts() {
        assertEquals("498", ticketPrice(49800))
        assertEquals("498.5", ticketPrice(49850))
        assertEquals("1.01", ticketPrice(101))
    }
    @Test fun startingPriceUsesAvailableSeatAndDoesNotInventMissingQuote() {
        assertEquals(174400, minimumTrainPrice(train()))
        assertNull(minimumTrainPrice(train().copy(pricesFen = mapOf("O" to 49800))))
        assertEquals(49800, minimumTrainPrice(train().copy(businessSeat = "无")))
        assertNull(minimumTrainPrice(train().copy(pricesFen = emptyMap())))
    }
    @Test fun soldOutSeatNeedsExplicitServerEligibilityAndCapacity() {
        assertEquals(WaitlistStatus.OPEN, waitlistStatus(train(), seat, date, now = now))
        assertEquals(WaitlistStatus.UNAVAILABLE, waitlistStatus(train().copy(waitlistSupported = false), seat, date, now = now))
        assertEquals(WaitlistStatus.FULL, waitlistStatus(train().copy(waitlistRestrictedSeats = "OM"), seat, date, now = now))
        assertEquals(WaitlistStatus.UNAVAILABLE, waitlistStatus(train(), seat.copy(count = "2"), date, now = now))
        assertEquals(WaitlistStatus.UNAVAILABLE, waitlistStatus(train(), seat.copy(code = "WZ"), date, now = now))
    }
    @Test fun departureCutoffAndMalformedTimeCannotBecomeEligible() {
        assertEquals(WaitlistStatus.OPEN, waitlistStatus(train(), seat, date, now = departureMillis(date, "11:39")!!))
        assertEquals(WaitlistStatus.UNAVAILABLE, waitlistStatus(train(), seat, date, now = departureMillis(date, "11:40")!!))
        assertEquals(WaitlistStatus.UNAVAILABLE, waitlistStatus(train().copy(startTime = "24:99"), seat, date, now = now))
        assertNull(departureMillis("2026-02-30", "12:00"))
    }
    @Test fun segmentAndDateIdentityDoNotCollide() {
        val original = WaitlistChoice(date, train(), seat)
        val segment = original.copy(train = train().copy(toStationCode = "NKH", toStationNo = "04"))
        val day = original.copy(date = "2026-11-04")
        assertNotEquals(original.key, segment.key)
        assertNotEquals(original.key, day.key)
        assertEquals(original.trainDateKey, segment.trainDateKey)
        assertEquals(3, toggleWaitlist(toggleWaitlist(listOf(original), segment), day).size)
        assertEquals(listOf(segment), toggleWaitlist(listOf(original, segment), original))
    }
    @Test fun threeDatesAndSixtyTrainDatesAllowAdditionalSeatOnSameTrain() {
        val dates = (0..2).map { WaitlistChoice(TicketDates.plus(date, it), train(), seat) }
        assertNotNull(waitlistAddError(dates, dates.first().copy(date = TicketDates.plus(date, 3))))
        val sixty = (0..59).map { WaitlistChoice(date, train().copy(trainNo = "TRAIN$it"), seat) }
        assertNotNull(waitlistAddError(sixty, WaitlistChoice(date, train().copy(trainNo = "TRAIN60"), seat)))
        assertNull(waitlistAddError(sixty, sixty.first().copy(seat = TicketSeat("M", "一等", "无"))))
        assertEquals(61, toggleWaitlist(sixty, sixty.first().copy(seat = TicketSeat("M", "一等", "无"))).size)
    }
}
