package com.railhelper.ticket

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun QueryScreen(
    user: User? = null,
    onTasks: (() -> Unit)? = null,
    onAccount: (() -> Unit)? = null,
    onCreateTask: (TaskDraft) -> Unit,
    onResults: (TicketQueryResult) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val memory = remember { TicketQueryMemory(context) }
    var from by rememberSaveable { mutableStateOf(memory.value("from")) }
    var to by rememberSaveable { mutableStateOf(memory.value("to")) }
    var date by rememberSaveable { mutableStateOf(TicketDates.clamp(memory.value("date", TicketDates.today()))) }
    var student by rememberSaveable { mutableStateOf(memory.value("student") == "true") }
    var picker by rememberSaveable { mutableStateOf("") }
    var history by remember { mutableStateOf(memory.history()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(from, to, date, student) { memory.save(from, to, date, student) }

    fun query() {
        if (loading) return
        if (from.isBlank() || to.isBlank()) { onMessage("请选择出发站和到达站"); return }
        if (from == to) { onMessage("出发站和到达站不能相同"); return }
        if (!TicketDates.bookable(date)) { date = TicketDates.today(); onMessage("出发日期已更新，请重新查询"); return }
        scope.launch {
            loading = true
            error = null
            try {
                val response = queryTicketBridge(from, to, date, student)
                if (response.success()) {
                    memory.addRoute(from, to)
                    onResults(TicketQueryResult(from, to, date, "", student, false, parseTrains(response.dataObject()?.optJSONArray("trains"))))
                } else { error = ticketQueryError(response.message()) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { error = "暂时无法查询车票，请稍后重试"
            } finally { loading = false }
        }
    }
    Column(Modifier.fillMaxSize().background(OriginColors.Page)) {
        TicketTitleBar("火车票")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 12.dp)) {
            Surface(color = Color.White, shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(horizontal = 23.dp, vertical = 14.dp)) {
                    Row(Modifier.fillMaxWidth().height(47.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(from.ifBlank { "出发地" }, fontSize = 23.sp, fontWeight = FontWeight.Bold,
                            color = if (from.isBlank()) OriginColors.TextMuted else OriginColors.TextPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).clickable(enabled = !loading) { picker = "from" }.padding(vertical = 8.dp))
                        IconButton(onClick = { val previous = from; from = to; to = previous }, enabled = !loading, modifier = Modifier.size(44.dp)) {
                            Box(Modifier.size(24.dp)) {
                                Image(painterResource(R.drawable.origin_hp_change_station_circle_normal), "交换出发到达站", Modifier.fillMaxSize())
                                Image(painterResource(R.drawable.origin_hp_change_station_logo_normal), null, Modifier.fillMaxSize())
                            }
                        }
                        Text(to.ifBlank { "到达地" }, fontSize = 23.sp, fontWeight = FontWeight.Bold,
                            color = if (to.isBlank()) OriginColors.TextMuted else OriginColors.TextPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f).clickable(enabled = !loading) { picker = "to" }.padding(vertical = 8.dp))
                    }
                    HorizontalDivider(thickness = .5.dp, color = Color(0xFFF0F0F0))
                    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).fillMaxHeight().clickable(enabled = !loading) { picker = "date" }, verticalAlignment = Alignment.CenterVertically) {
                            Text(TicketDates.monthDay(date), fontSize = 20.sp, color = OriginColors.TextPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text(if (date == TicketDates.today()) "今天" else TicketDates.weekday(date), fontSize = 13.sp, color = OriginColors.TextSecondary)
                        }
                        TicketCheck("学生票", student, { if (!loading) student = it })
                    }
                    HorizontalDivider(thickness = .5.dp, color = Color(0xFFF0F0F0))
                    Spacer(Modifier.height(12.dp))
                    TicketActionButton(if (loading) "正在查询…" else "查询车票", !loading, ::query, Modifier.fillMaxWidth())
                    if (history.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                            history.forEach { route ->
                                Text("${route.first}--${route.second}", fontSize = 12.sp, color = OriginColors.TextMuted, maxLines = 1,
                                    modifier = Modifier.clickable(enabled = !loading) { from = route.first; to = route.second }.padding(vertical = 8.dp))
                            }
                        }
                        Text("清除历史", fontSize = 12.sp, color = OriginColors.TextMuted,
                            modifier = Modifier.clickable { memory.clearRoutes(); history = emptyList() }.padding(start = 10.dp, top = 8.dp, bottom = 8.dp))
                    }
                }
            }
            error?.let { message ->
                Text(message, color = OriginColors.Danger, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
            }
        }
    }
    if (picker.isNotEmpty()) TicketFullscreen(onBack = { picker = "" }) {
        if (picker == "date") TicketCalendarScreen(date, onBack = { picker = "" }, onSelect = { date = it; picker = "" })
        else TicketStationScreen(onBack = { picker = "" }, onSelect = { if (picker == "from") from = it else to = it; picker = "" })
    }
}

internal suspend fun queryTicketBridge(from: String, to: String, date: String, student: Boolean, trainType: String = ""): JSONObject {
    val params = JSONObject().put("from_station", from).put("to_station", to).put("train_date", date)
        .put("ticket_type", if (student) "0X00" else "ADULT").put("only_has_ticket", false)
    if (trainType.isNotBlank()) params.put("train_types", trainType)
    return PythonBridge.call("query_tickets", params.toString())
}
