@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.railhelper.ticket

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

internal val taskSeats = listOf("O" to "二等座", "M" to "一等座", "9" to "商务座", "1" to "硬座", "3" to "硬卧", "4" to "软卧")
internal val taskTrainTypes = listOf("G" to "高铁", "D" to "动车", "C" to "城际", "Z" to "直达", "T" to "特快", "K" to "快速")
internal fun taskCodes(value: String) = value.split(',').map { it.trim().uppercase(Locale.ROOT) }.filter { it.isNotEmpty() }.distinct()
internal fun taskSeatLabel(codes: List<String>) = codes.joinToString("、") { code -> taskSeats.firstOrNull { it.first == code }?.second ?: code }.ifBlank { "请选择席别" }
internal fun taskDateLabel(date: String) = runCatching { "${TicketDates.monthDay(date)}  ${TicketDates.weekday(date)}" }.getOrDefault(date)

internal fun taskTimeRangeValid(value: String): Boolean {
    if (value.isBlank()) return true
    val match = Regex("^(\\d{2}):(\\d{2})-(\\d{2}):(\\d{2})$").matchEntire(value) ?: return false
    val (h1, m1, h2, m2) = match.destructured
    val a = h1.toInt() * 60 + m1.toInt(); val b = h2.toInt() * 60 + m2.toInt()
    return h1.toInt() in 0..23 && m1.toInt() in 0..59 && h2.toInt() in 0..23 && m2.toInt() in 0..59 && a < b
}

internal fun taskDraftProblem(draft: TaskDraft): String? = when {
    draft.fromStation.isBlank() || draft.toStation.isBlank() -> "请选择出发站和到达站"
    draft.fromStation == draft.toStation -> "出发站和到达站不能相同"
    !runCatching { TicketDates.bookable(draft.trainDate) }.getOrDefault(false) -> "请选择预售期内的出发日期"
    draft.seatTypes.isEmpty() -> "请至少选择一个席别"
    draft.passengers.isEmpty() -> "请至少添加一位乘车人"
    !taskTimeRangeValid(draft.startTimeRange) -> "出发时段格式应为 08:00-12:00，结束时间须晚于开始时间"
    draft.queryInterval.toIntOrNull() !in 3..60 -> "查询间隔须为 3—60 秒"
    !draft.infiniteRetry && (draft.maxRetryCount.toIntOrNull() ?: 0) <= 0 -> "重试次数须大于 0"
    else -> null
}

@Composable
internal fun TaskPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(9.dp), color = Color.White, contentColor = OriginColors.TextPrimary) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), content = content)
    }
}

@Composable
internal fun TaskSection(title: String, hint: String? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
        if (hint != null) Text(hint, fontSize = 11.sp, color = OriginColors.TextMuted)
    }
}

@Composable
internal fun TaskValueRow(label: String, value: String, onClick: (() -> Unit)? = null, divider: Boolean = true) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .heightIn(min = 49.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, lineHeight = 20.sp, color = OriginColors.TextSecondary)
        Spacer(Modifier.width(14.dp))
        Text(value, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.End,
            color = if (onClick != null) OriginColors.Blue else OriginColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (onClick != null) Text(" ›", fontSize = 22.sp, color = OriginColors.TextMuted)
    }
    if (divider) HorizontalDivider(color = OriginColors.Divider)
}

@Composable
internal fun TaskOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.semantics { this.selected = selected }, shape = RoundedCornerShape(4.dp), color = if (selected) Color(0xFFEFF7FF) else Color.White,
        border = androidx.compose.foundation.BorderStroke(.7.dp, if (selected) OriginColors.Blue else OriginColors.Divider)) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 9.dp), fontSize = 13.sp, lineHeight = 18.sp,
            color = if (selected) OriginColors.Blue else OriginColors.TextSecondary)
    }
}

@Composable
internal fun TaskRoute(from: String, to: String, date: String? = null, onFrom: (() -> Unit)? = null, onTo: (() -> Unit)? = null, onSwap: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).then(if (onFrom != null) Modifier.clickable(onClick = onFrom) else Modifier)) {
            if (onFrom != null) Text("出发站", fontSize = 12.sp, color = OriginColors.TextMuted)
            Text(from.ifBlank { "请选择" }, fontSize = 22.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onSwap != null) IconButton(onClick = onSwap) {
            Box(Modifier.size(28.dp)) {
                Image(painterResource(R.drawable.origin_hp_change_station_circle_normal), "交换出发站和到达站", Modifier.fillMaxSize())
                Image(painterResource(R.drawable.origin_hp_change_station_logo_normal), null, Modifier.fillMaxSize())
            }
        } else Image(painterResource(R.drawable.origin_ticket_route_line), null, Modifier.width(45.dp).height(10.dp))
        Column(Modifier.weight(1f).then(if (onTo != null) Modifier.clickable(onClick = onTo) else Modifier), horizontalAlignment = Alignment.End) {
            if (onTo != null) Text("到达站", fontSize = 12.sp, color = OriginColors.TextMuted)
            Text(to.ifBlank { "请选择" }, fontSize = 22.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (date != null) Text(taskDateLabel(date), Modifier.padding(bottom = 8.dp), fontSize = 13.sp, color = OriginColors.TextSecondary)
}

@Composable
internal fun TaskPrimary(label: String, enabled: Boolean = true, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick, modifier.fillMaxWidth().height(46.dp), enabled = enabled, shape = RoundedCornerShape(5.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8200))) { Text(label, fontSize = 17.sp) }
}
