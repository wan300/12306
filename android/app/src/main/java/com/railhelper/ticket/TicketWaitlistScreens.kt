@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.railhelper.ticket

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import kotlin.math.abs
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val WaitlistOrange = Color(0xFFFF8200)

fun openOfficialRailway(context: Context, onMessage: (String) -> Unit) {
    try {
        context.startActivity(Intent().setClassName("com.MobileTicket", "com.MobileTicket.ui.activity.WelcomeGuideActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) { onMessage("未找到铁路12306应用，请手动打开") }
}

@Composable
private fun WaitlistButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
        shape = RoundedCornerShape(5.dp), colors = ButtonDefaults.buttonColors(containerColor = WaitlistOrange)) {
        Text(text, fontSize = 17.sp)
    }
}

@Composable
fun WaitlistCart(state: TicketWaitlistState, onDismiss: () -> Unit, onNext: () -> Unit) {
    var rules by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White, shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp), dragHandle = null) {
        Box(Modifier.fillMaxWidth().height(46.dp), contentAlignment = Alignment.Center) {
            Text("候补列表", fontSize = 19.sp, color = OriginColors.TextPrimary)
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd)) { Text("收起", color = OriginColors.TextSecondary) }
        }
        Text("最多支持3个日期，60个候补车次（选择车次越多，候补成功机会越多）",
            Modifier.fillMaxWidth().background(Color(0xFFFFF5EB)).padding(horizontal = 12.dp, vertical = 6.dp), color = WaitlistOrange, fontSize = 12.sp, lineHeight = 16.sp)
        Column(Modifier.fillMaxWidth().heightIn(max = 330.dp).verticalScroll(rememberScrollState())) {
            state.choices.forEach { choice -> WaitlistTrainRow(choice, onDelete = { state.remove(choice.key) }) }
            if (state.choices.isEmpty()) Text("尚未添加候补需求", Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center, color = OriginColors.TextMuted)
        }
        Box(Modifier.fillMaxWidth().height(42.dp).clickable(onClick = onDismiss).semantics { contentDescription = "添加候补车次" }, contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.origin_waitlist_add_passenger), null, Modifier.size(25.dp))
        }
        Row(Modifier.fillMaxWidth().height(32.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text("还可添加${60 - state.choices.map { it.trainDateKey }.distinct().size}个候补车次", fontSize = 13.sp, color = OriginColors.TextSecondary)
            Text("规则说明", Modifier.clickable { rules = true }.padding(horizontal = 5.dp), color = OriginColors.Blue, fontSize = 13.sp)
        }
        Button(onNext, enabled = state.choices.isNotEmpty() && !state.busy,
            modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(0.dp),
            colors = ButtonDefaults.buttonColors(containerColor = WaitlistOrange)) { Text("下一步", fontSize = 18.sp) }
    }
    if (rules) WaitlistRulesDialog { rules = false }
}

@Composable
private fun WaitlistTrainRow(choice: WaitlistChoice, onDelete: (() -> Unit)? = null, compact: Boolean = false) {
    val t = choice.train
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = if (compact) 6.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${t.startTime} ${t.fromStation}", fontSize = 15.sp, lineHeight = 20.sp, color = OriginColors.TextPrimary, modifier = Modifier.weight(1f))
                Image(painterResource(R.drawable.origin_waitlist_route), null, Modifier.width(27.dp).height(3.dp))
                Text("${t.arriveTime} ${t.toStation}", fontSize = 15.sp, lineHeight = 20.sp, color = OriginColors.TextPrimary, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
            Spacer(Modifier.height(if (compact) 6.dp else 9.dp))
            val label = if (choice.seat.code in setOf("O", "M", "9", "P", "D")) choice.seat.label + "座" else choice.seat.label
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${t.trainCode}　${choice.date.substring(5).replace("-", "月")}日　$label", fontSize = 14.sp, lineHeight = 18.sp, color = OriginColors.TextSecondary)
                if (t.quietCarriage && choice.seat.code == "O") Text("(静)", color = WaitlistOrange, fontSize = 14.sp)
            }
        }
        if (onDelete != null) IconButton(onClick = onDelete, modifier = Modifier.padding(start = 4.dp).size(36.dp)) {
            Image(painterResource(R.drawable.origin_waitlist_delete), "移除${t.trainCode}${choice.seat.label}", Modifier.width(13.dp).height(18.dp))
        }
    }
    HorizontalDivider(thickness = .5.dp, color = OriginColors.Divider)
}

