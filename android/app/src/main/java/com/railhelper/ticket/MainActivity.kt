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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DirectionsRailway
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class TicketQueryResult(
    val from: String,
    val to: String,
    val date: String,
    val trainType: String,
    val studentTicket: Boolean,
    val onlyHasTicket: Boolean,
    val trains: List<TrainInfo>
)

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

internal sealed class TicketPage(val key: String) {
    data object Home : TicketPage("home")
    data object Results : TicketPage("results")
    data object Tasks : TicketPage("tasks")
    data class Detail(val taskId: Int) : TicketPage("detail")
    data class Edit(val original: TaskDraft, val draft: TaskDraft = original) : TicketPage("edit")
    data class Login(val newTask: TaskDraft? = null, val reauthenticate: Boolean = false) : TicketPage("login")
}

class TicketSearchState : ViewModel() {
    val result = mutableStateOf<TicketQueryResult?>(null)
    val waitlist = TicketWaitlistState(viewModelScope)
    internal val pages = mutableStateOf<List<TicketPage>>(listOf(TicketPage.Home))
}

class MainActivity : ComponentActivity() {
    private val searchState by viewModels<TicketSearchState>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(59, 153, 252)
        window.navigationBarColor = android.graphics.Color.WHITE
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1206)
        }
        setContent {
            TicketHelperApp(searchState, onExit = ::finish)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketHelperApp(searchState: TicketSearchState, onExit: () -> Unit) {
    val context = LocalContext.current
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }
    var initialized by remember { mutableStateOf(false) }
    var showSplash by remember { mutableStateOf(true) }
    var initError by remember { mutableStateOf<String?>(null) }
    var user by remember { mutableStateOf<User?>(null) }
    var pages by searchState.pages
    val page = pages.last()
    val screenState = rememberSaveableStateHolder()
    var confirmExit by rememberSaveable { mutableStateOf(false) }
    var queryResult by searchState.result

    fun open(next: TicketPage) {
        pages = pages + next
    }

    fun goBack() {
        if (pages.size == 1) {
            confirmExit = true
        } else {
            screenState.removeState(pages.last().key)
            pages = pages.dropLast(1)
        }
    }

    fun openTab(tab: String) {
        if (pages.last().key == tab && pages.size <= 2) return
        pages.drop(1).forEach { screenState.removeState(it.key) }
        pages = when (tab) {
            "tasks" -> listOf(TicketPage.Home, TicketPage.Tasks)
            "login" -> listOf(TicketPage.Home, TicketPage.Login())
            else -> listOf(TicketPage.Home)
        }
    }

    suspend fun refreshUser(validate: Boolean = false) {
        val res = if (validate) {
            PythonBridge.call("validate_current_session")
        } else {
            PythonBridge.call("get_current_user")
        }
        user = parseUser(res.dataObject())
    }

    fun openTaskDraft(draft: TaskDraft) {
        val initial = draft.copy(trainDate = draft.trainDate.ifBlank { TicketDates.today() })
        if (user == null) {
            open(TicketPage.Login(newTask = initial))
            scope.launch { snackbars.showSnackbar("请先登录 12306 后继续创建抢票任务") }
        } else {
            open(TicketPage.Edit(initial))
        }
    }

    LaunchedEffect(Unit) {
        val startedAt = System.currentTimeMillis()
        try {
            PythonBridge.initialize(context)
            refreshUser(validate = true)
            initialized = true
        } catch (exc: Exception) {
            initError = exc.message ?: "初始化失败"
        }
        val elapsed = System.currentTimeMillis() - startedAt
        if (elapsed < 1600) delay(1600 - elapsed)
        showSplash = false
    }

    OriginTheme {
        BackHandler(onBack = ::goBack)
        if (confirmExit) {
            AlertDialog(
                onDismissRequest = { confirmExit = false },
                title = { Text("退出应用？") },
                text = { Text("确认退出 12306 抢票助手吗？") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmExit = false
                        onExit()
                    }) { Text("退出") }
                },
                dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("取消") } }
            )
        }
        if (showSplash) {
            JiebaoSplashScreen()
            return@OriginTheme
        }

        val showBottomNav = page == TicketPage.Home || page == TicketPage.Tasks ||
            (page is TicketPage.Login && pages.size == 2 && page.newTask == null)
        Scaffold(
            containerColor = OriginColors.Blue,
            topBar = {
                if (page is TicketPage.Login) {
                    TicketTitleBar(
                        title = if (user == null || page.newTask != null || page.reauthenticate) "登录12306" else "账号",
                        onBack = { backDispatcher?.onBackPressed() ?: goBack() },
                        trailing = {
                            IconButton(onClick = {
                                scope.launch {
                                    PythonBridge.call("get_current_user")
                                    refreshUser()
                                }
                            }) {
                                Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = Color.White)
                            }
                        }
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbars) },
            bottomBar = {
                if (showBottomNav) TicketBottomNavigation(page.key, user != null, ::openTab)
                else Spacer(Modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars).background(Color.White))
            }
        ) { padding ->
            screenState.SaveableStateProvider(page.key) {
                Box(Modifier.fillMaxSize().padding(padding).originPage()) {
                    when {
                        initError != null -> ErrorBox(initError!!)
                        !initialized -> LoadingBox("正在初始化本地核心...")
                        page is TicketPage.Login -> LoginScreen(
                            user = if (page.newTask != null || page.reauthenticate) null else user,
                            onLoggedIn = {
                                scope.launch {
                                    refreshUser()
                                    if (pages.last() !== page) return@launch
                                    goBack()
                                    page.newTask?.let { open(TicketPage.Edit(it)) }
                                }
                            },
                            onLogout = {
                                scope.launch {
                                    PythonBridge.call("logout")
                                    refreshUser()
                                    if (pages.last() === page) goBack()
                                    snackbars.showSnackbar("已退出登录")
                                }
                            },
                            onMessage = { msg -> scope.launch { snackbars.showSnackbar(msg) } }
                        )
                        page == TicketPage.Tasks -> TasksScreen(
                            onCreate = { open(TicketPage.Edit(TaskDraft(trainDate = TicketDates.today()))) },
                            onEdit = { task -> open(TicketPage.Edit(task.toDraft())) },
                            onDetail = { id -> open(TicketPage.Detail(id)) },
                            onMessage = { msg -> scope.launch { snackbars.showSnackbar(msg) } }
                        )
                        page is TicketPage.Edit -> TaskEditScreen(
                            initial = page.draft,
                            originalDraft = page.original,
                            onDone = { if (page.original.id == null) openTab("tasks") else goBack() },
                            onCancel = ::goBack,
                            onLogin = { draft ->
                                pages = pages.dropLast(1) + page.copy(draft = draft) + TicketPage.Login(reauthenticate = true)
                            },
                            onMessage = { msg -> scope.launch { snackbars.showSnackbar(msg) } }
                        )
                        page is TicketPage.Detail -> TaskDetailScreen(
                            taskId = page.taskId,
                            onBack = ::goBack,
                            onEdit = { task -> open(TicketPage.Edit(task.toDraft())) },
                            onMessage = { msg -> scope.launch { snackbars.showSnackbar(msg) } }
                        )
                        page == TicketPage.Results && queryResult != null -> TicketResultsScreen(
                            initial = queryResult!!,
                            user = user,
                            waitlist = searchState.waitlist,
                            onLogin = { open(TicketPage.Login()) },
                            onBack = ::goBack,
                            onCreateTask = ::openTaskDraft,
                            onResultChanged = { queryResult = it },
                            onMessage = { msg -> scope.launch { snackbars.showSnackbar(msg) } }
                        )
                        else -> HomeScreen(
                            user = user,
                            onTasks = { openTab("tasks") },
                            onAccount = { openTab("login") },
                            onCreateTask = ::openTaskDraft,
                            onResults = { result ->
                                queryResult = result
                                open(TicketPage.Results)
                            },
                            onMessage = { msg -> scope.launch { snackbars.showSnackbar(msg) } }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun JiebaoSplashScreen() {
    val transition = rememberInfiniteTransition(label = "splash")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "splashAngle"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OriginColors.Blue)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 150.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(220.dp)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = Stroke(width = 1.4.dp.toPx())
                    val diameter = size.minDimension * 0.92f
                    val inset = (size.minDimension - diameter) / 2f
                    val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)
                    val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                    drawArc(
                        color = Color.White.copy(alpha = 0.28f),
                        startAngle = angle,
                        sweepAngle = 240f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = stroke
                    )
                    drawArc(
                        color = Color.White.copy(alpha = 0.56f),
                        startAngle = -angle * 0.72f,
                        sweepAngle = 150f,
                        useCenter = false,
                        topLeft = topLeft.copy(x = topLeft.x + 24f, y = topLeft.y + 24f),
                        size = androidx.compose.ui.geometry.Size(diameter - 48f, diameter - 48f),
                        style = stroke
                    )
                    repeat(9) { index ->
                        val dotAngle = Math.toRadians((index * 38 + angle / 3f).toDouble())
                        val radius = diameter * 0.38f
                        val center = androidx.compose.ui.geometry.Offset(
                            x = size.width / 2f + kotlin.math.cos(dotAngle).toFloat() * radius,
                            y = size.height / 2f + kotlin.math.sin(dotAngle).toFloat() * radius
                        )
                        drawCircle(Color.White.copy(alpha = 0.45f), radius = 1.8.dp.toPx(), center = center)
                    }
                }
                Surface(
                    modifier = Modifier.size(92.dp),
                    shape = CircleShape,
                    color = Color.White
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.DirectionsRailway,
                            contentDescription = null,
                            tint = OriginColors.Blue,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }
            }
            Text(
                text = "尽享精彩出行服务",
                color = Color.White,
                fontSize = 31.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Canvas(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .width(280.dp)
                    .height(18.dp)
            ) {
                drawArc(
                    color = Color.White.copy(alpha = 0.75f),
                    startAngle = 190f,
                    sweepAngle = 160f,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, -22f),
                    size = androidx.compose.ui.geometry.Size(size.width, 40f),
                    style = Stroke(width = 1.2.dp.toPx())
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Copyright © 2026", color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
            Spacer(Modifier.height(5.dp))
            Text(androidx.compose.ui.res.stringResource(R.string.brand_name), color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
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
fun HomeScreen(
    user: User?,
    onTasks: () -> Unit,
    onAccount: () -> Unit,
    onCreateTask: (TaskDraft) -> Unit,
    onResults: (TicketQueryResult) -> Unit,
    onMessage: (String) -> Unit
) {
    QueryScreen(
        user = user,
        onTasks = onTasks,
        onAccount = onAccount,
        onCreateTask = onCreateTask,
        onResults = onResults,
        onMessage = onMessage
    )
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
fun StationInput(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
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
            modifier = modifier.fillMaxWidth(),
            colors = OriginFieldColors(),
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
    var showDialog by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        colors = OriginFieldColors(),
        trailingIcon = {
            IconButton(onClick = { showDialog = true }) {
                Icon(Icons.Default.DateRange, contentDescription = "选择出发日期")
            }
        }
    )
    if (showDialog) TicketFullscreen(onBack = { showDialog = false }) {
        TicketCalendarScreen(TicketDates.clamp(value), onBack = { showDialog = false }, onSelect = {
            onValue(it)
            showDialog = false
        })
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
            colors = OriginFieldColors(),
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
    OriginCard(contentPadding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = onCheckedChange)
                Column(Modifier.weight(1f)) {
                    Text(
                        train.trainCode,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = OriginColors.TextPrimary
                    )
                    Text(
                        "${train.fromStation} ${train.startTime} → ${train.toStation} ${train.arriveTime} · ${train.duration}",
                        color = OriginColors.TextSecondary
                    )
                }
                OriginStatusChip(if (train.canBuy) "可购" else "候补", if (train.canBuy) OriginColors.Success else OriginColors.Warning)
            }
            OriginDivider()
            Text("商务 ${train.businessSeat} · 一等 ${train.firstSeat} · 二等 ${train.secondSeat}", color = OriginColors.TextPrimary)
            Text("软卧 ${train.softSleeper} · 硬卧 ${train.hardSleeper} · 硬座 ${train.hardSeat} · 无座 ${train.noSeat}", color = OriginColors.TextSecondary)
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
