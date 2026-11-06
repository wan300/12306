package com.railhelper.ticket

import android.content.Context
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class WaitlistPassenger(val id: String, val name: String, val maskedId: String, val type: String)
data class NewTrainRange(val date: String, val start: Int, val end: Int, val enabled: Boolean = true)

/** Passenger credentials and upstream tokens never enter this UI state or its saved draft. */
class TicketWaitlistState(private val scope: CoroutineScope) {
    var choices by mutableStateOf(emptyList<WaitlistChoice>()); private set
    var page by mutableStateOf("")
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    var code by mutableStateOf(""); private set
    var sessionId by mutableStateOf(""); private set
    var passengers by mutableStateOf(emptyList<WaitlistPassenger>()); private set
    var selectedPassengers by mutableStateOf(emptySet<String>())
    var deadlines by mutableStateOf(emptyList<String>()); private set
    var deadline by mutableStateOf("")
    var passengerLimit by mutableStateOf(19); private set
    var allowStanding by mutableStateOf(false); private set
    var acceptStanding by mutableStateOf(false)
    var acceptNewTrains by mutableStateOf(false)
    var newTrainSeats by mutableStateOf(setOf<String>())
    var newTrainRanges by mutableStateOf(emptyList<NewTrainRange>())
    var orderDetails by mutableStateOf<JSONObject?>(null); private set
    var agreed by mutableStateOf(false)
    var orderState by mutableStateOf(""); private set
    var orderId by mutableStateOf(""); private set
    var amountFen by mutableStateOf<Int?>(null); private set
    var paymentDeadline by mutableStateOf<Long?>(null); private set
    private var preferences: android.content.SharedPreferences? = null
    private var accountId: Int? = null
    private var loaded = false
    private val pendingSubmission: Boolean get() = accountId?.let { preferences?.getBoolean("pending_$it", false) } == true

    fun attach(context: Context, user: User?) {
        preferences = context.applicationContext.getSharedPreferences("ticket_waitlist_ui", Context.MODE_PRIVATE)
        if (!loaded) {
            loaded = true
            choices = runCatching {
                val array = JSONArray(preferences!!.getString("choices", "[]"))
                (0 until array.length()).mapNotNull { index ->
                    val item = array.getJSONObject(index)
                    val date = item.getString("date")
                    if (!TicketDates.bookable(date)) return@mapNotNull null
                    val train = parseTrains(JSONArray().put(item.getJSONObject("train"))).single()
                    WaitlistChoice(date, train, TicketSeat(item.getString("seat"), item.getString("label"), "无"))
                }.take(600)
            }.getOrDefault(emptyList())
        }
        if (accountId != user?.id) {
            accountId = user?.id
            sessionId = ""; passengers = emptyList(); selectedPassengers = emptySet()
            orderId = ""; amountFen = null; paymentDeadline = null
            orderDetails = null; acceptNewTrains = false; newTrainSeats = emptySet(); newTrainRanges = emptyList()
            orderState = if (user != null && preferences!!.getBoolean("pending_${user.id}", false)) "unknown" else ""
            message = ""; code = ""; page = ""
        }
    }

    private fun persist() {
        // Route/seat/price only. Passenger records, cookies and query secrets stay out of disk drafts.
        val json = JSONArray(choices.map { choice ->
            val t = choice.train
            JSONObject().put("date", choice.date).put("seat", choice.seat.code).put("label", choice.seat.label)
                .put("train", JSONObject().put("train_code", t.trainCode).put("train_no", t.trainNo)
                    .put("from_station", t.fromStation).put("to_station", t.toStation)
                    .put("from_station_code", t.fromStationCode).put("to_station_code", t.toStationCode)
                    .put("from_station_no", t.fromStationNo).put("to_station_no", t.toStationNo)
                    .put("start_time", t.startTime).put("arrive_time", t.arriveTime).put("duration", t.duration)
                    .put("prices_fen", JSONObject(t.pricesFen)))
                .also { it.getJSONObject("train").put("quiet_carriage", t.quietCarriage) }
        })
        preferences?.edit()?.putString("choices", json.toString())?.apply()
    }

    fun toggle(choice: WaitlistChoice): String? {
        if (busy || pendingSubmission) return "请先核对已提交的候补订单状态"
        val error = waitlistAddError(choices, choice)
        if (error != null) return error
        choices = toggleWaitlist(choices, choice); sessionId = ""; persist()
        return null
    }

    fun remove(key: String) {
        if (busy) return
        choices = choices.filterNot { it.key == key }; sessionId = ""; persist()
    }

    fun reconcile(result: TicketQueryResult) {
        if (busy || page == "confirm" || pendingSubmission) return
        choices = reconcileWaitlist(choices, result); persist()
    }

