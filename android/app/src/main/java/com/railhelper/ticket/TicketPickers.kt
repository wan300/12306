package com.railhelper.ticket

import android.icu.util.ChineseCalendar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

@Composable
fun TicketStationScreen(onBack: () -> Unit, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val memory = remember { TicketQueryMemory(context) }
    var stations by remember { mutableStateOf<List<TicketStation>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var keyword by rememberSaveable { mutableStateOf("") }
    val recent = remember { memory.recentStations() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(reload) {
        loading = true
        failed = false
        try {
            stations = withContext(Dispatchers.IO) { context.assets.open("station_name.js").bufferedReader().use { parseTicketStations(it.readText()) } }
            failed = stations.isEmpty()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { failed = true
        } finally { loading = false }
    }
    fun choose(name: String) { memory.addStation(name); onSelect(name) }
    val groups = remember(stations) { stations.groupBy { it.letter }.toSortedMap() }
    val positions = remember(groups) {
        var index = 3
        groups.mapValues { (_, rows) -> index.also { index += rows.size + 1 } }
    }
    val matches = remember(stations, keyword) { findTicketStations(stations, keyword) }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        TicketTitleBar("车站选择", onBack)
        Row(Modifier.fillMaxWidth().padding(10.dp).height(36.dp)
            .background(Color(0xFFF5F5F5), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null, tint = OriginColors.TextSecondary, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(6.dp))
            BasicTextField(keyword, onValueChange = { keyword = it }, singleLine = true,
                textStyle = TextStyle(color = OriginColors.TextPrimary, fontSize = 14.sp), modifier = Modifier.weight(1f),
                decorationBox = { field ->
                    Box { if (keyword.isEmpty()) Text("请输入（如：北京/beijing/bj）", color = OriginColors.TextMuted, fontSize = 13.sp, maxLines = 1); field() }
                })
            if (keyword.isNotEmpty()) IconButton(onClick = { keyword = "" }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, "清除搜索", Modifier.size(17.dp), tint = OriginColors.TextMuted)
            }
        }
        when {
            loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            failed -> TicketEmptyState("车站数据加载失败", "请重试", onRetry = { reload++ })
            keyword.isNotBlank() -> LazyColumn(Modifier.weight(1f)) {
                if (matches.isEmpty()) item { TicketEmptyState("未找到车站", "试试车站名称、拼音或首字母") }
                items(matches, key = { it.code }) { station -> StationRow(station.name) { choose(station.name) } }
            }
            else -> Box(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize().padding(end = 25.dp), state = listState) {
                    item(key = "recent") { if (recent.isNotEmpty()) StationTiles("最近选择", recent, ::choose) }
                    item(key = "hot") { StationTiles("热门车站", ticketHotStations.filter { name -> stations.any { it.name == name } }, ::choose) }
                    item(key = "domestic") {
                        Column(Modifier.fillMaxWidth().padding(start = 12.dp, top = 5.dp)) {
                            Text("国内站点", fontSize = 16.sp, lineHeight = 22.sp, color = OriginColors.Blue)
                            Box(Modifier.padding(start = 18.dp, top = 8.dp).width(28.dp).height(2.dp).background(OriginColors.Blue))
                        }
                    }
                    groups.forEach { (letter, rows) ->
                        item(key = "letter-$letter") { Text(letter, color = OriginColors.TextSecondary, fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth().background(Color(0xFFF7F7F7)).padding(horizontal = 14.dp, vertical = 8.dp)) }
                        items(rows, key = { it.code }) { station -> StationRow(station.name) { choose(station.name) } }
                    }
                }
                Column(Modifier.align(Alignment.CenterEnd).width(25.dp).fillMaxHeight().padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    val indexEntries = buildList {
                        if (recent.isNotEmpty()) add("近" to 0)
                        add("热" to 1)
                        groups.keys.forEach { letter -> add(letter to positions.getValue(letter)) }
                    }
                    indexEntries.forEach { (label, position) ->
                        Box(Modifier.weight(1f).fillMaxWidth().clickable { scope.launch { listState.scrollToItem(position) } },
                            contentAlignment = Alignment.Center) {
                            Text(label, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, color = OriginColors.Blue)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StationTiles(title: String, stations: List<String>, onSelect: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(title, color = OriginColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 14.dp))
        stations.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 9.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { name -> Box(Modifier.weight(1f).height(36.dp)
                    .background(Color(0xFFFAFAFA), RoundedCornerShape(3.dp))
                    .border(.5.dp, OriginColors.Divider, RoundedCornerShape(3.dp)).clickable { onSelect(name) }, contentAlignment = Alignment.Center) {
                    Text(name, fontSize = 16.sp, color = OriginColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StationRow(name: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp)) {
        Text(name, fontSize = 16.sp, color = OriginColors.TextPrimary, modifier = Modifier.padding(vertical = 13.dp))
        HorizontalDivider(thickness = .5.dp, color = OriginColors.Divider)
    }
}

@Composable
fun TicketCalendarScreen(selected: String, onBack: () -> Unit, onSelect: (String) -> Unit) {
    val today = TicketDates.today()
    val firstMonth = TicketDates.calendar(today).apply { set(Calendar.DAY_OF_MONTH, 1) }
    val months = (0..2).map { offset -> (firstMonth.clone() as Calendar).apply { add(Calendar.MONTH, offset) } }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        TicketTitleBar("选择日期", onBack)
        Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("日", "一", "二", "三", "四", "五", "六").forEachIndexed { index, text ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(text, fontSize = 14.sp, color = if (index == 0 || index == 6) Color.Red else OriginColors.TextPrimary)
                }
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            months.forEach { month ->
                val year = month.get(Calendar.YEAR)
                val monthNumber = month.get(Calendar.MONTH) + 1
                item(key = "$year-$monthNumber") {
                    Box(Modifier.fillMaxWidth().height(28.dp).background(Color(0xFFFAFAFA)), contentAlignment = Alignment.Center) {
                        Text("${year}年${monthNumber}月", fontSize = 16.sp, lineHeight = 22.sp, color = OriginColors.TextPrimary)
                    }
                    Spacer(Modifier.height(8.dp))
                    val leading = month.get(Calendar.DAY_OF_WEEK) - 1
                    val count = month.getActualMaximum(Calendar.DAY_OF_MONTH)
                    val cells = List(leading) { 0 } + (1..count).toList()
                    cells.chunked(7).forEach { week ->
                        Row(Modifier.fillMaxWidth()) {
                            week.forEach { day ->
                                val date = "%04d-%02d-%02d".format(year, monthNumber, day.coerceAtLeast(1))
                                val enabled = day > 0 && TicketDates.bookable(date, today)
                                val isSelected = day > 0 && date == selected
                                val weekend = TicketDates.calendar(date).get(Calendar.DAY_OF_WEEK) in listOf(Calendar.SUNDAY, Calendar.SATURDAY)
                                Box(Modifier.weight(1f).height(68.dp).padding(vertical = 7.dp)
                                    .background(if (isSelected) OriginColors.Blue else Color.Transparent, RoundedCornerShape(5.dp))
                                    .clickable(enabled = enabled) { onSelect(date) }, contentAlignment = Alignment.Center) {
                                    if (day > 0) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(day.toString(), fontSize = 16.sp, lineHeight = 20.sp,
                                            color = when { isSelected -> Color.White; date < today -> Color(0xFFE5E5E5); !enabled -> Color(0xFF888888); weekend -> Color.Red; else -> OriginColors.TextPrimary })
                                        Text(if (date == today) "今天" else lunarDay(date), fontSize = 11.sp, lineHeight = 16.sp,
                                            color = when { isSelected -> Color.White; date < today -> Color(0xFFE5E5E5); !enabled -> Color(0xFF888888); else -> OriginColors.TextSecondary })
                                    }
                                }
                            }
                            repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

private fun lunarDay(date: String): String {
    val lunar = ChineseCalendar().apply { timeInMillis = TicketDates.millis(date) + 12 * 3_600_000L }
    val day = lunar.get(ChineseCalendar.DAY_OF_MONTH)
    return when (day) {
        10 -> "初十"; 20 -> "二十"; 30 -> "三十"
        else -> listOf("初", "十", "廿")[((day - 1) / 10).coerceIn(0, 2)] + "一二三四五六七八九"[(day - 1) % 10]
    }
}
