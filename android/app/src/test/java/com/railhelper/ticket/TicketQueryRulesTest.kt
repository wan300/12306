package com.railhelper.ticket

import org.junit.Assert.*
import org.junit.Test

class TicketQueryRulesTest {
    @Test fun saleWindowIncludesTodayAndExactlyFourteenMoreDays() {
        assertFalse(TicketDates.bookable("2026-10-31", "2026-11-01"))
        assertTrue(TicketDates.bookable("2026-11-01", "2026-11-01"))
        assertTrue(TicketDates.bookable("2026-11-15", "2026-11-01"))
        assertFalse(TicketDates.bookable("2026-11-16", "2026-11-01"))
    }
    @Test fun dateWindowCrossesMonthAndLeapYearBoundaries() {
        assertEquals("2028-03-01", TicketDates.plus("2028-02-28", 2))
        assertEquals("2027-01-01", TicketDates.plus("2026-12-31", 1))
        assertEquals("2026-11-01", TicketDates.clamp("invalid", "2026-11-01"))
        assertEquals("2026-11-01", TicketDates.clamp("2026-11-02junk", "2026-11-01"))
        assertEquals("2026-11-01", TicketDates.clamp("2026-02-30", "2026-11-01"))
        assertEquals("2026-11-15", TicketDates.clamp("2026-12-01", "2026-11-01"))
    }
    @Test fun sixDateStripAlwaysIncludesSelectionAndStaysWithinSaleWindow() {
        for (offset in 0..14) {
            val selected = TicketDates.plus("2026-11-01", offset)
            val dates = TicketDates.strip(selected, "2026-11-01")
            assertEquals(6, dates.size)
            assertTrue(selected in dates)
            assertTrue(dates.all { TicketDates.bookable(it, "2026-11-01") })
        }
    }
    @Test fun stationParsingSupportsActualShortPinyinFieldAndSkipsMalformedData() {
        val source = "var station_names='@bji|北京|BJP|beijing|bj|2|0357|北京|||@bjn|北京南|VNP|beijingnan|bjn|3|0357|北京|||@invalid';"
        val stations = parseTicketStations(source)
        assertEquals(2, stations.size)
        assertEquals("北京", findTicketStations(stations, " BJ ").first().name)
        assertEquals("北京南", findTicketStations(stations, "VNP").single().name)
        assertEquals("北京", findTicketStations(stations, "beijing").first().name)
        assertEquals(2, findTicketStations(stations, "北京").size)
        assertTrue(findTicketStations(stations, "不存在").isEmpty())
    }
    @Test fun unavailableAndPlaceholderInventoryNeverCountsAsAvailable() {
        listOf("", "无", "--", "*", "0", "-1", "候补").forEach { assertFalse(it, hasTicketCount(it)) }
        assertTrue(hasTicketCount("有"))
        assertTrue(hasTicketCount("2"))
    }
    @Test fun commonCityRanksFirstWhenStationInitialsCollide() {
        val stations = listOf(TicketStation("宝鸡", "BAY", "baoji", "bj"), TicketStation("北京", "BJP", "beijing", "bj"))
        assertEquals("北京", findTicketStations(stations, "bj").first().name)
        assertEquals("宝鸡", findTicketStations(stations, "baoji").single().name)
    }
    @Test fun queryFailuresNeverExposeBridgeExceptionsOrRawInput() {
        assertEquals("未找到出发站，请重新选择", ticketQueryError("未找到出发站: raw-input"))
        assertEquals("出发日期不可用，请重新选择", ticketQueryError("出发日期不能超过15天"))
        assertEquals("暂时无法查询车票，请稍后重试", ticketQueryError("查询请求失败: TLS exception https://private.invalid?token=secret"))
        assertEquals("暂时无法查询车票，请稍后重试", ticketQueryError(""))
    }
    @Test fun seatsIncludeHardSeatAndExcludeUnsupportedSleeperPlaceholders() {
        val seats = trainSeats(train().copy(secondSeat = "--", hardSeat = "有", hardSleeper = "无"))
        assertEquals(listOf("3", "1"), seats.map { it.code })
    }
    @Test fun availabilityFilterHonorsSelectedSeatAndCanBuy() {
        val trains = listOf(train("G1"), train("G2").copy(secondSeat = "无", firstSeat = "有"), train("G3").copy(canBuy = false))
        val filtered = filterTicketTrains(trains, TicketFilters(availableOnly = true, seatCodes = setOf("O")), "depart")
        assertEquals(listOf("G1"), filtered.map { it.trainCode })
    }
    @Test fun categoryAndStationFiltersComposeAndDurationIsNumeric() {
        val trains = listOf(train("G1", "10:00"), train("K2", "9:00").copy(fromStation = "北京", duration = "09:00"), train("D3", "08:00").copy(duration = "02:00"))
        assertEquals(listOf("D3", "G1"), filterTicketTrains(trains, TicketFilters(category = "high"), "duration").map { it.trainCode })
        assertEquals(listOf("K2"), filterTicketTrains(trains, TicketFilters(category = "normal", fromStations = setOf("北京")), "depart").map { it.trainCode })
        assertTrue(filterTicketTrains(trains, TicketFilters(toStations = setOf("广州")), "depart").isEmpty())
        assertEquals(Int.MAX_VALUE, durationInMinutes("bad"))
        assertEquals(Int.MAX_VALUE, durationInMinutes("02:99"))
    }
    private fun train(code: String = "G35", time: String = "08:00") = TrainInfo(code, "北京南", "上海虹桥", time, "12:27", "04:27", true, "--", "--", "1", "--", "--", "--", "--")
}
