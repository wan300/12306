@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.railhelper.ticket

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
fun TicketResultsScreen(
    initial: TicketQueryResult,
    user: User?,
    waitlist: TicketWaitlistState,
    onLogin: () -> Unit,
    onBack: () -> Unit,
    onCreateTask: (TaskDraft) -> Unit,
    onMessage: (String) -> Unit,
    onResultChanged: (TicketQueryResult) -> Unit = {},
) {
    val context = LocalContext.current
    val largeText = LocalDensity.current.fontScale > 1.15f
    val memory = remember { TicketQueryMemory(context) }
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf(initial) }
    LaunchedEffect(initial) { result = initial }
    var loading by remember { mutableStateOf(false) }
    var calendar by rememberSaveable { mutableStateOf(false) }
    var filterSheet by rememberSaveable { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf("all") }
    var availableOnly by rememberSaveable { mutableStateOf(false) }
    var seatCodes by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var fromStations by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var toStations by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var sort by rememberSaveable { mutableStateOf("depart") }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var showPrices by rememberSaveable { mutableStateOf(false) }
    var cart by rememberSaveable { mutableStateOf(false) }
    var quietChoice by remember { mutableStateOf<WaitlistChoice?>(null) }
    var rememberQuiet by rememberSaveable { mutableStateOf(false) }
    val quietPrefs = remember { context.getSharedPreferences("ticket_quiet_notice", android.content.Context.MODE_PRIVATE) }
    var refreshingPrice by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val filters = TicketFilters(category, availableOnly, seatCodes.toSet(), fromStations.toSet(), toStations.toSet())
    val visible = remember(result, filters, sort) { filterTicketTrains(result.trains, filters, sort) }
    val seatOptions = remember(result) { result.trains.flatMap(::trainSeats).distinctBy { it.code }.map { it.code to it.label } }
    BackHandler { onBack() }
    LaunchedEffect(user?.id) { waitlist.attach(context, user) }
    LaunchedEffect(result) { waitlist.reconcile(result) }

    fun refreshPrice(train: TrainInfo) {
        if (refreshingPrice != null) return
        val key = trainSegmentKey(train)
        val date = result.date
        refreshingPrice = key
        scope.launch {
            try {
                val response = PythonBridge.call("query_train_prices", JSONObject()
                    .put("train_no", train.trainNo).put("from_station_no", train.fromStationNo)
                    .put("to_station_no", train.toStationNo).put("seat_types", train.seatTypes)
                    .put("train_date", date).toString())
                if (!response.success()) { onMessage(response.message()); return@launch }
                if (result.date != date) return@launch
                val prices = parsePrices(response.dataObject()?.optJSONObject("prices_fen"))
                result = result.copy(trains = result.trains.map {
                    if (trainSegmentKey(it) == key) it.copy(pricesFen = prices, priceSource = "leftTicket/queryTicketPrice") else it
                })
                onResultChanged(result)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { onMessage("票价查询失败，请稍后重试")
            } finally { refreshingPrice = null }
        }
    }

    fun changeDate(date: String) {
        if (loading || !TicketDates.bookable(date)) return
        scope.launch {
            loading = true
            try {
                val response = queryTicketBridge(result.from, result.to, date, result.studentTicket, result.trainType)
                if (!response.success()) { onMessage(ticketQueryError(response.message())); return@launch }
                val next = result.copy(date = date, trains = parseTrains(response.dataObject()?.optJSONArray("trains")))
                result = next
                onResultChanged(next)
                memory.saveDate(date)
                selected = emptyList()
                expanded = null
                listState.scrollToItem(0)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { onMessage("暂时无法查询车票，请稍后重试")
            } finally { loading = false }
        }
    }
    fun submitSelection() {
        if (selected.isEmpty()) { onMessage("请展开车次并加入任务"); return }
        onCreateTask(TaskDraft(name = "${result.date} ${result.from}-${result.to} 抢票", fromStation = result.from,
            toStation = result.to, trainDate = result.date, trainCodes = selected.joinToString(","),
            trainTypes = result.trainType.takeIf { it.isNotBlank() }?.split(',')?.toSet() ?: emptySet()))
    }
    Column(Modifier.fillMaxSize().background(OriginColors.Page)) {
        TicketTitleBar("${result.from} <> ${result.to}", onBack) {
            IconButton(onClick = { waitlist.refreshStatus() }) { Icon(Icons.Outlined.ReceiptLong, "候补订单", tint = Color.White) }
        }
        Row(Modifier.fillMaxWidth().background(OriginColors.Blue).padding(start = 10.dp, end = 5.dp, top = 3.dp, bottom = 10.dp).height(if (largeText) 54.dp else 41.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TicketDates.strip(result.date).forEach { date ->
                val active = date == result.date
                Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = if (largeText) 0.dp else 5.dp)
                    .background(if (active) Color.White else Color.Transparent, RoundedCornerShape(6.dp))
                    .clickable(enabled = !loading) { if (!active) changeDate(date) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    val color = if (active) OriginColors.Blue else Color.White
                    Text(if (date == TicketDates.today()) "今天" else TicketDates.weekday(date), color = color, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1)
                    Text(date.substring(5).replace('-', '.'), color = color, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
            Column(Modifier.width(40.dp).fillMaxHeight().clickable(enabled = !loading) { calendar = true },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Outlined.CalendarMonth, null, tint = Color.White, modifier = Modifier.size(21.dp))
                Text("日历", color = Color.White, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item(key = "filters") {
                Column {
                    Surface(color = Color.White, shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)) {
                        Column(Modifier.fillMaxWidth().height(43.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Image(painterResource(R.drawable.origin_ticket_direct), null, Modifier.width(14.dp).height(16.dp))
                                Spacer(Modifier.width(5.dp)); Text("直达", color = OriginColors.Blue, fontSize = 17.sp, lineHeight = 22.sp)
                            }
                            Spacer(Modifier.height(4.dp)); Box(Modifier.width(28.dp).height(2.dp).background(OriginColors.Blue, RoundedCornerShape(2.dp)))
                        }
                    }
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 10.dp), maxItemsInEachRow = if (largeText) 2 else 4, horizontalArrangement = Arrangement.SpaceBetween) {
                        TicketCheck("只看高铁/动车", category == "high", { category = if (it) "high" else "all" }, small = true)
                        TicketCheck("只看普速", category == "normal", { category = if (it) "normal" else "all" }, small = true)
                        TicketCheck("只看有票", availableOnly, { availableOnly = it }, small = true)
                        Row(Modifier.height(34.dp).clickable { filterSheet = true }, verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(R.drawable.origin_ticket_filter), null, Modifier.size(13.dp), colorFilter = ColorFilter.tint(if (filters.active) OriginColors.Blue else OriginColors.TextSecondary))
                            Text("筛选", fontSize = 12.sp, lineHeight = 18.sp, color = if (filters.active) OriginColors.Blue else OriginColors.TextSecondary)
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        result.trains.map { it.fromStation }.distinct().forEach { station ->
                            StationFilterTag(station, station in fromStations) { fromStations = if (station in fromStations) fromStations - station else fromStations + station }
                        }
                        result.trains.map { it.toStation }.distinct().forEach { station ->
                            StationFilterTag(station, station in toStations) { toStations = if (station in toStations) toStations - station else toStations + station }
                        }
                    }
                }
            }
            if (loading) item {
                Box(Modifier.fillMaxWidth().padding(44.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            } else if (visible.isEmpty()) item {
                TicketEmptyState("暂无符合条件的车次", if (filters.active) "可调整筛选条件或选择其他日期" else "可选择其他日期再试")
                if (filters.active) TextButton(onClick = { category = "all"; availableOnly = false; seatCodes = emptyList(); fromStations = emptyList(); toStations = emptyList() }, modifier = Modifier.fillMaxWidth()) { Text("清除筛选") }
            } else items(visible, key = { "${it.trainCode}-${it.fromStation}-${it.toStation}" }) { train ->
                TicketTrainCard(train, result.date, result.studentTicket, showPrices, waitlist.choices,
                    train.trainCode in selected, trainSegmentKey(train) == expanded,
                    refreshingPrice == trainSegmentKey(train), onRefreshPrice = { refreshPrice(train) },
                    onWaitlist = { seat ->
                        val choice = WaitlistChoice(result.date, train, seat)
                        if (train.quietCarriage && seat.code == "O" && waitlist.choices.none { it.key == choice.key } && !quietPrefs.getBoolean("acknowledged", false)) {
                            quietChoice = choice; rememberQuiet = false
                        } else waitlist.toggle(choice)?.let(onMessage)
                    },
                    onExpand = { expanded = if (expanded == trainSegmentKey(train)) null else trainSegmentKey(train) },
                    onSelect = { selected = if (train.trainCode in selected) selected - train.trainCode else selected + train.trainCode })
            }
        }
        Row(Modifier.fillMaxWidth().background(Color(0xFFF0F2F5)).padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
            Text(if (loading) "正在查询…" else "共${visible.size}趟列车", fontSize = 12.sp, lineHeight = 16.sp, color = OriginColors.TextSecondary)
            if (selected.isNotEmpty()) Text("　创建任务(${selected.size})", Modifier.clickable(onClick = ::submitSelection), fontSize = 12.sp, lineHeight = 16.sp, color = OriginColors.Blue)
        }
        Row(Modifier.fillMaxWidth().background(Color.White).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            ResultAction(Icons.Outlined.FilterAlt, "筛选", filters.active, Modifier.weight(1f), R.drawable.origin_ticket_filter) { filterSheet = true }
            ResultAction(Icons.Outlined.Timer, "耗时最短", sort == "duration", Modifier.weight(1f)) { sort = "duration"; scope.launch { listState.scrollToItem(0) } }
            ResultAction(Icons.Outlined.Schedule, "发时最早", sort == "depart", Modifier.weight(1f)) { sort = "depart"; scope.launch { listState.scrollToItem(0) } }
            ResultAction(Icons.Outlined.CurrencyYen, if (showPrices) "显示余票" else "显示票价", showPrices, Modifier.weight(1f)) { showPrices = !showPrices }
            ResultAction(Icons.Outlined.ShoppingCart, "候补下单", false, Modifier.weight(1.15f), badge = waitlist.choices.size) { cart = true }
        }
    }
    if (calendar) TicketFullscreen(onBack = { calendar = false }) {
        TicketCalendarScreen(result.date, onBack = { calendar = false }, onSelect = { calendar = false; if (it != result.date) changeDate(it) })
    }
    if (cart) WaitlistCart(waitlist, onDismiss = { cart = false }, onNext = { cart = false; waitlist.prepare() })
    quietChoice?.let { choice ->
        QuietCarriageDialog(rememberQuiet, { rememberQuiet = it }, onDismiss = { quietChoice = null }) {
            val error = waitlist.toggle(choice)
            if (error != null) onMessage(error)
            else if (rememberQuiet) quietPrefs.edit().putBoolean("acknowledged", true).apply()
            quietChoice = null
        }
    }
    if (waitlist.page.isNotBlank()) TicketFullscreen(onBack = { waitlist.page = "" }) {
        if (waitlist.page == "confirm") WaitlistCheckoutScreen(waitlist, onBack = { waitlist.page = "" },
            onLogin = { waitlist.page = ""; onLogin() }, onMessage = onMessage)
        else WaitlistOrderScreen(waitlist, onBack = { waitlist.page = "" }, onLogin = { waitlist.page = ""; onLogin() }, onMessage = onMessage)
    }
    if (filterSheet) TicketFilterSheet(filters, seatOptions,
        result.trains.map { it.fromStation }.distinct(), result.trains.map { it.toStation }.distinct(),
        onDismiss = { filterSheet = false }, onApply = { next ->
            category = next.category; availableOnly = next.availableOnly; seatCodes = next.seatCodes.toList()
            fromStations = next.fromStations.toList(); toStations = next.toStations.toList(); filterSheet = false
            scope.launch { listState.scrollToItem(0) }
        })
}

@Composable
private fun StationFilterTag(name: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.background(if (selected) Color(0xFFE7F3FF) else Color.White, RoundedCornerShape(4.dp))
        .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
        Text(name, color = if (selected) OriginColors.Blue else OriginColors.TextSecondary, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
    }
}

@Composable
private fun TicketTrainCard(train: TrainInfo, date: String, student: Boolean, showPrices: Boolean,
    choices: List<WaitlistChoice>, selected: Boolean, expanded: Boolean, refreshing: Boolean,
    onRefreshPrice: () -> Unit, onWaitlist: (TicketSeat) -> Unit, onExpand: () -> Unit, onSelect: () -> Unit) {
    val seats = trainSeats(train)
    val longSeatNames = seats.any { it.label.length > 2 }
    val seatColumns = if (LocalDensity.current.fontScale > 1.15f || (showPrices && longSeatNames)) 2 else if (showPrices || longSeatNames) 3 else 4
    val price = minimumTrainPrice(train)
    val shape = RoundedCornerShape(7.dp)
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        .then(if (selected) Modifier.border(1.dp, OriginColors.Blue, shape) else Modifier), color = Color.White, shape = shape) {
        Column {
            Column(Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(horizontal = 11.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(train.startTime, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = OriginColors.TextPrimary, maxLines = 1, softWrap = false)
                        Text(train.fromStation, fontSize = 14.sp, lineHeight = 20.sp, color = OriginColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(Modifier.weight(.95f).padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(train.trainCode, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = OriginColors.TextPrimary, maxLines = 1, softWrap = false)
                            if (train.quietCarriage) QuietCarriageBadge()
                        }
                        Image(painterResource(R.drawable.origin_ticket_route_line), null, Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 2.dp).height(3.dp))
                        Text(ticketDuration(train.duration), fontSize = 10.sp, lineHeight = 14.sp, color = OriginColors.TextMuted, maxLines = 1, softWrap = false)
                    }
                    Column(Modifier.weight(1.2f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(train.arriveTime, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = OriginColors.TextPrimary, maxLines = 1, softWrap = false)
                        Text(train.toStation, fontSize = 14.sp, lineHeight = 20.sp, color = OriginColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(Modifier.weight(.8f), horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(price?.let { "¥${ticketPrice(it)}" } ?: "票价待查", fontSize = if (price != null) 17.sp else 11.sp,
                                lineHeight = 24.sp, color = if (price != null) WaitlistOrange else OriginColors.TextMuted, maxLines = 1)
                            if (price != null) Text("起", fontSize = 10.sp, lineHeight = 20.sp, color = WaitlistOrange)
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 8.dp), thickness = .5.dp, color = OriginColors.Divider)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val seatWidth = maxWidth / seatColumns
                    FlowRow(maxItemsInEachRow = seatColumns, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        seats.forEach { seat ->
                            val status = waitlistStatus(train, seat, date, student)
                            val added = choices.any { it.key == WaitlistChoice(date, train, seat).key }
                            TicketSeatLabel(seat, train.pricesFen[seat.code], showPrices, status, added,
                                Modifier.width(seatWidth), onWaitlist = { onWaitlist(seat) })
                        }
                    }
                }
                if (seats.isEmpty()) Text("暂无席别信息", fontSize = 12.sp, color = OriginColors.TextMuted)
            }
            if (expanded) Column(Modifier.fillMaxWidth().background(Color(0xFFF6F9FC)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                seats.forEach { seat ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 42.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(seat.label, fontSize = 14.sp, color = OriginColors.TextPrimary, modifier = Modifier.weight(1f))
                        Text(train.pricesFen[seat.code]?.let { "¥${ticketPrice(it)}" } ?: "未获取", Modifier.padding(end = 20.dp), fontSize = 15.sp, color = WaitlistOrange)
                        Text(seatText(seat.count), fontSize = 14.sp, color = if (hasTicketCount(seat.count)) OriginColors.Success else OriginColors.TextMuted)
                    }
                    HorizontalDivider(thickness = .5.dp, color = OriginColors.Divider)
                }
                TextButton(onClick = onRefreshPrice, enabled = !refreshing, modifier = Modifier.fillMaxWidth()) {
                    Text(if (refreshing) "正在查询票价…" else "刷新实时票价")
                }
                TextButton(onClick = onSelect, modifier = Modifier.fillMaxWidth()) {
                    Icon(if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.AddCircleOutline, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text(if (selected) "已加入任务 · 点击移除" else "将此车次加入任务")
                }
            }
        }
    }
}

private fun seatText(value: String): String = if (value.toIntOrNull()?.let { it > 0 } == true) "${value}张" else if (value == "0") "无" else value

@Composable
private fun TicketSeatLabel(seat: TicketSeat, priceFen: Int?, showPrices: Boolean, status: WaitlistStatus,
    added: Boolean, modifier: Modifier = Modifier, onWaitlist: () -> Unit) {
        Row(modifier.heightIn(min = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(seat.label, fontSize = 12.sp, lineHeight = 18.sp, color = OriginColors.TextSecondary)
            Spacer(Modifier.width(3.dp))
            if (showPrices) Text(priceFen?.let { "¥${ticketPrice(it)}" } ?: "待查", fontSize = 12.sp, lineHeight = 18.sp, color = if (hasTicketCount(seat.count)) OriginColors.Success else OriginColors.TextMuted)
            else if (status == WaitlistStatus.UNAVAILABLE) Text(seatText(seat.count), fontSize = 12.sp, lineHeight = 18.sp, color = if (hasTicketCount(seat.count)) OriginColors.Success else OriginColors.TextMuted)
            if (status != WaitlistStatus.UNAVAILABLE) WaitlistSeatAction(status, added, onWaitlist)
        }
}

@Composable
private fun WaitlistSeatAction(status: WaitlistStatus, added: Boolean, onWaitlist: () -> Unit) {
    val color = if (added) WaitlistOrange else if (status == WaitlistStatus.OPEN) OriginColors.Blue else OriginColors.TextMuted
    Row(Modifier.heightIn(min = 24.dp).clickable(enabled = status == WaitlistStatus.OPEN || added, onClick = onWaitlist), verticalAlignment = Alignment.CenterVertically) {
        Text(if (status == WaitlistStatus.FULL) "候补已满" else "候补", fontSize = 12.sp, color = color)
        if (status == WaitlistStatus.OPEN || added) Icon(if (added) Icons.Outlined.RemoveCircleOutline else Icons.Outlined.AddCircleOutline,
            if (added) "移除候补" else "加入候补", Modifier.padding(start = 2.dp).size(17.dp), tint = color)
    }
}

@Composable
private fun ResultAction(icon: ImageVector, label: String, active: Boolean, modifier: Modifier, drawable: Int? = null, badge: Int = 0, onClick: () -> Unit) {
    val color = if (active) OriginColors.Blue else OriginColors.TextPrimary
    Column(modifier.fillMaxHeight().clickable(onClick = onClick), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (drawable != null) Image(painterResource(drawable), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(color))
        else BadgedBox(badge = { if (badge > 0) Badge(containerColor = WaitlistOrange) { Text(badge.toString()) } }) {
            Icon(icon, null, Modifier.size(20.dp), tint = color)
        }
        Text(label, fontSize = 11.sp, lineHeight = 16.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TicketFilterSheet(initial: TicketFilters, seats: List<Pair<String, String>>, departures: List<String>, arrivals: List<String>, onDismiss: () -> Unit, onApply: (TicketFilters) -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White, shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp), dragHandle = null) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.8f)) {
            Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("取消", color = OriginColors.TextSecondary) }
                Text("筛选", color = OriginColors.TextPrimary, fontSize = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(64.dp))
            }
            HorizontalDivider(color = OriginColors.Divider, thickness = .5.dp)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                FilterChoices("车次类型", listOf("high" to "高铁/动车", "normal" to "普速"), setOf(draft.category)) { key -> draft = draft.copy(category = if (draft.category == key) "all" else key) }
                TicketCheck("只看有票", draft.availableOnly, { draft = draft.copy(availableOnly = it) })
                FilterChoices("席别类型", seats, draft.seatCodes) { key -> draft = draft.copy(seatCodes = toggleSet(draft.seatCodes, key)) }
                FilterChoices("出发车站", departures.map { it to it }, draft.fromStations) { key -> draft = draft.copy(fromStations = toggleSet(draft.fromStations, key)) }
                FilterChoices("到达车站", arrivals.map { it to it }, draft.toStations) { key -> draft = draft.copy(toStations = toggleSet(draft.toStations, key)) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { draft = TicketFilters() }, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(22.dp)) { Text("清空", fontSize = 16.sp) }
                Button(onClick = { onApply(draft) }, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(22.dp)) { Text("确认", fontSize = 16.sp) }
            }
        }
    }
}

private fun toggleSet(values: Set<String>, value: String): Set<String> = if (value in values) values - value else values + value

@Composable
private fun FilterChoices(title: String, options: List<Pair<String, String>>, selected: Set<String>, onToggle: (String) -> Unit) {
    if (options.isEmpty()) return
    Column {
        Text(title, color = OriginColors.TextMuted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 10.dp))
        options.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (key, label) ->
                    val active = key in selected
                    Box(Modifier.weight(1f).height(40.dp).background(if (active) Color(0xFFE9F4FF) else Color(0xFFF7F7F7), RoundedCornerShape(3.dp))
                        .border(.5.dp, if (active) OriginColors.Blue else Color(0xFFDDDDDD), RoundedCornerShape(3.dp))
                        .clickable { onToggle(key) }, contentAlignment = Alignment.Center) {
                        Text(label, color = if (active) OriginColors.Blue else OriginColors.TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
