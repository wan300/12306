@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.railhelper.ticket

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DirectionsRailway
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

private const val DAY_MILLIS = 86_400_000L

private val trainTypeOptions = listOf(
    "" to "全部",
    "G" to "高铁（G）",
    "D" to "动车（D）",
    "C" to "城际（C）",
    "Z" to "直达（Z）",
    "T" to "特快（T）",
    "K" to "快速（K）"
)

private val trainTypeCodes = trainTypeOptions.drop(1).map { it.first }

private fun trainTypeLabel(code: String): String =
    trainTypeOptions.firstOrNull { it.first == code }?.second ?: code

private fun splitCsvCodes(value: String): List<String> =
    value.split(",").map { it.trim().uppercase(Locale.ROOT) }.filter { it.isNotBlank() }.distinct()

private fun dateFormatter(): SimpleDateFormat =
    SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }

private fun todayUtcMillis(): Long {
    val local = Calendar.getInstance()
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }
    return utc.timeInMillis
}

private fun parseDateMillis(value: String): Long? =
    try {
        dateFormatter().parse(value)?.time
    } catch (_: Exception) {
        null
    }

private fun formatDateMillis(millis: Long): String = dateFormatter().format(millis)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1206)
        }
        setContent {
            TicketHelperApp()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketHelperApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }
    var initialized by remember { mutableStateOf(false) }
    var initError by remember { mutableStateOf<String?>(null) }
    var user by remember { mutableStateOf<User?>(null) }
    var screen by rememberSaveable { mutableStateOf("home") }
    var editDraft by remember { mutableStateOf<TaskDraft?>(null) }
    var detailTaskId by remember { mutableStateOf<Int?>(null) }

    suspend fun refreshUser(validate: Boolean = false) {
        val res = if (validate) {
            PythonBridge.call("validate_current_session")
        } else {
            PythonBridge.call("get_current_user")
        }
        user = parseUser(res.dataObject())
        if (user == null) screen = "login"
    }

    LaunchedEffect(Unit) {
        try {
            PythonBridge.initialize(context)
            refreshUser(validate = true)
            initialized = true
        } catch (exc: Exception) {
            initError = exc.message ?: "初始化失败"
        }
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("12306抢票助手", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    actions = {
                        IconButton(onClick = {
                            scope.launch {
                                PythonBridge.call("get_current_user")
                                refreshUser()
                            }
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbars) },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = screen == "home",
                        onClick = { screen = "home" },
                        icon = { Icon(Icons.Default.Home, null) },
                        label = { Text("首页") }
                    )
                    NavigationBarItem(
                        selected = screen == "query",
                        onClick = { screen = "query" },
                        icon = { Icon(Icons.Default.Search, null) },
                        label = { Text("查票") }
                    )
                    NavigationBarItem(
                        selected = screen == "tasks",
                        onClick = { screen = "tasks" },
                        icon = { Icon(Icons.Default.List, null) },
                        label = { Text("任务") }
                    )
                    NavigationBarItem(
                        selected = screen == "login",
                        onClick = { screen = "login" },
                        icon = { Icon(Icons.Default.Login, null) },
                        label = { Text(if (user == null) "登录" else "账号") }
                    )
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when {
                    initError != null -> ErrorBox(initError!!)
                    !initialized -> LoadingBox("正在初始化本地核心...")
                    screen == "login" -> LoginScreen(
                        user = user,
                        onLoggedIn = { scope.launch { refreshUser(); screen = "home" } },
                        onLogout = {
                            scope.launch {
                                PythonBridge.call("logout")
                                refreshUser()
                                snackbars.showSnackbar("已退出登录")
                            }
                        },
                        onMessage = { msg -> scope.launch { snackbars.showSnackbar(msg) } }
                    )
                    user == null -> LoginScreen(
                        user = null,
                        onLoggedIn = { scope.launch { refreshUser(); screen = "home" } },
                        onLogout = {},
                        onMessage = { msg -> scope.launch { snackbars.showSnackbar(msg) } }
                    )
                    screen == "query" -> QueryScreen(
                        onCreateTask = { draft ->
                            editDraft = draft
                            screen = "edit"
                        },
                        onMessage = { msg -> scope.launch { snackbars.showSnackbar(msg) } }
                    )
                    screen == "tasks" -> TasksScreen(
                        onCreate = {
                            editDraft = TaskDraft()
                            screen = "edit"
                        },
                        onEdit = { task ->
                            editDraft = task.toDraft()
                            screen = "edit"
                        },
                        onDetail = { id ->
                            detailTaskId = id
                            screen = "detail"
                        },
                        onMessage = { msg -> scope.launch { snackbars.showSnackbar(msg) } }
                    )
                    screen == "edit" -> TaskEditScreen(
                        initial = editDraft ?: TaskDraft(),
                        onDone = {
                            editDraft = null
                            screen = "tasks"
                        },
                        onCancel = { screen = "tasks" },
                        onMessage = { msg -> scope.launch { snackbars.showSnackbar(msg) } }
                    )
                    screen == "detail" && detailTaskId != null -> TaskDetailScreen(
                        taskId = detailTaskId!!,
                        onBack = { screen = "tasks" },
                        onEdit = { task ->
                            editDraft = task.toDraft()
                            screen = "edit"
                        },
                        onMessage = { msg -> scope.launch { snackbars.showSnackbar(msg) } }
                    )
                    else -> HomeScreen(
                        user = user,
                        onQuery = { screen = "query" },
                        onTasks = { screen = "tasks" },
                        onCreate = {
                            editDraft = TaskDraft()
                            screen = "edit"
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun LoadingBox(text: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(text)
    }
}

@Composable
fun ErrorBox(text: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("发生错误", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
        Text(text)
    }
}

@Composable
fun HomeScreen(user: User?, onQuery: () -> Unit, onTasks: () -> Unit, onCreate: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("当前账号", style = MaterialTheme.typography.titleMedium)
                    Text(user?.railwayUsername ?: "未登录", fontWeight = FontWeight.SemiBold)
                    Text("任务会在手机本地运行；启动任务后会显示常驻通知。")
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onQuery, modifier = Modifier.weight(1f)) { Text("查票") }
                Button(onClick = onCreate, modifier = Modifier.weight(1f)) { Text("创建任务") }
            }
        }
        item {
            OutlinedButton(onClick = onTasks, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.List, null)
                Spacer(Modifier.size(8.dp))
                Text("查看任务列表")
            }
        }
    }
}

