package com.railhelper.ticket

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TasksScreen(onCreate: () -> Unit, onEdit: (TicketTask) -> Unit, onDetail: (Int) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf<List<TicketTask>>(emptyList()) }
    var filter by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<TicketTask?>(null) }
    suspend fun load() {
        loading = true
        val result = PythonBridge.call("get_tasks", "")
        if (result.success()) { tasks = parseTasks(result.dataObject()?.optJSONArray("tasks")); error = "" }
        else error = result.message()
        loading = false
    }
    fun action(method: String, task: TicketTask) {
        if (busy) return
        busy = true
        scope.launch {
            if (method == "start_task") requestBatteryOptimizationExemption(context)
            val result = PythonBridge.call(method, task.id)
            if (!result.success()) onMessage(result.message())
            else if (method == "start_task") TaskRunnerService.start(context)
            load(); busy = false
        }
    }
    LaunchedEffect(Unit) { load() }
    Surface(Modifier.fillMaxSize(), color = OriginColors.Page, contentColor = OriginColors.TextPrimary) {
        Column {
            TicketTitleBar("抢票任务", trailing = { TextButton(onClick = { scope.launch { load() } }, enabled = !loading && !busy) { Text("刷新", color = Color.White) } })
            Row(Modifier.fillMaxWidth().background(Color.White).horizontalScroll(rememberScrollState())) {
                listOf("" to "全部", "pending" to "待运行", "running" to "运行中", "paused" to "已暂停", "success" to "成功", "failed" to "失败", "cancelled" to "已取消").forEach { (key, label) ->
                    Column(Modifier.clickable { filter = key }.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, Modifier.padding(vertical = 14.dp), fontSize = 14.sp, color = if (key == filter) OriginColors.Blue else OriginColors.TextSecondary)
                        Box(Modifier.width(28.dp).height(2.dp).background(if (key == filter) OriginColors.Blue else Color.Transparent))
                    }
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            val visible = tasks.filter { filter.isBlank() || it.status == filter }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (error.isNotBlank()) item { TaskPanel { Text(error, color = OriginColors.Danger); TextButton(onClick = { scope.launch { load() } }) { Text("重新加载") } } }
                if (!loading && error.isBlank() && visible.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (filter.isBlank()) "还没有抢票任务" else "暂无${statusText(filter)}任务", fontSize = 17.sp)
                        Text("设置行程和乘车人，保存后即可启动", Modifier.padding(top = 10.dp), fontSize = 13.sp, color = OriginColors.TextMuted)
                    }
                }
                items(visible, key = { it.id }) { task ->
                    TaskCard(task, busy, { onDetail(task.id) }, { onEdit(task) }, { action("start_task", task) }, { action("stop_task", task) }, { action("cancel_task", task) }, { delete = task })
                }
            }
            Box(Modifier.background(Color.White).padding(12.dp)) { TaskPrimary("创建抢票任务", onClick = onCreate) }
        }
    }
    delete?.let { target -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("删除任务？") }, text = { Text("将删除“${target.name}”及其运行记录。") },
        confirmButton = { TextButton(onClick = { delete = null; action("delete_task", target) }) { Text("删除", color = OriginColors.Danger) } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text("保留") } }) }
}

@Composable
private fun TaskCard(task: TicketTask, busy: Boolean, onDetail: () -> Unit, onEdit: () -> Unit, onStart: () -> Unit, onStop: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    TaskPanel {
        Column(Modifier.clickable(onClick = onDetail)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(task.name, Modifier.weight(1f), fontSize = 13.sp, color = OriginColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp)); Text(statusText(task.status), fontSize = 13.sp, color = originStatusColor(task.status))
            }
            TaskRoute(task.fromStation, task.toStation, task.trainDate)
            Text("${formatSeatTypes(task.seatTypes)} · 已查询 ${task.retryCount} 次", fontSize = 12.sp, color = OriginColors.TextMuted)
        }
        HorizontalDivider(Modifier.padding(top = 12.dp), color = OriginColors.Divider)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { menu = true }, enabled = !busy) { Text("更多", color = OriginColors.TextSecondary) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("查看详情") }, onClick = { menu = false; onDetail() })
                    if (task.status !in listOf("success", "cancelled")) DropdownMenuItem(text = { Text("取消任务") }, onClick = { menu = false; onCancel() })
                    if (task.status != "running") DropdownMenuItem(text = { Text("删除任务", color = OriginColors.Danger) }, onClick = { menu = false; onDelete() })
                }
            }
            Spacer(Modifier.weight(1f))
            if (task.status !in listOf("running", "success")) TextButton(onClick = onEdit, enabled = !busy) { Text("编辑") }
            if (task.status == "success") TextButton(onClick = onDetail) { Text("查看结果") }
            else OutlinedButton(onClick = if (task.status == "running") onStop else onStart, enabled = !busy) { Text(if (task.status == "running") "暂停任务" else "启动任务") }
        }
    }
}

