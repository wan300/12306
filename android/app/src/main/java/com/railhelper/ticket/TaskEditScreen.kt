@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.railhelper.ticket

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

@Composable
fun TaskEditScreen(
    initial: TaskDraft,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    onMessage: (String) -> Unit,
    onLogin: (TaskDraft) -> Unit,
    originalDraft: TaskDraft = initial,
) {
    val scope = rememberCoroutineScope()
    var draft by remember(initial) { mutableStateOf(initial.copy(trainDate = initial.trainDate.ifBlank { TicketDates.today() })) }
    var contacts by remember { mutableStateOf<List<Passenger>>(emptyList()) }
    var passengerError by remember { mutableStateOf("") }
    var loadingPassengers by remember { mutableStateOf(false) }
    var loadingTrains by remember { mutableStateOf(false) }
    var trainError by remember { mutableStateOf("") }
    var availableCodes by remember { mutableStateOf(taskCodes(initial.trainCodes)) }
    var trainDetails by remember { mutableStateOf<Map<String, TrainInfo>>(emptyMap()) }
    var refreshingPrice by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf("") }
    var field by remember { mutableStateOf("") }
    var fieldValue by remember { mutableStateOf("") }
    var fieldError by remember { mutableStateOf("") }
    var station by remember { mutableStateOf("") }
    var calendar by remember { mutableStateOf(false) }
    var exitPrompt by remember { mutableStateOf(false) }
    val original = remember(originalDraft) { originalDraft.copy(trainDate = originalDraft.trainDate.ifBlank { TicketDates.today() }) }
    fun leave() { if (!saving) { if (draft != original) exitPrompt = true else onCancel() } }
    BackHandler { leave() }

    fun changeJourney(next: TaskDraft) {
        if (next != draft) {
            draft = next.copy(trainCodes = "")
            availableCodes = emptyList()
            trainDetails = emptyMap()
            trainError = ""
        }
    }
    fun editField(key: String, value: String) { field = key; fieldValue = value; fieldError = "" }
    fun loadContacts() {
        if (loadingPassengers) return
        scope.launch {
            loadingPassengers = true; passengerError = ""
            val result = PythonBridge.call("get_passengers")
            loadingPassengers = false
            if (result.success()) contacts = parsePassengers(result.optJSONArray("data"))
            else passengerError = "暂时无法获取乘车人，请重试或重新登录"
        }
    }
    fun loadTrains() {
        if (loadingTrains) return
        if (draft.fromStation.isBlank() || draft.toStation.isBlank()) { trainError = "请先选择出发站和到达站"; return }
        val snapshot = draft
        scope.launch {
            loadingTrains = true; trainError = ""
            val params = JSONObject().put("from_station", snapshot.fromStation).put("to_station", snapshot.toStation)
                .put("train_date", snapshot.trainDate).put("only_has_ticket", false)
            if (snapshot.trainTypes.isNotEmpty()) params.put("train_types", snapshot.trainTypes.joinToString(","))
            val range = snapshot.startTimeRange.split('-')
            if (range.size == 2) params.put("start_time_min", range[0]).put("start_time_max", range[1])
            val result = PythonBridge.call("query_tickets", params.toString())
            loadingTrains = false
            if (snapshot.fromStation != draft.fromStation || snapshot.toStation != draft.toStation || snapshot.trainDate != draft.trainDate || snapshot.trainTypes != draft.trainTypes || snapshot.startTimeRange != draft.startTimeRange) return@launch
            if (result.success()) {
                trainDetails = parseTrains(result.dataObject()?.optJSONArray("trains")).associateBy { it.trainCode }
                availableCodes = trainDetails.keys.toList()
                if (availableCodes.isEmpty()) trainError = "该行程和出发条件下暂无车次，可调整条件后重试"
            } else trainError = result.message().ifBlank { "查询车次失败，请重试" }
        }
    }
    fun save() {
        if (saving) return
        val problem = taskDraftProblem(draft)
        if (problem != null) { onMessage(problem); return }
        val ready = draft.copy(name = draft.name.trim().ifBlank { "${draft.fromStation}→${draft.toStation} ${TicketDates.monthDay(draft.trainDate)}" })
        scope.launch {
            saving = true
            val result = ready.id?.let { PythonBridge.call("update_task", it, ready.toJson().toString()) }
                ?: PythonBridge.call("create_task", ready.toJson().toString())
            saving = false
            if (result.success()) onDone() else onMessage(result.message())
        }
    }
    fun refreshPrice(train: TrainInfo) {
        if (refreshingPrice != null) return
        val date = draft.trainDate
        refreshingPrice = train.trainCode
        scope.launch {
            try {
                val result = PythonBridge.call("query_train_prices", JSONObject().put("train_no", train.trainNo)
                    .put("from_station_no", train.fromStationNo).put("to_station_no", train.toStationNo)
                    .put("seat_types", train.seatTypes).put("train_date", date).toString())
                if (!result.success()) { onMessage("票价暂未获取，请稍后重试"); return@launch }
                val current = trainDetails[train.trainCode]
                if (date == draft.trainDate && current != null && trainSegmentKey(current) == trainSegmentKey(train)) {
                    trainDetails = trainDetails + (train.trainCode to current.copy(pricesFen = current.pricesFen + parsePrices(result.dataObject()?.optJSONObject("prices_fen"))))
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { onMessage("票价暂未获取，请稍后重试")
            } finally { refreshingPrice = null }
        }
    }

    Surface(Modifier.fillMaxSize(), color = OriginColors.Page, contentColor = OriginColors.TextPrimary) {
        Column(Modifier.imePadding()) {
            TicketTitleBar(if (draft.id == null) "创建抢票任务" else "编辑抢票任务", ::leave)
            LazyColumn(Modifier.weight(1f).background(Brush.verticalGradient(listOf(Color(0xFFCBE5FF), OriginColors.Page), endY = 450f)),
                contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    TaskPanel {
                        TaskRoute(draft.fromStation, draft.toStation, onFrom = { station = "from" }, onTo = { station = "to" },
                            onSwap = { changeJourney(draft.copy(fromStation = draft.toStation, toStation = draft.fromStation)) })
                        HorizontalDivider(color = OriginColors.Divider)
                        TaskValueRow("出发日期", taskDateLabel(draft.trainDate), { calendar = true })
                        TaskValueRow("出发时段", draft.startTimeRange.ifBlank { "全天" }, { editField("出发时段", draft.startTimeRange) }, divider = false)
                    }
                }
                item {
                    TaskPanel {
                        TaskSection("席别选择", "按选择顺序优先购票")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            taskSeats.forEach { (code, label) ->
                                val priority = draft.seatTypes.indexOf(code)
                                TaskOption(if (priority >= 0) "${priority + 1} $label" else label, priority >= 0) {
                                    draft = draft.copy(seatTypes = if (priority >= 0) draft.seatTypes - code else draft.seatTypes + code)
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(taskSeatLabel(draft.seatTypes), fontSize = 12.sp, lineHeight = 18.sp, color = OriginColors.TextMuted)
                        HorizontalDivider(Modifier.padding(top = 12.dp), color = OriginColors.Divider)
                        TaskValueRow("车次类型", if (draft.trainTypes.isEmpty()) "不限" else taskTrainTypes.filter { it.first in draft.trainTypes }.joinToString(" / ") { it.second }, { sheet = "types" })
                        TaskValueRow("指定车次", taskCodes(draft.trainCodes).let { if (it.isEmpty()) "不限车次" else it.joinToString("、") }, {
                            sheet = "trains"; loadTrains()
                        }, divider = false)
                    }
                }
                item {
                    TaskPanel {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("乘车人", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            TextButton(onClick = { sheet = "passengers"; loadContacts() }) {
                                Image(painterResource(R.drawable.origin_waitlist_add_passenger), null, Modifier.size(17.dp))
                                Spacer(Modifier.width(5.dp)); Text("添加乘车人", color = Color(0xFFFF8200), fontSize = 14.sp)
                            }
                        }
                        if (draft.passengers.isEmpty()) Text("添加需要购票的乘车人", Modifier.padding(bottom = 8.dp), fontSize = 13.sp, color = OriginColors.TextMuted)
                        draft.passengers.forEach { passenger ->
                            TaskPassengerRow(passenger, onType = { type -> draft = draft.copy(passengers = draft.passengers.map { if (it.idNo == passenger.idNo) it.copy(passengerType = type) else it }) },
                                onRemove = { draft = draft.copy(passengers = draft.passengers.filterNot { it.idNo == passenger.idNo }) })
                        }
                    }
                }
                item {
                    TaskPanel {
                        TaskSection("抢票设置")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("有票时自动提交", fontSize = 14.sp, lineHeight = 21.sp)
                                Text("提交成功后需手动付款", fontSize = 12.sp, lineHeight = 19.sp, color = OriginColors.TextMuted)
                            }
                            WaitlistSwitch(draft.autoSubmit, { draft = draft.copy(autoSubmit = it) })
                        }
                        HorizontalDivider(Modifier.padding(top = 12.dp), color = OriginColors.Divider)
                        TaskValueRow("更多设置", if (advanced) "收起" else "任务名称、查询频率", { advanced = !advanced }, divider = advanced)
                        if (advanced) {
                            TaskValueRow("任务名称", draft.name.ifBlank { "按行程自动命名" }, { editField("任务名称", draft.name) })
                            TaskValueRow("查询间隔", "${draft.queryInterval} 秒", { editField("查询间隔", draft.queryInterval) })
                            Row(Modifier.heightIn(min = 49.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("持续重试", Modifier.weight(1f), fontSize = 14.sp, color = OriginColors.TextSecondary)
                                WaitlistSwitch(draft.infiniteRetry, { draft = draft.copy(infiniteRetry = it) })
                            }
                            if (!draft.infiniteRetry) TaskValueRow("最多重试", "${draft.maxRetryCount} 次", { editField("最多重试", draft.maxRetryCount) }, divider = false)
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().background(Color.White).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("保存后可在任务列表中启动", fontSize = 12.sp, color = OriginColors.TextMuted, modifier = Modifier.padding(bottom = 8.dp))
                TaskPrimary(if (saving) "正在保存…" else "保存任务", !saving, ::save)
            }
        }
    }

    if (station.isNotBlank()) TicketFullscreen(onBack = { station = "" }) {
        TicketStationScreen(onBack = { station = "" }, onSelect = {
            changeJourney(if (station == "from") draft.copy(fromStation = it) else draft.copy(toStation = it)); station = ""
        })
    }
    if (calendar) TicketFullscreen(onBack = { calendar = false }) {
        TicketCalendarScreen(TicketDates.clamp(draft.trainDate), onBack = { calendar = false }, onSelect = { changeJourney(draft.copy(trainDate = it)); calendar = false })
    }
    if (exitPrompt) AlertDialog(onDismissRequest = { exitPrompt = false }, title = { Text("放弃本次修改？") }, text = { Text("尚未保存的任务设置将丢失。") },
        confirmButton = { TextButton(onClick = onCancel) { Text("放弃修改") } }, dismissButton = { TextButton(onClick = { exitPrompt = false }) { Text("继续编辑") } })
    if (field.isNotBlank()) AlertDialog(onDismissRequest = { field = "" }, title = { Text(field) }, text = {
        Column {
            OutlinedTextField(fieldValue, { fieldValue = if (field in listOf("查询间隔", "最多重试")) it.filter(Char::isDigit) else it; fieldError = "" },
                singleLine = true, label = { Text(when (field) { "出发时段" -> "08:00-12:00，留空为全天"; "查询间隔" -> "3—60 秒"; "最多重试" -> "重试次数"; else -> "留空按行程自动命名" }) },
                keyboardOptions = KeyboardOptions(keyboardType = if (field in listOf("查询间隔", "最多重试")) KeyboardType.Number else KeyboardType.Text), isError = fieldError.isNotBlank())
            if (field == "出发时段") Row { TextButton(onClick = { fieldValue = "" }) { Text("全天") }; TextButton(onClick = { fieldValue = "06:00-12:00" }) { Text("上午") }; TextButton(onClick = { fieldValue = "12:00-23:59" }) { Text("下午 / 晚间") } }
            if (fieldError.isNotBlank()) Text(fieldError, color = OriginColors.Danger, fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(onClick = {
        fieldError = when {
            field == "出发时段" && !taskTimeRangeValid(fieldValue.trim()) -> "请填写有效时段，例如 08:00-12:00"
            field == "查询间隔" && fieldValue.toIntOrNull() !in 3..60 -> "请输入 3—60 之间的秒数"
            field == "最多重试" && (fieldValue.toIntOrNull() ?: 0) <= 0 -> "请输入大于 0 的次数"
            else -> ""
        }
        if (fieldError.isBlank()) {
            when (field) {
                "任务名称" -> draft = draft.copy(name = fieldValue.trim())
                "出发时段" -> changeJourney(draft.copy(startTimeRange = fieldValue.trim()))
                "查询间隔" -> draft = draft.copy(queryInterval = fieldValue)
                "最多重试" -> draft = draft.copy(maxRetryCount = fieldValue)
            }
            field = ""
        }
    }) { Text("确定") } }, dismissButton = { TextButton(onClick = { field = "" }) { Text("取消") } })

    if (sheet.isNotBlank()) ModalBottomSheet(onDismissRequest = { sheet = "" }, containerColor = Color.White, contentColor = OriginColors.TextPrimary,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(max = 520.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(when (sheet) { "types" -> "车次类型"; "trains" -> "选择车次"; else -> "选择乘车人" }, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                TextButton(onClick = { sheet = "" }) { Text("完成") }
            }
            if (sheet == "types") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TaskOption("不限", draft.trainTypes.isEmpty()) { changeJourney(draft.copy(trainTypes = emptySet())) }
                    taskTrainTypes.forEach { (code, label) -> TaskOption("$label ($code)", code in draft.trainTypes) { changeJourney(draft.copy(trainTypes = if (code in draft.trainTypes) draft.trainTypes - code else draft.trainTypes + code)) } }
                }
                Spacer(Modifier.height(24.dp))
            } else if (sheet == "trains") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已选 ${taskCodes(draft.trainCodes).size} 趟", Modifier.weight(1f), fontSize = 13.sp, color = OriginColors.TextSecondary)
                    TextButton(onClick = { draft = draft.copy(trainCodes = "") }) { Text("不限车次") }
                    TextButton(onClick = ::loadTrains, enabled = !loadingTrains) { Text("刷新") }
                }
                if (loadingTrains) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("按所选席别显示成人票价，以提交订单时为准", fontSize = 11.sp, color = OriginColors.TextMuted, modifier = Modifier.padding(bottom = 8.dp))
                if (trainError.isNotBlank()) Text(trainError, fontSize = 13.sp, color = OriginColors.TextSecondary, modifier = Modifier.padding(vertical = 10.dp))
                LazyColumn {
                    items((availableCodes + taskCodes(draft.trainCodes)).distinct()) { code ->
                        val checked = code in taskCodes(draft.trainCodes)
                        Row(Modifier.fillMaxWidth().clickable { val selected = taskCodes(draft.trainCodes); draft = draft.copy(trainCodes = (if (checked) selected - code else selected + code).joinToString(",")) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text(code, fontSize = 17.sp)
                                trainDetails[code]?.let { train ->
                                    Text("${train.startTime}—${train.arriveTime} · ${train.fromStation} → ${train.toStation}",
                                        fontSize = 12.sp, lineHeight = 19.sp, color = OriginColors.TextSecondary)
                                    val seats = trainSeats(train)
                                    val selectedSeats = draft.seatTypes.ifEmpty { seats.map { it.code } }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        selectedSeats.forEach { seatCode ->
                                            val label = taskSeats.firstOrNull { it.first == seatCode }?.second ?: seats.firstOrNull { it.code == seatCode }?.label ?: seatCode
                                            val price = train.pricesFen[seatCode]?.takeIf { it > 0 }
                                            val offered = seats.any { it.code == seatCode }
                                            Text("$label ${if (!offered) "未开设" else price?.let { "¥${ticketPrice(it)}" } ?: "票价待查"}",
                                                fontSize = 12.sp, lineHeight = 20.sp, color = if (offered && price != null) Color(0xFFFF8200) else OriginColors.TextMuted)
                                        }
                                    }
                                    if (selectedSeats.any { seat -> seats.any { it.code == seat } && (train.pricesFen[seat] ?: 0) <= 0 }) {
                                        TextButton(onClick = { refreshPrice(train) }, enabled = refreshingPrice == null, contentPadding = PaddingValues(0.dp)) {
                                            Text(if (refreshingPrice == code) "正在查询票价…" else "查询票价", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                            Checkbox(checked, null)
                        }
                        HorizontalDivider(color = OriginColors.Divider)
                    }
                }
            } else {
                if (loadingPassengers) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (passengerError.isNotBlank() || (!loadingPassengers && contacts.isEmpty())) {
                    Text(passengerError.ifBlank { "暂无乘车人，请在12306中添加后刷新" }, color = OriginColors.TextSecondary, fontSize = 13.sp)
                    TextButton(onClick = ::loadContacts, enabled = !loadingPassengers) { Text("重新获取") }
                    if (passengerError.isNotBlank()) TextButton(onClick = { onLogin(draft) }) { Text("重新登录") }
                }
                LazyColumn {
                    items(contacts, key = { it.idNo }) { passenger ->
                        val checked = draft.passengers.any { it.idNo == passenger.idNo }
                        Row(Modifier.fillMaxWidth().clickable {
                            draft = draft.copy(passengers = if (checked) draft.passengers.filterNot { it.idNo == passenger.idNo }
                                else draft.passengers + passenger.copy(passengerType = normalizeTicketType(passenger.passengerType)))
                        }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(passenger.name, fontSize = 16.sp, lineHeight = 24.sp)
                                Text(maskId(passenger.idNo), fontSize = 12.sp, color = OriginColors.TextMuted)
                            }
                            Checkbox(checked, null)
                        }
                        HorizontalDivider(color = OriginColors.Divider)
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskPassengerRow(passenger: Passenger, onType: (String) -> Unit, onRemove: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(passenger.name, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
            Text(maskId(passenger.idNo), fontSize = 12.sp, color = OriginColors.TextMuted)
        }
        Box {
            TextButton(onClick = { expanded = true }) { Text((ticketTypeOptions.firstOrNull { it.first == normalizeTicketType(passenger.passengerType) }?.second ?: "成人票") + " ⌄", fontSize = 13.sp) }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                ticketTypeOptions.forEach { (code, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { onType(code); expanded = false }) }
            }
        }
        IconButton(onClick = onRemove) { Image(painterResource(R.drawable.origin_waitlist_delete), "移除乘车人", Modifier.size(19.dp)) }
    }
}
