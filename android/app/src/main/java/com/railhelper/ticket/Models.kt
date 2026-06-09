package com.railhelper.ticket

import org.json.JSONArray
import org.json.JSONObject

data class User(
    val id: Int,
    val username: String,
    val railwayUsername: String,
    val isLoggedIn: Boolean
)

data class Passenger(
    val name: String,
    val idNo: String,
    val idTypeCode: String,
    val passengerType: String,
    val mobile: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("passenger_name", name)
        .put("passenger_id_no", idNo)
        .put("passenger_id_type_code", idTypeCode)
        .put("passenger_type", passengerType)
        .put("mobile_no", mobile)
}

data class TrainInfo(
    val trainCode: String,
    val fromStation: String,
    val toStation: String,
    val startTime: String,
    val arriveTime: String,
    val duration: String,
    val canBuy: Boolean,
    val businessSeat: String,
    val firstSeat: String,
    val secondSeat: String,
    val softSleeper: String,
    val hardSleeper: String,
    val hardSeat: String,
    val noSeat: String
)

data class TicketTask(
    val id: Int,
    val name: String,
    val fromStation: String,
    val toStation: String,
    val trainDate: String,
    val trainCodes: String?,
    val trainTypes: String?,
    val seatTypes: String,
    val startTimeRange: String?,
    val passengers: String,
    val queryInterval: Int,
    val maxRetryCount: Int,
    val autoSubmit: Boolean,
    val status: String,
    val retryCount: Int,
    val orderId: String?,
    val resultMessage: String?,
    val createdAt: String,
    val startedAt: String?,
    val finishedAt: String?
)

data class TaskLog(
    val id: Int,
    val level: String,
    val message: String,
    val createdAt: String
)

data class TaskDraft(
    val id: Int? = null,
    val name: String = "",
    val fromStation: String = "",
    val toStation: String = "",
    val trainDate: String = "",
    val trainCodes: String = "",
    val trainTypes: Set<String> = setOf("G", "D"),
    val seatTypes: List<String> = listOf("O", "M"),
    val startTimeRange: String = "",
    val passengers: List<Passenger> = emptyList(),
    val queryInterval: String = "5",
    val maxRetryCount: String = "100",
    val infiniteRetry: Boolean = false,
    val autoSubmit: Boolean = true
) {
    fun toJson(): JSONObject {
        val startRange = startTimeRange.trim().ifBlank { JSONObject.NULL }
        val maxRetry = if (infiniteRetry) -1 else maxRetryCount.toIntOrNull() ?: 100
        return JSONObject()
            .put("name", name)
            .put("from_station", fromStation)
            .put("to_station", toStation)
            .put("train_date", trainDate)
            .put("train_codes", JSONArray(trainCodes.split(",").map { it.trim() }.filter { it.isNotBlank() }))
            .put("train_types", JSONArray(trainTypes.toList()))
            .put("seat_types", JSONArray(seatTypes))
            .put("start_time_range", startRange)
            .put("passengers", JSONArray(passengers.map { it.toJson() }))
            .put("query_interval", queryInterval.toIntOrNull() ?: 5)
            .put("max_retry_count", maxRetry)
            .put("auto_submit", autoSubmit)
    }
}

fun parseUser(json: JSONObject?): User? {
    if (json == null) return null
    return User(
        id = json.optInt("id"),
        username = json.optString("username"),
        railwayUsername = json.optString("railway_username", json.optString("username")),
        isLoggedIn = json.optBoolean("is_logged_in")
    )
}

fun parsePassengers(array: JSONArray?): List<Passenger> {
    if (array == null) return emptyList()
    return (0 until array.length()).map { index ->
        val json = array.getJSONObject(index)
        Passenger(
            name = json.optString("passenger_name"),
            idNo = json.optString("passenger_id_no"),
            idTypeCode = json.optString("passenger_id_type_code", "1"),
            passengerType = json.optString("passenger_type", "1"),
            mobile = json.optString("mobile_no")
        )
    }
}

fun parseTrains(array: JSONArray?): List<TrainInfo> {
    if (array == null) return emptyList()
    return (0 until array.length()).map { index ->
        val json = array.getJSONObject(index)
        TrainInfo(
            trainCode = json.optString("train_code"),
            fromStation = json.optString("from_station"),
            toStation = json.optString("to_station"),
            startTime = json.optString("start_time"),
            arriveTime = json.optString("arrive_time"),
            duration = json.optString("duration"),
            canBuy = json.optBoolean("can_buy"),
            businessSeat = json.optString("business_seat", "--"),
            firstSeat = json.optString("first_seat", "--"),
            secondSeat = json.optString("second_seat", "--"),
            softSleeper = json.optString("soft_sleeper", "--"),
            hardSleeper = json.optString("hard_sleeper", "--"),
            hardSeat = json.optString("hard_seat", "--"),
            noSeat = json.optString("no_seat", "--")
        )
    }
}

fun parseTasks(array: JSONArray?): List<TicketTask> {
    if (array == null) return emptyList()
    return (0 until array.length()).map { index -> parseTask(array.getJSONObject(index)) }
}

fun parseTask(json: JSONObject): TicketTask = TicketTask(
    id = json.optInt("id"),
    name = json.optString("name"),
    fromStation = json.optString("from_station"),
    toStation = json.optString("to_station"),
    trainDate = json.optString("train_date"),
    trainCodes = json.optNullableString("train_codes"),
    trainTypes = json.optNullableString("train_types"),
    seatTypes = json.optString("seat_types"),
    startTimeRange = json.optNullableString("start_time_range"),
    passengers = json.optString("passengers", "[]"),
    queryInterval = json.optInt("query_interval", 5),
    maxRetryCount = json.optInt("max_retry_count", 100),
    autoSubmit = json.optInt("auto_submit", 1) == 1 || json.optBoolean("auto_submit", false),
    status = json.optString("status"),
    retryCount = json.optInt("retry_count"),
    orderId = json.optNullableString("order_id"),
    resultMessage = json.optNullableString("result_message"),
    createdAt = json.optString("created_at"),
    startedAt = json.optNullableString("started_at"),
    finishedAt = json.optNullableString("finished_at")
)

fun parseLogs(array: JSONArray?): List<TaskLog> {
    if (array == null) return emptyList()
    return (0 until array.length()).map { index ->
        val json = array.getJSONObject(index)
        TaskLog(
            id = json.optInt("id"),
            level = json.optString("level"),
            message = json.optString("message"),
            createdAt = json.optString("created_at")
        )
    }
}

fun JSONObject.optNullableString(name: String): String? {
    if (isNull(name)) return null
    return optString(name).ifBlank { null }
}

val ticketTypeOptions = listOf(
    "1" to "成人票",
    "2" to "儿童票",
    "3" to "学生票",
    "4" to "残军票"
)

fun normalizeTicketType(type: String): String =
    type.takeIf { value -> ticketTypeOptions.any { it.first == value } } ?: "1"

fun passengerIdentityLabel(type: String): String = when (normalizeTicketType(type)) {
    "1" -> "成人"
    "2" -> "儿童"
    "3" -> "学生"
    "4" -> "残军"
    else -> "成人"
}
