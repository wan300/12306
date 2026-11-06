@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.railhelper.ticket

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

val NEW_TRAIN_SEATS = listOf("O" to "二等座", "M" to "一等座", "9" to "商务座", "1" to "硬座", "4" to "软卧", "3" to "硬卧")

@Composable
fun QuietCarriageBadge() {
    Text("静", Modifier.background(Color(0xFFFFDF56), RoundedCornerShape(2.dp)).padding(horizontal = 2.dp)
        .semantics { contentDescription = "静音车厢" }, color = Color(0xFF8B731C), fontSize = 11.sp, lineHeight = 14.sp)
}

@Composable
fun QuietCarriageDialog(rememberChoice: Boolean, onRemember: (Boolean) -> Unit, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxWidth(.73f), shape = RoundedCornerShape(12.dp), color = Color.White) {
            Column(Modifier.background(Brush.verticalGradient(listOf(Color(0xFFE9F2FF), Color.White), endY = 300f))
                .padding(12.dp).verticalScroll(rememberScrollState())) {
                Text("温馨提示", Modifier.fillMaxWidth().padding(vertical = 10.dp), textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, color = Color(0xFF1A2440))
                Text("选择该车次二等座可能候补到“静音车厢”席位，是否继续选择？", color = OriginColors.Blue, fontSize = 13.sp, lineHeight = 21.sp)
                Text("在静音车厢内须保持安静，动作轻柔避免产生噪声；将手机等电子设备调至静音或振动状态；在接打电话或相互交谈时离开静音车厢；在使用电子设备时佩戴耳机或关闭音源外放功能；携带儿童出行的旅客照看好孩子，避免喧哗、哭闹。",
                    color = OriginColors.TextPrimary, fontSize = 13.sp, lineHeight = 21.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(rememberChoice, onRemember, modifier = Modifier.size(30.dp))
                    Text("记住我的选择，不再提示。", fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.clickable { onRemember(!rememberChoice) })
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    OutlinedButton(onDismiss, Modifier.weight(1f).heightIn(min = 42.dp), shape = RoundedCornerShape(6.dp), contentPadding = PaddingValues(4.dp), border = androidx.compose.foundation.BorderStroke(1.dp, OriginColors.Blue)) { Text("取消选择", fontSize = 14.sp) }
                    Button(onConfirm, Modifier.weight(1f).heightIn(min = 42.dp), shape = RoundedCornerShape(6.dp), contentPadding = PaddingValues(4.dp)) { Text("仍然选择", fontSize = 14.sp) }
                }
            }
        }
    }
}

fun newTrainSummary(ranges: List<NewTrainRange>): String = ranges.filter { it.enabled }.joinToString("\n") {
    "${it.date.substring(5).replace("-", "月")}日　${it.start}点—${it.end}点"
}