@Composable
fun LoginScreen(
    user: User?,
    onLoggedIn: () -> Unit,
    onLogout: () -> Unit,
    onMessage: (String) -> Unit
) {
    WebLoginScreen(
        user = user,
        onLoggedIn = onLoggedIn,
        onLogout = onLogout,
        onMessage = onMessage
    )
}
@Composable
fun QrImage(base64: String) {
    val bytes = remember(base64) { Base64.decode(base64, Base64.DEFAULT) }
    val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
    Image(bitmap = bitmap.asImageBitmap(), contentDescription = "登录二维码", modifier = Modifier.size(240.dp))
}

@Composable
fun QueryScreen(onCreateTask: (TaskDraft) -> Unit, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var from by rememberSaveable { mutableStateOf("") }
    var to by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf("") }
    var trainType by rememberSaveable { mutableStateOf("") }
    var onlyHasTicket by rememberSaveable { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var trains by remember { mutableStateOf<List<TrainInfo>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("车票查询", style = MaterialTheme.typography.titleLarge)
                    StationInput("出发站", from, { from = it }, onMessage)
                    StationInput("到达站", to, { to = it }, onMessage)
                    DatePickerField("出发日期", date) { date = it }
                    TrainTypeDropdown("车次类型", trainType) { trainType = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = onlyHasTicket, onCheckedChange = { onlyHasTicket = it })
                        Text("只看有票")
                    }
                    Button(
                        enabled = !loading,
                        onClick = {
                            scope.launch {
                                if (from.isBlank() || to.isBlank() || date.isBlank()) {
                                    onMessage("请填写出发站、到达站和日期")
                                    return@launch
                                }
                                loading = true
                                val params = JSONObject()
                                    .put("from_station", from)
                                    .put("to_station", to)
                                    .put("train_date", date)
                                    .put("train_types", trainType.ifBlank { JSONObject.NULL })
                                    .put("only_has_ticket", onlyHasTicket)
                                val res = PythonBridge.call("query_tickets", params.toString())
                                loading = false
                                if (!res.success()) {
                                    onMessage(res.message())
                                }
                                trains = parseTrains(res.dataObject()?.optJSONArray("trains"))
                                selected = emptySet()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Search, null)
                        Spacer(Modifier.size(8.dp))
                        Text(if (loading) "查询中..." else "查询")
                    }
                }
            }
        }
        if (selected.isNotEmpty()) {
            item {
                Button(
                    onClick = {
                        onCreateTask(
                            TaskDraft(
                                name = "$date $from-$to 抢票",
                                fromStation = from,
                                toStation = to,
                                trainDate = date,
                                trainCodes = selected.joinToString(","),
                                trainTypes = trainType.takeIf { it.isNotBlank() }?.let { setOf(it) } ?: emptySet()
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.size(8.dp))
                    Text("用已选车次创建任务 (${selected.size})")
                }
            }
        }
        items(trains, key = { it.trainCode }) { train ->
            TrainCard(
                train = train,
                checked = selected.contains(train.trainCode),
                onCheckedChange = { checked ->
                    selected = if (checked) selected + train.trainCode else selected - train.trainCode
                }
            )
        }
    }
}