fun deadlineLabel(value: String): String {
    val minutes = value.toIntOrNull() ?: return value
    return "开车前" + when {
        minutes % 1440 == 0 -> "${minutes / 1440}天"
        minutes % 60 == 0 -> "${minutes / 60}小时"
        else -> "${minutes}分钟"
    }
}

@Composable
fun WaitlistCheckoutScreen(state: TicketWaitlistState, onBack: () -> Unit, onLogin: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    var passengerPicker by rememberSaveable { mutableStateOf(false) }
    var deadlinePicker by rememberSaveable { mutableStateOf(false) }
    var confirmation by rememberSaveable { mutableStateOf(false) }
    var rules by rememberSaveable { mutableStateOf(false) }
    var newTrainPicker by rememberSaveable { mutableStateOf(false) }
    val selected = state.passengers.filter { it.id in state.selectedPassengers }
    Column(Modifier.fillMaxSize().background(OriginColors.Page)) {
        TicketTitleBar("确认候补订单", onBack)
        Column(Modifier.weight(1f).background(Brush.verticalGradient(listOf(OriginColors.Blue, Color(0xFFDAEAF6), Color(0xFFEEEFF5)), endY = 1000f))
            .verticalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Color.White)) {
                Text("当前支持选择3个不同日期，共计60个候补车次", Modifier.fillMaxWidth().background(Color(0xFFFFEFDF)).padding(horizontal = 14.dp, vertical = 6.dp), color = WaitlistOrange, fontSize = 13.sp)
                state.choices.forEach { WaitlistTrainRow(it, compact = true) }
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Color(0xFFF1F8FF)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("添加更多候补车次（${state.choices.map { it.trainDateKey }.distinct().size}/60）", fontSize = 16.sp, color = OriginColors.TextPrimary)
                    Text("候补车次越多，兑现机会越多", fontSize = 13.sp, color = Color(0xFF5579A3))
                }
                Button(onClick = onBack, shape = RoundedCornerShape(4.dp), enabled = !state.busy, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("⊕ 去添加") }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Color.White).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("接受新增列车", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("当有新增列车时，系统自动为您兑现", fontSize = 12.sp, color = OriginColors.Blue)
                    }
                    WaitlistSwitch(state.acceptNewTrains, { if (it) newTrainPicker = true else state.acceptNewTrains = false },
                        enabled = state.sessionId.isNotBlank() && !state.busy,
                        modifier = Modifier.semantics { contentDescription = "接受新增列车" })
                }
                if (state.acceptNewTrains) {
                    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = OriginColors.Divider)
                    val train = state.choices.firstOrNull()?.train
                    Text("发到站：${train?.fromStation.orEmpty()} — ${train?.toStation.orEmpty()}", fontSize = 13.sp)
                    Text("席别：" + NEW_TRAIN_SEATS.filter { it.first in state.newTrainSeats }.joinToString("、") { it.second }, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("出发时段：\n" + newTrainSummary(state.newTrainRanges), Modifier.weight(1f), fontSize = 13.sp)
                        TextButton(onClick = { newTrainPicker = true }, enabled = !state.busy) { Text("设置 ›") }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Color.White).padding(horizontal = 16.dp, vertical = 2.dp)) {
                selected.forEach { passenger ->
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(passenger.name, fontSize = 16.sp)
                            Text(passenger.maskedId, fontSize = 13.sp, color = OriginColors.TextMuted)
                        }
                        Text(ticketTypeName(passenger.type), fontSize = 13.sp, color = OriginColors.TextSecondary)
                    }
                }
                TextButton(onClick = { passengerPicker = true }, enabled = state.sessionId.isNotBlank() && !state.busy, modifier = Modifier.fillMaxWidth().height(40.dp)) {
                    Image(painterResource(R.drawable.origin_waitlist_add_passenger), null, Modifier.size(23.dp))
                    Spacer(Modifier.width(8.dp)); Text(if (selected.isEmpty()) "添加乘车人" else "修改乘车人", color = WaitlistOrange, fontSize = 16.sp)
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Color.White).padding(14.dp)) {
                Row(Modifier.fillMaxWidth().clickable(enabled = state.deadlines.isNotEmpty() && !state.busy) { deadlinePicker = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("截止兑现时间", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(if (state.deadline.isBlank()) "待获取" else deadlineLabel(state.deadline), fontSize = 15.sp, color = OriginColors.Blue)
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = OriginColors.Blue)
                }
                Text("最晚可至开车前20分钟，您可根据实际情况自行选择。", fontSize = 13.sp, lineHeight = 21.sp, color = OriginColors.TextMuted)
                if (state.allowStanding) {
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("是否接受无座", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        WaitlistSwitch(state.acceptStanding, onCheckedChange = { state.acceptStanding = it }, enabled = !state.busy)
                    }
                    Text("如有可供发售的无座车票时，系统将自动为您兑现。", fontSize = 13.sp, lineHeight = 21.sp, color = OriginColors.TextMuted)
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Color.White).padding(16.dp)) {
                Text("ⓘ 温馨提示", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = OriginColors.TextSecondary)
                Text("各候补需求按所选时间分别截止兑现，请根据出行安排合理选择。票价以订单生成后的实际预付款为准。", fontSize = 13.sp, lineHeight = 22.sp, color = OriginColors.TextMuted)
            }
            if (state.busy) Box(Modifier.fillMaxWidth().padding(22.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            if (state.message.isNotBlank()) Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(state.message, color = OriginColors.TextSecondary, fontSize = 14.sp, lineHeight = 22.sp)
                if (!state.busy) Row {
                    if (state.code == "LOGIN_REQUIRED") TextButton(onClick = onLogin) { Text("登录12306账号") }
                    else TextButton(onClick = state::prepare) { Text("重新获取") }
                    TextButton(onClick = { openOfficialRailway(context, onMessage) }) { Text("打开铁路12306") }
                }
            }
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(state.agreed, { state.agreed = it }, enabled = !state.busy)
                Text("我已阅读并同意", fontSize = 13.sp, color = OriginColors.TextSecondary)
                TextButton(onClick = { rules = true }, contentPadding = PaddingValues(2.dp)) { Text("候补购票相关说明", fontSize = 13.sp) }
            }
        }
        Column(Modifier.background(Color.White).padding(12.dp)) {
            WaitlistButton(if (state.busy) "正在办理…" else "提交候补订单",
                !state.busy && state.sessionId.isNotBlank() && selected.isNotEmpty() && state.agreed) { confirmation = true }
        }
    }
    if (passengerPicker) ModalBottomSheet(onDismissRequest = { passengerPicker = false }, containerColor = Color.White,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("选择乘车人（最多${state.passengerLimit}人）", Modifier.padding(horizontal = 20.dp, vertical = 10.dp), fontSize = 18.sp)
        Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
            if (state.passengers.isEmpty()) Text("当前账号暂无可用乘车人，请在铁路12306中添加并完成核验后重新获取。", Modifier.padding(20.dp), fontSize = 14.sp)
            state.passengers.forEach { passenger ->
                val checked = passenger.id in state.selectedPassengers
                Row(Modifier.fillMaxWidth().clickable {
                    if (checked) state.selectedPassengers -= passenger.id
                    else if (state.selectedPassengers.size < state.passengerLimit) state.selectedPassengers += passenger.id
                    else onMessage("最多选择${state.passengerLimit}位乘车人")
                }.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked, onCheckedChange = null)
                    Column(Modifier.weight(1f)) {
                        Text("${passenger.name}　${ticketTypeName(passenger.type)}", fontSize = 16.sp)
                        Text(passenger.maskedId, fontSize = 13.sp, color = OriginColors.TextMuted)
                    }
                }
            }
        }
        Box(Modifier.padding(16.dp)) { WaitlistButton("确定") { passengerPicker = false } }
    }
    if (deadlinePicker) WaitlistDeadlinePicker(state.deadlines, state.deadline,
        onDismiss = { deadlinePicker = false }, onConfirm = { state.deadline = it; deadlinePicker = false })
    if (confirmation) AlertDialog(onDismissRequest = { confirmation = false }, title = { Text("确认提交候补") }, text = {
        Text("${state.choices.map { it.trainDateKey }.distinct().size}个车次，${selected.size}位乘车人\n截止兑现：${deadlineLabel(state.deadline)}\n接受新增列车：${if (state.acceptNewTrains) "是\n" + newTrainSummary(state.newTrainRanges) + "\n" + NEW_TRAIN_SEATS.filter { it.first in state.newTrainSeats }.joinToString("、") { it.second } else "否"}\n\n提交成功后将生成待支付订单。本应用不会自动付款。")
    }, confirmButton = { TextButton(onClick = { confirmation = false; state.submit() }) { Text("确认提交", color = WaitlistOrange) } },
        dismissButton = { TextButton(onClick = { confirmation = false }) { Text("返回核对") } })
    if (rules) WaitlistRulesDialog { rules = false }
    if (newTrainPicker) NewTrainConditions(state, onDismiss = { newTrainPicker = false }, onMessage = onMessage)
}