@Composable
fun NewTrainConditions(state: TicketWaitlistState, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    var ranges by remember { mutableStateOf(state.newTrainRanges) }
    var seats by remember { mutableStateOf(state.newTrainSeats) }
    val first = state.choices.firstOrNull()?.train
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White, dragHandle = null, shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(if (ranges.size == 1) .64f else .82f)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp).heightIn(min = 55.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("新增列车兑现条件设置", Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                TextButton(onClick = onDismiss) { Text("取消", color = OriginColors.TextMuted) }
            }
            HorizontalDivider(color = OriginColors.Divider)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("发到站　${first?.fromStation.orEmpty()} — ${first?.toStation.orEmpty()}",
                    Modifier.fillMaxWidth().border(.5.dp, Color(0xFFD0E6FF), RoundedCornerShape(5.dp))
                        .background(Brush.horizontalGradient(listOf(Color(0xFFF0F7FF), Color.White))).padding(10.dp), fontSize = 14.sp, lineHeight = 18.sp, color = Color(0xFF194A7B))
                Column(Modifier.fillMaxWidth().border(.5.dp, Color(0xFFD0E6FF), RoundedCornerShape(5.dp))) {
                NewTrainSectionTitle("选择席别")
                FlowRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 5) {
                    NEW_TRAIN_SEATS.forEach { (code, label) ->
                        Box(Modifier.border(.5.dp, if (code in seats) OriginColors.Blue else OriginColors.Divider, RoundedCornerShape(3.dp))
                            .background(if (code in seats) Color(0xFFF0F7FF) else Color.White)
                            .clickable { seats = if (code in seats) seats - code else seats + code }
                            .padding(horizontal = 9.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
                            Text(label, color = if (code in seats) OriginColors.Blue else OriginColors.TextMuted, fontSize = 13.sp, lineHeight = 17.sp)
                        }
                    }
                }
                }
                Column(Modifier.fillMaxWidth().border(.5.dp, Color(0xFFD0E6FF), RoundedCornerShape(5.dp))) {
                NewTrainSectionTitle("期望出行时段")
                ranges.forEachIndexed { index, range ->
                    Column(Modifier.fillMaxWidth().padding(8.dp).border(.5.dp, Color(0xFFD0E6FF), RoundedCornerShape(5.dp)).padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(range.date.substring(5).replace("-", "月") + "日", fontSize = 15.sp, lineHeight = 20.sp)
                            Text("　${range.start}点—${range.end}点", Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp, color = OriginColors.Blue)
                            WaitlistSwitch(range.enabled, { enabled -> ranges = ranges.mapIndexed { i, r -> if (i == index) r.copy(enabled = enabled) else r } },
                                modifier = Modifier.semantics { contentDescription = "${range.date}出发时段" })
                        }
                        if (range.enabled) {
                            if (range.start < 7) Text("ⓘ 包含凌晨出发的新增列车", color = WaitlistOrange, fontSize = 12.sp, lineHeight = 17.sp)
                            RangeSlider(value = range.start.toFloat()..range.end.toFloat(), valueRange = 0f..24f, steps = 23,
                                startThumb = { NewTrainTimeThumb() }, endThumb = { NewTrainTimeThumb() },
                                track = {
                                    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                                        val y = size.height / 2
                                        drawLine(Color(0xFFEEEEEE), Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
                                        drawLine(Color(0xFF54C5E5), Offset(size.width * range.start / 24f, y), Offset(size.width * range.end / 24f, y), size.height, StrokeCap.Round)
                                    }
                                },
                                onValueChange = { value ->
                                    val start = value.start.roundToInt(); val end = value.endInclusive.roundToInt()
                                    if (start < end) ranges = ranges.mapIndexed { i, r -> if (i == index) r.copy(start = start, end = end) else r }
                                }, modifier = Modifier.semantics { contentDescription = "${range.date}发车时间范围" })
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("0点", fontSize = 12.sp, lineHeight = 14.sp); Text("24点", fontSize = 12.sp, lineHeight = 14.sp) }
                        }
                    }
                }
                }
            }
            Button(onClick = {
                if (seats.isEmpty() || ranges.none { it.enabled }) onMessage("请至少选择一个席别和一个出发时段")
                else { state.newTrainSeats = seats; state.newTrainRanges = ranges; state.acceptNewTrains = true; onDismiss() }
            }, modifier = Modifier.fillMaxWidth().padding(10.dp).height(46.dp), shape = RoundedCornerShape(6.dp)) { Text("确认", fontSize = 17.sp) }
        }
    }
}

@Composable
private fun NewTrainTimeThumb() {
    Canvas(Modifier.size(18.dp).shadow(4.dp, CircleShape).background(Color.White, CircleShape).border(.5.dp, OriginColors.Divider, CircleShape)) {
        for (x in listOf(.35f, .5f, .65f)) {
            drawLine(Color(0xFFDDDDDD), Offset(size.width * x, size.height * .3f),
                Offset(size.width * x, size.height * .7f), 1.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
private fun NewTrainSectionTitle(title: String) {
    Text(title, Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFFF0F7FF), Color.White))).padding(10.dp),
        color = Color(0xFF194A7B), fontSize = 14.sp, lineHeight = 18.sp)
}

@Composable
fun WaitlistSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(modifier.width(40.dp).height(30.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange), contentAlignment = Alignment.Center) {
        Box(Modifier.width(38.dp).height(22.dp).background(if (checked) OriginColors.Blue else Color(0xFFD9D9D9), RoundedCornerShape(11.dp)).padding(2.dp)) {
            Box(Modifier.size(18.dp).align(if (checked) Alignment.CenterEnd else Alignment.CenterStart).background(Color.White, RoundedCornerShape(9.dp)))
        }
    }
}