@Composable
fun StationInput(label: String, value: String, onValue: (String) -> Unit, onMessage: (String) -> Unit) {
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    var searchRequest by remember { mutableStateOf(0) }

    LaunchedEffect(value, expanded, searchRequest) {
        if (!expanded) return@LaunchedEffect
        val keyword = value.trim()
        if (keyword.isBlank()) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        val res = PythonBridge.call("search_stations", keyword)
        if (!res.success()) {
            suggestions = emptyList()
            onMessage(res.message())
            return@LaunchedEffect
        }
        val arr = res.dataObject()?.optJSONArray("stations") ?: JSONArray()
        suggestions = (0 until arr.length()).map { arr.getJSONObject(it).optString("name") }.filter { it.isNotBlank() }
    }

    Box {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValue(it)
                expanded = true
            },
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = {
                    expanded = true
                    searchRequest += 1
                }) {
                    Icon(Icons.Default.Search, null)
                }
            }
        )
        DropdownMenu(
            expanded = expanded && suggestions.isNotEmpty(),
            onDismissRequest = { expanded = false }
        ) {
            suggestions.take(8).forEach { station ->
                DropdownMenuItem(
                    text = { Text(station) },
                    onClick = {
                        onValue(station)
                        expanded = false
                        suggestions = emptyList()
                    }
                )
            }
        }
    }
}

@Composable
fun DatePickerField(label: String, value: String, onValue: (String) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    val minDate = remember { todayUtcMillis() }
    val maxDate = remember { minDate + 15 * DAY_MILLIS }
    val selectedDate = remember(value) { parseDateMillis(value)?.coerceIn(minDate, maxDate) ?: minDate }

    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().clickable { showDialog = true },
        trailingIcon = {
            IconButton(onClick = { showDialog = true }) {
                Icon(Icons.Default.DateRange, null)
            }
        }
    )

    if (showDialog) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis in minDate..maxDate
            }
        )
        DatePickerDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(
                    enabled = pickerState.selectedDateMillis != null,
                    onClick = {
                        pickerState.selectedDateMillis?.let { onValue(formatDateMillis(it)) }
                        showDialog = false
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("取消")
                }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
fun TrainTypeDropdown(label: String, value: String, onValue: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = trainTypeLabel(value),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth().clickable { expanded = true },
            trailingIcon = {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Default.ArrowDropDown, null)
                }
            }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            trainTypeOptions.forEach { (code, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onValue(code)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun TrainCard(train: TrainInfo, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = onCheckedChange)
                Column(Modifier.weight(1f)) {
                    Text(train.trainCode, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${train.fromStation} ${train.startTime} → ${train.toStation} ${train.arriveTime} · ${train.duration}")
                }
            }
            Text("商务 ${train.businessSeat} · 一等 ${train.firstSeat} · 二等 ${train.secondSeat}")
            Text("软卧 ${train.softSleeper} · 硬卧 ${train.hardSleeper} · 硬座 ${train.hardSeat} · 无座 ${train.noSeat}")
        }
    }
}