@Composable
fun TaskDetailScreen(taskId: Int, onBack: () -> Unit, onEdit: (TicketTask) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var task by remember(taskId) { mutableStateOf<TicketTask?>(null) }
    var logs by remember(taskId) { mutableStateOf<List<TaskLog>>(emptyList()) }
    var alertsOnly by rememberSaveable(taskId) { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    suspend fun load() {
        val result = PythonBridge.call("get_task", taskId)
        if (result.success()) { task = result.dataObject()?.let(::parseTask); error = "" } else error = result.message()
        val logResult = PythonBridge.call("get_task_logs", taskId, 200)
        if (logResult.success()) logs = parseLogs(logResult.dataObject()?.optJSONArray("logs"))
    }
    fun toggle(current: TicketTask) {
        if (busy) return
        busy = true
        scope.launch {
            val start = current.status != "running"
            if (start) requestBatteryOptimizationExemption(context)
            val result = PythonBridge.call(if (start) "start_task" else "stop_task", taskId)
            if (!result.success()) onMessage(result.message()) else if (start) TaskRunnerService.start(context)
            load(); busy = false
        }
    }
    LaunchedEffect(taskId) { while (true) { load(); delay(if (task?.status == "running") 3_000 else 8_000) } }
    Surface(Modifier.fillMaxSize(), color = OriginColors.Page, contentColor = OriginColors.TextPrimary) {
        Column {
            TicketTitleBar("任务详情", onBack)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (error.isNotBlank()) item { TaskPanel { Text(error, color = OriginColors.Danger); TextButton(onClick = { scope.launch { load() } }) { Text("重试") } } }
                val current = task
                if (current != null) {
                    item { TaskPanel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(statusText(current.status), Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.Medium, color = originStatusColor(current.status))
                            Text("已查询 ${current.retryCount} 次", fontSize = 12.sp, color = OriginColors.TextMuted)
                        }
                        Text(current.name, Modifier.padding(top = 8.dp), fontSize = 13.sp, color = OriginColors.TextSecondary)
                        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = OriginColors.Divider)
                        TaskRoute(current.fromStation, current.toStation, current.trainDate)
                        TaskValueRow("席别优先级", taskSeatLabel(current.seatTypes.split(',')))
                        TaskValueRow("车次类型", current.trainTypes?.takeIf { it.isNotBlank() }?.split(',')?.joinToString(" / ") { code -> taskTrainTypes.firstOrNull { it.first == code }?.second ?: code } ?: "不限")
                        TaskValueRow("指定车次", current.trainCodes?.takeIf { it.isNotBlank() } ?: "不限车次")
                        TaskValueRow("出发时段", current.startTimeRange?.takeIf { it.isNotBlank() } ?: "全天", divider = false)
                    } }
                    item { TaskPanel {
                        TaskSection("任务设置")
                        TaskValueRow("乘车人", "${current.toDraft().passengers.size} 人")
                        TaskValueRow("查询间隔", "${current.queryInterval} 秒")
                        TaskValueRow("最多重试", if (current.maxRetryCount < 0) "持续重试" else "${current.maxRetryCount} 次")
                        TaskValueRow("有票时自动提交", if (current.autoSubmit) "已开启 · 需手动付款" else "已关闭", divider = false)
                        if (!current.resultMessage.isNullOrBlank()) Text(current.resultMessage, Modifier.padding(top = 10.dp), fontSize = 13.sp, lineHeight = 20.sp)
                        if (!current.orderId.isNullOrBlank()) TaskValueRow("订单号", current.orderId, divider = false)
                    } }
                    item { TaskPanel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("运行记录", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            TextButton(onClick = { alertsOnly = !alertsOnly }) { Text(if (alertsOnly) "显示全部" else "只看提醒", fontSize = 13.sp) }
                        }
                        Text("最新记录在前 · 最近 ${logs.size} 条", fontSize = 12.sp, color = OriginColors.TextMuted)
                        if (logs.isEmpty()) Text("启动任务后将在此显示查询进度", Modifier.padding(top = 10.dp), fontSize = 13.sp, color = OriginColors.TextMuted)
                        else if (alertsOnly && logs.none { it.level in listOf("warning", "error") }) Text("暂无需要关注的提醒", Modifier.padding(top = 10.dp), fontSize = 13.sp, color = OriginColors.TextMuted)
                    } }
                    items(logs.asReversed().filter { !alertsOnly || it.level in listOf("warning", "error") }, key = { it.id }) { log -> TaskLogRow(log) }
                } else if (error.isBlank()) item { LoadingBox("正在加载任务…") }
            }
            task?.let { current -> Row(Modifier.fillMaxWidth().background(Color.White).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (current.status !in listOf("running", "success")) OutlinedButton(onClick = { onEdit(current) }, enabled = !busy, modifier = Modifier.height(46.dp)) { Text("编辑任务") }
                TaskPrimary(if (busy) "处理中…" else if (current.status == "success") "查看订单" else if (current.status == "running") "暂停任务" else "启动任务", !busy,
                    { if (current.status == "success") openPaymentPage(context) else toggle(current) }, Modifier.weight(1f))
            } }
        }
    }
}

@Composable
private fun TaskLogRow(log: TaskLog) {
    val (label, color) = when (log.level) {
        "error" -> "异常" to OriginColors.Danger
        "warning" -> "提醒" to OriginColors.Warning
        "success" -> "成功" to OriginColors.Success
        else -> "进度" to OriginColors.Blue
    }
    TaskPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(color = color.copy(alpha = .09f), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)) {
                Text(label, Modifier.padding(horizontal = 7.dp, vertical = 3.dp), color = color, fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(log.createdAt.replace('T', ' ').take(19).let { if (it.matches(Regex("\\d{4}-\\d{2}-\\d{2} .*"))) it.drop(5) else it }, fontSize = 12.sp, color = OriginColors.TextMuted)
        }
        Text(log.message, Modifier.padding(top = 9.dp), fontSize = 14.sp, lineHeight = 23.sp, color = OriginColors.TextPrimary)
    }
}
