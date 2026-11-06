package com.railhelper.ticket

import org.junit.Assert.*
import org.junit.Test

class TaskUiRulesTest {
    private fun valid() = TaskDraft(fromStation = "北京南", toStation = "上海虹桥", trainDate = TicketDates.today(),
        passengers = listOf(Passenger("测试", "test-only", "1", "1", "")))

    @Test fun timeWindowRejectsInvalidClocksAndReversedIntervals() {
        listOf("", "00:00-23:59", "08:00-12:00").forEach { assertTrue(it, taskTimeRangeValid(it)) }
        listOf("24:00-25:00", "08:60-12:00", "12:00-08:00", "08:00-08:00", "8:00-12:00", "garbage").forEach { assertFalse(it, taskTimeRangeValid(it)) }
    }
    @Test fun optionalNameDoesNotBlockOtherwiseCompleteTask() { assertNull(taskDraftProblem(valid())) }
    @Test fun missingJourneyPassengersOrSeatPreventsSave() {
        val draft = valid()
        listOf(draft.copy(fromStation = ""), draft.copy(toStation = draft.fromStation), draft.copy(trainDate = "invalid"),
            draft.copy(trainDate = TicketDates.plus(TicketDates.today(), 15)), draft.copy(seatTypes = emptyList()),
            draft.copy(passengers = emptyList())).forEach { assertNotNull(taskDraftProblem(it)) }
    }
    @Test fun finiteRetriesAndQueryFrequencyHaveBounds() {
        listOf("2", "61", "", "9999999999999").forEach { assertNotNull(taskDraftProblem(valid().copy(queryInterval = it))) }
        listOf("3", "60").forEach { assertNull(taskDraftProblem(valid().copy(queryInterval = it))) }
        assertNotNull(taskDraftProblem(valid().copy(maxRetryCount = "0")))
        assertNotNull(taskDraftProblem(valid().copy(maxRetryCount = "9999999999999")))
        assertNull(taskDraftProblem(valid().copy(maxRetryCount = "0", infiniteRetry = true)))
    }
}