@Composable
fun TasksScreen(
    onCreate: () -> Unit,
    onEdit: (TicketTask) -> Unit,
    onDetail: (Int) -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf<List<TicketTask>>(emptyList()) }
    var filter by rememberSaveable { mutableStateOf("") }

    fun load() {
        scope.launch {
            val res = PythonBridge.call("get_tasks", filter)
            tasks = parseTasks(res.dataObject()?.optJSONArray("tasks"))
        }
    }

    LaunchedEffect(filter) { load() }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onCreate, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.size(8.dp))
                    Text("创建任务")
                }
                OutlinedButton(onClick = { filter = ""; load() }) { Text("全部") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                listOf("pending", "running", "paused", "success", "failed", "cancelled").forEach { status ->
                    FilterChip(selected = filter == status, onClick = { filter = status }, label = { Text(statusText(status)) })
                }
            }
        }
        items(tasks, key = { it.id }) { task ->
            TaskCard(
                task = task,
                onDetail = { onDetail(task.id) },
                onEdit = { onEdit(task) },
                onStart = {
                    scope.launch {
                        requestBatteryOptimizationExemption(context)
                        val res = PythonBridge.call("start_task", task.id)
                        if (!res.success()) onMessage(res.message())
                        TaskRunnerService.start(context)
                        load()
                    }
                },
                onStop = {
                    scope.launch {
                        PythonBridge.call("stop_task", task.id)
                        load()
                    }
                },
                onCancel = {
                    scope.launch {
                        PythonBridge.call("cancel_task", task.id)
                        load()
                    }
                },
                onDelete = {
                    scope.launch {
                        PythonBridge.call("delete_task", task.id)
                        load()
                    }
                }
            )
        }
    }
}

@Composable
fun TaskCard(
    task: TicketTask,
    onDetail: () -> Unit,
    onEdit: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(task.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${task.fromStation} → ${task.toStation} · ${task.trainDate}")
                }
                AssistChip(onClick = {}, label = { Text(statusText(task.status)) })
            }
            Text("席别 ${formatSeatTypes(task.seatTypes)} · 重试 ${task.retryCount}/${if (task.maxRetryCount < 0) "∞" else task.maxRetryCount}")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDetail) { Text("详情") }
                if (task.status != "running" && task.status != "success") OutlinedButton(onClick = onEdit) { Text("编辑") }
                if (task.status in listOf("pending", "paused", "failed", "cancelled")) Button(onClick = onStart) {
                    Icon(Icons.Default.PlayArrow, null)
                    Text("启动")
                }
                if (task.status == "running") OutlinedButton(onClick = onStop) {
                    Icon(Icons.Default.Pause, null)
                    Text("暂停")
                }
                if (task.status !in listOf("success", "cancelled")) OutlinedButton(onClick = onCancel) {
                    Icon(Icons.Default.Stop, null)
                    Text("取消")
                }
                if (task.status != "running") OutlinedButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