    fun prepare() {
        if (busy || choices.isEmpty()) return
        if ((pendingSubmission || orderState == "pending_payment")) { page = "status"; refreshStatus(); return }
        page = "confirm"; busy = true; message = ""; code = ""
        sessionId = ""; passengers = emptyList(); selectedPassengers = emptySet(); agreed = false
        acceptNewTrains = false
        newTrainSeats = choices.map { it.seat.code }.filter { it in NEW_TRAIN_SEATS.map { seat -> seat.first } }.toSet()
        newTrainRanges = choices.groupBy { it.date }.map { (date, items) ->
            val hours = items.mapNotNull { it.train.startTime.take(2).toIntOrNull() }
            NewTrainRange(date, hours.minOrNull() ?: 0, ((hours.maxOrNull() ?: 23) + 1).coerceAtMost(24))
        }
        val owner = accountId
        val params = JSONObject().put("choices", JSONArray(choices.map {
            JSONObject().put("date", it.date).put("train_no", it.train.trainNo)
                .put("from_code", it.train.fromStationCode).put("to_code", it.train.toStationCode).put("seat_code", it.seat.code)
        }))
        scope.launch {
            try {
                val response = PythonBridge.call("prepare_waitlist", params.toString())
                if (owner != accountId) return@launch
                code = response.optString("code"); message = response.message()
                if (code == "EXISTING_ORDER") {
                    orderState = response.dataObject()?.optString("state").orEmpty()
                    page = "status"
                    val data = response.dataObject()!!
                    orderId = data.optString("order_id")
                    amountFen = data.optInt("amount_fen").takeIf { it > 0 }
                    paymentDeadline = data.optLong("payment_deadline_ms").takeIf { it > 0 }
                    orderDetails = data.optJSONObject("details")
                }
                if (response.success()) {
                    val data = response.dataObject()!!
                    sessionId = data.getString("session_id")
                    val options = data.getJSONArray("deadline_minutes")
                    deadlines = (0 until options.length()).map { options.getString(it) }
                    deadline = if ("360" in deadlines) "360" else deadlines.first()
                    passengerLimit = data.optInt("max_passengers", 19)
                    allowStanding = data.optBoolean("allow_standing"); acceptStanding = false
                    val list = data.optJSONArray("passengers") ?: JSONArray()
                    passengers = (0 until list.length()).map { index -> list.getJSONObject(index).let {
                        WaitlistPassenger(it.getString("id"), it.getString("name"), it.getString("masked_id"), it.optString("ticket_type", "1"))
                    } }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { code = "PREPARE_FAILED"; message = "无法获取候补信息，请稍后重试"
            } finally { busy = false }
        }
    }

    fun submit() {
        if (busy || sessionId.isBlank() || selectedPassengers.isEmpty() || !agreed) return
        busy = true; page = "status"; orderState = "processing"; message = "正在提交候补请求…"
        // Persist the ambiguous state before sending, so process death never causes an automatic resend.
        accountId?.let { preferences?.edit()?.putBoolean("pending_$it", true)?.apply() }
        val params = JSONObject().put("session_id", sessionId).put("confirmed", true)
            .put("passenger_ids", JSONArray(selectedPassengers.toList())).put("deadline_minutes", deadline)
            .put("accept_standing", acceptStanding)
        if (acceptNewTrains) params.put("new_trains", JSONObject().put("seat_codes", JSONArray(newTrainSeats.toList()))
            .put("ranges", JSONArray(newTrainRanges.filter { it.enabled }.map {
                JSONObject().put("date", it.date).put("start_hour", it.start).put("end_hour", it.end)
            })))
        val owner = accountId
        scope.launch {
            try {
                val response = PythonBridge.call("submit_waitlist", params.toString())
                if (owner != accountId) return@launch
                code = response.optString("code"); message = response.message()
                orderState = response.dataObject()?.optString("state")?.takeIf { it.isNotBlank() } ?: "rejected"
                if (orderState == "rejected") accountId?.let { preferences?.edit()?.remove("pending_$it")?.apply() }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { orderState = "unknown"; message = "提交结果尚未确认，请刷新订单状态，勿重复提交"
            } finally { busy = false }
            if (owner == accountId && orderState == "processing") refreshStatus()
        }
    }

    fun refreshStatus() {
        if (busy) return
        page = "status"; busy = true
        val owner = accountId
        scope.launch {
            try {
                val response = PythonBridge.call("query_waitlist_status", JSONObject().put("session_id", sessionId).toString())
                if (owner != accountId) return@launch
                code = response.optString("code"); message = response.message()
                val data = response.dataObject()
                val state = data?.optString("state").orEmpty()
                if (state.isNotBlank()) orderState = state
                if (state == "pending_payment") {
                    orderId = data!!.optString("order_id")
                    amountFen = data.optInt("amount_fen").takeIf { it > 0 }
                    paymentDeadline = data.optLong("payment_deadline_ms").takeIf { it > 0 }
                    orderDetails = data.optJSONObject("details")
                    choices = emptyList(); persist()
                }
                if (state in setOf("failed", "none")) accountId?.let { preferences?.edit()?.remove("pending_$it")?.apply() }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { message = "暂时无法查询候补订单，请稍后刷新"
            } finally { busy = false }
        }
    }

    /** Explicit recovery only after the user has checked the official account. */
    fun resumeAfterOfficialCheck() {
        if (busy || code != "VERIFY_ORDER" || orderState != "unknown") return
        accountId?.let { preferences?.edit()?.remove("pending_$it")?.apply() }
        sessionId = ""; orderState = ""; code = ""; message = ""; page = ""
        orderDetails = null; orderId = ""; amountFen = null; paymentDeadline = null
    }
}
