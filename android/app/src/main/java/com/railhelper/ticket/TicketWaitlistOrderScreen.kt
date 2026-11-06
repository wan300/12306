package com.railhelper.ticket

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONObject.label(key: String): String = if (isNull(key)) "" else optString(key)
private fun JSONObject.price(): String = optInt("amount_fen").takeIf { it > 0 }?.let { "¥${ticketPrice(it)}" }.orEmpty()

@Composable
fun PendingWaitlistOrder(state: TicketWaitlistState, onBack: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val details = state.orderDetails
    val trains = details?.optJSONArray("trains").objects()
    val passengers = details?.optJSONArray("passengers").objects()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showDetails by remember { mutableStateOf(false) }
    var showAmount by remember { mutableStateOf(false) }
    var showNewTrains by remember { mutableStateOf(false) }
    LaunchedEffect(state.orderId, state.paymentDeadline) {
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    val seconds = state.paymentDeadline?.let { ((it - now) / 1000).coerceAtLeast(0) }
    val expired = seconds == 0L
    val remaining = seconds?.let { "${it / 60}分${it % 60}秒" } ?: "待获取"
    Column(Modifier.fillMaxSize().background(OriginColors.Page)) {
        TicketTitleBar("未完成", onBack)
        Column(Modifier.weight(1f).background(Brush.verticalGradient(listOf(OriginColors.Blue, Color(0xFFF4F4F4)), endY = 500f))
            .verticalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.origin_waitlist_clock), null, Modifier.size(23.dp))
                Text(if (expired) "支付期限已到" else "未完成", Modifier.padding(start = 10.dp).weight(1f), color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Medium)
                Text("剩余：$remaining", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Color.White).padding(16.dp)) {
                trains.firstOrNull()?.let { PendingTrainCard(it) }
                if (trains.isEmpty()) Text("车次详情暂未返回，请刷新查询", fontSize = 14.sp, color = OriginColors.TextMuted)
                passengers.forEach { passenger ->
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp).background(Color(0xFFF6F9FB), RoundedCornerShape(5.dp)).padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(passenger.label("name"), fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                            Text(passenger.label("ticket_type"), Modifier.padding(start = 8.dp).border(.5.dp, Color(0xFF91C6FF), RoundedCornerShape(2.dp)).padding(horizontal = 5.dp, vertical = 1.dp), color = OriginColors.Blue, fontSize = 11.sp, lineHeight = 16.sp)
                            Spacer(Modifier.weight(1f))
                            Text(passenger.price(), color = WaitlistOrange, fontSize = 16.sp, lineHeight = 22.sp)
                        }
                        Text(passenger.label("id_type"), Modifier.padding(top = 6.dp), color = OriginColors.TextMuted, fontSize = 13.sp, lineHeight = 20.sp)
                        Text(passenger.label("masked_id"), fontSize = 13.sp, lineHeight = 20.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Color.White).clickable { showDetails = true }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("候补明细", Modifier.weight(1f), fontSize = 16.sp)
                Text("详情 ›", color = OriginColors.Blue, fontSize = 16.sp)
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Color.White).padding(horizontal = 16.dp)) {
                PendingInfoRow("截止兑现时间：", details?.label("deadline_minutes")?.takeIf { it.isNotBlank() }?.let(::deadlineLabel) ?: "待获取")
                PendingInfoRow("是否接受无座：", yesNo(details?.label("accept_standing")))
                Row(Modifier.fillMaxWidth().heightIn(min = 46.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("是否接受新增列车：", Modifier.weight(1f), fontSize = 13.sp, color = OriginColors.TextMuted)
                    Text(yesNo(details?.label("accept_new_trains")), fontSize = 13.sp)
                    if (details?.label("accept_new_trains") == "Y") TextButton(onClick = { showNewTrains = !showNewTrains }) {
                        Text(if (showNewTrains) "折叠 ⌃" else "展开 ⌄", fontSize = 13.sp)
                    }
                }
                if (details?.label("accept_new_trains") == "Y" && showNewTrains) {
                    Column(Modifier.fillMaxWidth().background(Color(0xFFF9F9F9), RoundedCornerShape(5.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val first = trains.firstOrNull()
                    Text("发到站：${first?.label("from_station").orEmpty()} — ${first?.label("to_station").orEmpty()}", fontSize = 13.sp, lineHeight = 20.sp)
                    val seats = details.optJSONArray("new_train_seats")
                    Text("席别：" + if (seats != null && seats.length() > 0) (0 until seats.length()).joinToString("、") { seats.optString(it) } else "待获取", fontSize = 13.sp, lineHeight = 20.sp)
                    Text("出发时段：", fontSize = 13.sp, lineHeight = 20.sp)
                    val ranges = details.optJSONArray("new_train_ranges").objects()
                    ranges.forEach { range ->
                        val hours = range.label("hours")
                        val interval = if (hours.length == 4) "${hours.take(2).toIntOrNull()}点—${hours.takeLast(2).toIntOrNull()}点" else hours
                        Text("${range.label("date")}  $interval", fontSize = 13.sp, lineHeight = 20.sp)
                    }
                    }
                }
                HorizontalDivider(color = Color(0xFFF5F5F5))
                val reserve = details?.label("reserve_time").orEmpty()
                if (reserve.isNotBlank()) PendingInfoRow("下单日期：", reserve.take(10).replace('-', '.'))
                PendingInfoRow("候补单号：", "${state.orderId.take(2)}****${state.orderId.takeLast(4)}", divider = false)
            }
            state.paymentDeadline?.let { deadline ->
                val date = SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }.format(Date(deadline))
                Text("支付截止：$date。${if (expired) "请刷新核对最新订单状态。" else "当前订单尚未付款。"}", Modifier.padding(8.dp), fontSize = 13.sp, color = OriginColors.TextSecondary)
            }
            if (state.code != "PENDING_PAYMENT" && state.message.isNotBlank()) Text(state.message, Modifier.padding(8.dp), color = OriginColors.TextSecondary, fontSize = 13.sp)
        }
        Column(Modifier.fillMaxWidth().background(Color.White)) {
            Text("候补票款按本单各候补需求中票款最高的额度计算", Modifier.fillMaxWidth().background(Color(0xFFFFF5EF)).padding(14.dp), color = Color(0xFFFF6500), fontSize = 13.sp)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("候补票款：", color = OriginColors.TextSecondary, fontSize = 15.sp)
                Text(state.amountFen?.let { "¥${ticketPrice(it)}" } ?: "待获取", Modifier.weight(1f), fontSize = 25.sp, color = Color(0xFFFF6500))
                Text("明细 ${if (showAmount) "⌄" else "⌃"}", Modifier.clickable { showAmount = !showAmount }, fontSize = 14.sp, color = OriginColors.TextMuted)
            }
            if (showAmount) Text("${passengers.size}位乘车人，候补票款 ${state.amountFen?.let { "¥${ticketPrice(it)}" } ?: "待获取"}", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 13.sp)
            HorizontalDivider(color = OriginColors.Divider)
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = state::refreshStatus, enabled = !state.busy, modifier = Modifier.weight(1f).heightIn(min = 44.dp), shape = RoundedCornerShape(5.dp)) {
                    Text(if (state.busy) "正在查询…" else "刷新状态")
                }
                Button(onClick = { openOfficialRailway(context, onMessage) }, modifier = Modifier.weight(1.3f).heightIn(min = 44.dp), shape = RoundedCornerShape(5.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6500))) { Text("查看官方订单", fontSize = 16.sp) }
            }
        }
    }
    if (showDetails) AlertDialog(onDismissRequest = { showDetails = false }, title = { Text("候补明细") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            trains.forEach { PendingTrainCard(it); HorizontalDivider(color = OriginColors.Divider) }
        }
    }, confirmButton = { TextButton(onClick = { showDetails = false }) { Text("关闭") } })
}