@Composable
fun TaskEditScreen(
    initial: TaskDraft,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    onMessage: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(initial) }
    var contacts by remember { mutableStateOf<List<Passenger>>(emptyList()) }
    var loadingPassengers by remember { mutableStateOf(false) }
    var loadingTrains by remember { mutableStateOf(false) }
    var availableTrainCodes by remember(initial.id) { mutableStateOf(splitCsvCodes(initial.trainCodes)) }

    fun updateSelectedTrainCodes(codes: List<String>) {
        draft = draft.copy(trainCodes = codes.distinct().joinToString(","))
    }

    fun queryTrainCodes() {
        scope.launch {
            if (draft.fromStation.isBlank() || draft.toStation.isBlank() || draft.trainDate.isBlank()) {
                onMessage("请先填写出发站、到达站和出发日期")
                return@launch
            }
            loadingTrains = true
            val params = JSONObject()
                .put("from_station", draft.fromStation)
                .put("to_station", draft.toStation)
                .put("train_date", draft.trainDate)
                .put("only_has_ticket", false)
            if (draft.trainTypes.isNotEmpty()) {
                params.put("train_types", draft.trainTypes.joinToString(","))
            }
            val range = draft.startTimeRange.split("-").map { it.trim() }
            if (range.size == 2 && range[0].isNotBlank() && range[1].isNotBlank()) {
                params.put("start_time_min", range[0])
                params.put("start_time_max", range[1])
            }
            val res = PythonBridge.call("query_tickets", params.toString())
            loadingTrains = false
            if (!res.success()) {
                onMessage(res.message())
                return@launch
            }
            val codes = parseTrains(res.dataObject()?.optJSONArray("trains")).map { it.trainCode }.distinct()
            availableTrainCodes = codes
            updateSelectedTrainCodes(splitCsvCodes(draft.trainCodes).filter { it in codes })
            onMessage("查询到 ${codes.size} 个符合条件的车次")
        }
    }

    fun addPassenger(passenger: Passenger) {
        if (draft.passengers.none { it.idNo == passenger.idNo }) {
            draft = draft.copy(passengers = draft.passengers + passenger.copy(passengerType = normalizeTicketType(passenger.passengerType)))
        }
    }

    fun removePassenger(idNo: String) {
        draft = draft.copy(passengers = draft.passengers.filterNot { it.idNo == idNo })
    }

    fun updatePassengerTicketType(idNo: String, ticketType: String) {
        draft = draft.copy(
            passengers = draft.passengers.map { passenger ->
                if (passenger.idNo == idNo) passenger.copy(passengerType = normalizeTicketType(ticketType)) else passenger
            }
        )
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (draft.id == null) "创建抢票任务" else "编辑抢票任务", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, label = { Text("任务名称") }, modifier = Modifier.fillMaxWidth())
                    StationInput("出发站", draft.fromStation, { draft = draft.copy(fromStation = it) }, onMessage)
                    StationInput("到达站", draft.toStation, { draft = draft.copy(toStation = it) }, onMessage)
                    DatePickerField("出发日期", draft.trainDate) { draft = draft.copy(trainDate = it) }
                    OutlinedTextField(draft.startTimeRange, { draft = draft.copy(startTimeRange = it) }, label = { Text("出发时间段，例如 08:00-12:00") }, modifier = Modifier.fillMaxWidth())
                    TrainTypeChipSet(
                        title = "车次类型",
                        selected = draft.trainTypes,
                        onChange = { values ->
                            draft = draft.copy(trainTypes = values)
                        }
                    )
                    SeatPriorityEditor(draft.seatTypes) { draft = draft.copy(seatTypes = it) }
                    TrainCodeSelector(
                        availableCodes = availableTrainCodes,
                        selectedCodes = splitCsvCodes(draft.trainCodes),
                        loading = loadingTrains,
                        onQuery = ::queryTrainCodes,
                        onToggle = { code ->
                            val selectedCodes = splitCsvCodes(draft.trainCodes)
                            updateSelectedTrainCodes(
                                if (selectedCodes.contains(code)) selectedCodes - code else selectedCodes + code
                            )
                        }
                    )
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("乘车人", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        OutlinedButton(
                            enabled = !loadingPassengers,
                            onClick = {
                                scope.launch {
                                    loadingPassengers = true
                                    val res = PythonBridge.call("get_passengers")
                                    loadingPassengers = false
                                    if (!res.success()) {
                                        onMessage(res.message())
                                        return@launch
                                    }
                                    contacts = parsePassengers(res.optJSONArray("data"))
                                }
                            }
                        ) { Text("获取联系人") }
                    }
                    if (draft.passengers.isEmpty()) {
                        Text("请先获取联系人并选择至少一名乘车人")
                    } else {
                        Text("已选乘车人", style = MaterialTheme.typography.labelLarge)
                        draft.passengers.forEach { passenger ->
                            SelectedPassengerTicketTypeRow(
                                passenger = passenger,
                                onTicketTypeChange = { ticketType -> updatePassengerTicketType(passenger.idNo, ticketType) },
                                onRemove = { removePassenger(passenger.idNo) }
                            )
                        }
                    }
                    if (contacts.isNotEmpty()) {
                        Text("联系人", style = MaterialTheme.typography.labelLarge)
                        contacts.forEach { passenger ->
                            val checked = draft.passengers.any { it.idNo == passenger.idNo }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { isChecked ->
                                        if (isChecked) addPassenger(passenger) else removePassenger(passenger.idNo)
                                    }
                                )
                                Column(Modifier.weight(1f)) {
                                    Text("${passenger.name} · ${maskId(passenger.idNo)}")
                                    Text(
                                        "联系人身份：${passengerIdentityLabel(passenger.passengerType)}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("任务配置", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(draft.queryInterval, { draft = draft.copy(queryInterval = it.filter(Char::isDigit)) }, label = { Text("刷票间隔 3-60 秒") }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(draft.infiniteRetry, onCheckedChange = { draft = draft.copy(infiniteRetry = it) })
                        Text("无限重试")
                    }
                    if (!draft.infiniteRetry) {
                        OutlinedTextField(draft.maxRetryCount, { draft = draft.copy(maxRetryCount = it.filter(Char::isDigit)) }, label = { Text("最大重试次数") }, modifier = Modifier.fillMaxWidth())
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(draft.autoSubmit, onCheckedChange = { draft = draft.copy(autoSubmit = it) })
                        Text("发现余票后自动提交订单")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    if (!draft.isValid()) {
                                        onMessage("请填写完整任务信息并选择乘车人/席别")
                                        return@launch
                                    }
                                    val taskId = draft.id
                                    val res = if (taskId == null) {
                                        PythonBridge.call("create_task", draft.toJson().toString())
                                    } else {
                                        PythonBridge.call("update_task", taskId, draft.toJson().toString())
                                    }
                                    if (res.success()) onDone() else onMessage(res.message())
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("保存") }
                        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
                    }
                }
            }
        }
    }
}