@Composable
private fun WaitlistDeadlinePicker(options: List<String>, selected: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = options.indexOf(selected).coerceAtLeast(0))
    val centered by remember { derivedStateOf {
        val layout = list.layoutInfo
        val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
        layout.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index ?: options.indexOf(selected).coerceAtLeast(0)
    } }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White, dragHandle = null,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), shape = RoundedCornerShape(0.dp)) {
        Row(Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss) { Text("取消", color = OriginColors.TextMuted, fontSize = 16.sp) }
            Text("请选择截止兑现时间", Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 17.sp)
            TextButton(onClick = { options.getOrNull(centered)?.let(onConfirm) }, enabled = !list.isScrollInProgress) { Text("确认", fontSize = 16.sp) }
        }
        Box(Modifier.fillMaxWidth().height(240.dp)) {
            Column(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 14.dp).height(48.dp)) {
                HorizontalDivider(color = OriginColors.Divider)
                Spacer(Modifier.weight(1f))
                HorizontalDivider(color = OriginColors.Divider)
            }
            LazyColumn(state = list, flingBehavior = rememberSnapFlingBehavior(list), contentPadding = PaddingValues(vertical = 96.dp), modifier = Modifier.fillMaxSize()) {
                itemsIndexed(options) { index, value ->
                    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                        Text(deadlineLabel(value), fontSize = 18.sp, color = if (index == centered) OriginColors.TextPrimary else Color(0xFFBBBBBB))
                    }
                }
            }
        }
    }
}