private fun yesNo(value: String?): String = when (value) { "Y" -> "是"; "N" -> "否"; else -> "待获取" }

@Composable
private fun PendingInfoRow(label: String, value: String, divider: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = OriginColors.TextMuted, fontSize = 13.sp, lineHeight = 20.sp)
        Text(value, Modifier.weight(1.1f), textAlign = TextAlign.End, color = OriginColors.TextPrimary, fontSize = 13.sp, lineHeight = 20.sp)
    }
    if (divider) HorizontalDivider(color = Color(0xFFF5F5F5))
}

@Composable
private fun PendingTrainCard(train: JSONObject) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(train.label("start_time"), fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
            Text(train.label("from_station"), fontSize = 14.sp, lineHeight = 22.sp)
        }
        Column(Modifier.weight(1.1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(train.label("train_code"), fontSize = 17.sp)
            Image(painterResource(R.drawable.origin_waitlist_route), null, Modifier.fillMaxWidth().height(4.dp))
            val depart = departureMillis(train.label("date"), train.label("start_time"))
            val arrive = departureMillis(train.label("arrive_date").ifBlank { train.label("date") }, train.label("arrive_time"))
            if (depart != null && arrive != null && arrive >= depart) {
                val mins = (arrive - depart) / 60_000
                Text("历时${mins / 60}小时${mins % 60}分", fontSize = 11.sp, color = OriginColors.TextMuted)
            }
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(train.label("arrive_time"), fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
            Text(train.label("to_station"), fontSize = 14.sp, lineHeight = 22.sp)
        }
    }
    val date = train.label("date")
    val dateLabel = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply { isLenient = false }.parse(date)?.let {
            SimpleDateFormat("yyyy年MM月dd日 E", Locale.CHINA).format(it)
        }
    }.getOrNull() ?: date
    Text("乘车时间：$dateLabel", Modifier.padding(top = 12.dp), fontSize = 12.sp, lineHeight = 20.sp, color = OriginColors.TextMuted)
    Text(train.label("seat_name"), fontSize = 12.sp, lineHeight = 20.sp, color = OriginColors.TextMuted)
}