@Composable
fun TrainTypeChipSet(title: String, selected: Set<String>, onChange: (Set<String>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = selected.isEmpty(),
                onClick = { onChange(emptySet()) },
                label = { Text("全部") }
            )
            trainTypeCodes.forEach { code ->
                FilterChip(
                    selected = selected.contains(code),
                    onClick = {
                        val next = if (selected.contains(code)) selected - code else selected + code
                        onChange(trainTypeCodes.filter { it in next }.toSet())
                    },
                    label = { Text(trainTypeLabel(code)) }
                )
            }
        }
    }
}

@Composable
fun TrainCodeSelector(
    availableCodes: List<String>,
    selectedCodes: List<String>,
    loading: Boolean,
    onQuery: () -> Unit,
    onToggle: (String) -> Unit
) {
    val optionCodes = (availableCodes + selectedCodes).distinct()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("指定车次", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            OutlinedButton(enabled = !loading, onClick = onQuery) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                } else {
                    Icon(Icons.Default.Search, null)
                }
                Spacer(Modifier.size(6.dp))
                Text(if (loading) "查询中..." else "查询车次")
            }
        }
        if (optionCodes.isEmpty()) {
            Text("留空表示不限车次", style = MaterialTheme.typography.bodySmall)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                optionCodes.forEach { code ->
                    FilterChip(
                        selected = selectedCodes.contains(code),
                        onClick = { onToggle(code) },
                        label = { Text(code) }
                    )
                }
            }
        }
    }
}

@Composable
fun SeatPriorityEditor(selected: List<String>, onChange: (List<String>) -> Unit) {
    val seats = listOf("9" to "商务", "M" to "一等", "O" to "二等", "4" to "软卧", "3" to "硬卧", "1" to "硬座")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("席别优先级", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            seats.forEach { (code, label) ->
                FilterChip(
                    selected = selected.contains(code),
                    onClick = {
                        onChange(if (selected.contains(code)) selected - code else selected + code)
                    },
                    label = { Text(label) }
                )
            }
        }
        Text("选择顺序即优先级：${formatSeatTypes(selected.joinToString(","))}")
    }
}

@Composable
fun SelectedPassengerTicketTypeRow(
    passenger: Passenger,
    onTicketTypeChange: (String) -> Unit,
    onRemove: () -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(passenger.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(maskId(passenger.idNo), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onRemove) { Text("移除") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ticketTypeOptions.forEach { (code, label) ->
                FilterChip(
                    selected = normalizeTicketType(passenger.passengerType) == code,
                    onClick = { onTicketTypeChange(code) },
                    label = { Text(label) }
                )
            }
        }
    }
}