private fun ticketTypeName(type: String) = when (type) { "2" -> "儿童票"; "3" -> "学生票"; "4" -> "优待票"; else -> "成人票" }

@Composable
fun WaitlistOrderScreen(state: TicketWaitlistState, onBack: () -> Unit, onLogin: () -> Unit, onMessage: (String) -> Unit) {
    if (state.orderState == "pending_payment") {
        PendingWaitlistOrder(state, onBack, onMessage)
        return
    }
    val context = LocalContext.current
    val title = when (state.orderState) {
        "pending_payment" -> "待支付"
        "processing" -> "正在处理"
        "rejected", "failed" -> "未成功受理"
        "none" -> "暂无待支付候补订单"
        else -> "订单状态待确认"
    }
    var recovery by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(OriginColors.Page)) {
        TicketTitleBar("候补订单", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().background(Color.White).padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (state.orderState == "pending_payment") Icons.Outlined.Schedule else Icons.Outlined.Info, null,
                    Modifier.size(42.dp), tint = WaitlistOrange)
                Text(title, Modifier.padding(top = 10.dp), fontSize = 22.sp, color = WaitlistOrange)
                Text(state.message, Modifier.padding(top = 12.dp), textAlign = TextAlign.Center, color = OriginColors.TextSecondary, fontSize = 14.sp, lineHeight = 22.sp)
                if (state.busy) CircularProgressIndicator(Modifier.padding(top = 18.dp).size(26.dp))
            }

        }
        Column(Modifier.background(Color.White).padding(12.dp)) {
            WaitlistButton(if (state.busy) "正在查询…" else "刷新订单状态", !state.busy, state::refreshStatus)
            if (state.code == "VERIFY_ORDER") TextButton(onClick = { recovery = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("核对后重新选择") }
            if (state.code == "LOGIN_REQUIRED") TextButton(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("登录12306账号") }
            TextButton(onClick = { openOfficialRailway(context, onMessage) }, modifier = Modifier.fillMaxWidth()) { Text("打开铁路12306查看") }
        }
    }
    if (recovery) AlertDialog(onDismissRequest = { recovery = false }, title = { Text("已核对官方订单？") }, text = {
        Text("请先在铁路12306的“待支付”和“候补订单”中核对。仅在确认本次提交已失败或过期、没有待支付或正在处理的订单后继续，以免重复下单。")
    }, confirmButton = { TextButton(onClick = { recovery = false; state.resumeAfterOfficialCheck() }) { Text("已核对，重新选择") } },
        dismissButton = { TextButton(onClick = { recovery = false }) { Text("返回核对") } })
}

@Composable
private fun WaitlistRulesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("候补购票说明") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("每份清单最多选择3个日期、60个车次，同一车次可选择多个席别。\n\n仅支持服务端允许候补的席别。提交前会刷新余票并核验登录、人证状态、乘车人及截止兑现时间。\n\n预付款及订单状态以12306实际返回为准。未支付订单不会进入已支付的候补兑现流程。", fontSize = 14.sp, lineHeight = 23.sp)
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://mobile.12306.cn/otsmobile/h5/otsbussiness/houbuRules/houbuRules.html"))) }
            }) { Text("阅读12306官方完整规则") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } })
}