@Composable
fun TaskDetailScreen(
    taskId: Int,
    onBack: () -> Unit,
    onEdit: (TicketTask) -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var task by remember { mutableStateOf<TicketTask?>(null) }
    var logs by remember { mutableStateOf<List<TaskLog>>(emptyList()) }

    suspend fun load() {
        val taskRes = PythonBridge.call("get_task", taskId)
        if (taskRes.success()) task = taskRes.dataObject()?.let { parseTask(it) }
        val logRes = PythonBridge.call("get_task_logs", taskId, 200)
        logs = parseLogs(logRes.dataObject()?.optJSONArray("logs"))
    }

    LaunchedEffect(taskId) {
        while (true) {
            load()
            delay(if (task?.status == "running") 3_000 else 8_000)
        }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            OutlinedButton(onClick = onBack) { Text("返回任务列表") }
        }
        val current = task
        if (current != null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(current.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            AssistChip(onClick = {}, label = { Text(statusText(current.status)) })
                        }
                        Text("${current.fromStation} → ${current.toStation} · ${current.trainDate}")
                        Text("车次 ${current.trainCodes ?: "不限"} · 席别 ${formatSeatTypes(current.seatTypes)}")
                        Text("重试 ${current.retryCount}/${if (current.maxRetryCount < 0) "∞" else current.maxRetryCount} · 间隔 ${current.queryInterval}s")
                        if (current.resultMessage != null) Text(current.resultMessage)
                        if (current.orderId != null) Text("订单号：${current.orderId}", fontWeight = FontWeight.Bold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (current.status != "running" && current.status != "success") OutlinedButton(onClick = { onEdit(current) }) { Text("编辑") }
                            if (current.status in listOf("pending", "paused", "failed", "cancelled")) Button(onClick = {
                                scope.launch {
                                    requestBatteryOptimizationExemption(context)
                                    val res = PythonBridge.call("start_task", current.id)
                                    if (!res.success()) onMessage(res.message())
                                    TaskRunnerService.start(context)
                                    load()
                                }
                            }) { Text("启动") }
                            if (current.status == "running") OutlinedButton(onClick = {
                                scope.launch { PythonBridge.call("stop_task", current.id); load() }
                            }) { Text("暂停") }
                            if (current.status == "success") Button(onClick = { openPaymentPage(context) }) { Text("去支付") }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("运行日志", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = { scope.launch { load() } }) { Icon(Icons.Default.Refresh, null) }
                        }
                        logs.ifEmpty { listOf(TaskLog(0, "info", "暂无日志", "")) }.forEach { log ->
                            Text("${log.createdAt} [${log.level}] ${log.message}")
                        }
                    }
                }
            }
        } else {
            item { LoadingBox("正在加载任务...") }
        }
    }
}

fun TicketTask.toDraft(): TaskDraft {
    val passengers = try {
        parsePassengers(JSONArray(this.passengers))
    } catch (_: Exception) {
        emptyList()
    }
    return TaskDraft(
        id = id,
        name = name,
        fromStation = fromStation,
        toStation = toStation,
        trainDate = trainDate,
        trainCodes = trainCodes.orEmpty(),
        trainTypes = trainTypes?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet(),
        seatTypes = seatTypes.split(",").filter { it.isNotBlank() },
        startTimeRange = startTimeRange.orEmpty(),
        passengers = passengers,
        queryInterval = queryInterval.toString(),
        maxRetryCount = maxRetryCount.takeIf { it >= 0 }?.toString() ?: "100",
        infiniteRetry = maxRetryCount < 0,
        autoSubmit = autoSubmit
    )
}

fun TaskDraft.isValid(): Boolean =
    name.isNotBlank() &&
        fromStation.isNotBlank() &&
        toStation.isNotBlank() &&
        trainDate.isNotBlank() &&
        seatTypes.isNotEmpty() &&
        passengers.isNotEmpty()

fun statusText(status: String): String = when (status) {
    "pending" -> "待运行"
    "running" -> "运行中"
    "paused" -> "已暂停"
    "success" -> "成功"
    "failed" -> "失败"
    "cancelled" -> "已取消"
    else -> status
}

fun formatSeatTypes(types: String): String {
    val map = mapOf("9" to "商务", "M" to "一等", "O" to "二等", "4" to "软卧", "3" to "硬卧", "1" to "硬座")
    return types.split(",").filter { it.isNotBlank() }.joinToString("、") { map[it] ?: it }
}

fun maskId(id: String): String =
    if (id.length < 8) id else id.take(4) + "****" + id.takeLast(4)

fun shareQr(context: Context, base64: String, onMessage: (String) -> Unit) {
    if (base64.isBlank()) return
    runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val file = File(context.cacheDir, "login-qrcode.png")
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "分享登录二维码"))
    }.onFailure { onMessage(it.message ?: "分享失败") }
}

fun openPaymentPage(context: Context) {
    val uri = Uri.parse("https://kyfw.12306.cn/otn/view/train_order.html")
    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
}

fun requestBatteryOptimizationExemption(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val powerManager = context.getSystemService(PowerManager::class.java)
    if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) return
    runCatching {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
