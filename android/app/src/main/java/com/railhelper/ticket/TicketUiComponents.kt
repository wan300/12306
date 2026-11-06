package com.railhelper.ticket

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import org.json.JSONArray

class TicketQueryMemory(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("ticket_query_ui", Context.MODE_PRIVATE)
    fun value(key: String, fallback: String = "") = preferences.getString(key, fallback) ?: fallback
    fun save(from: String, to: String, date: String, student: Boolean) {
        preferences.edit().putString("from", from).putString("to", to).putString("date", date)
            .putString("student", student.toString()).apply()
    }
    fun saveDate(date: String) { preferences.edit().putString("date", date).apply() }
    fun history(): List<Pair<String, String>> = runCatching {
        val array = JSONArray(value("routes", "[]"))
        (0 until array.length()).map { array.getJSONArray(it).let { route -> route.getString(0) to route.getString(1) } }
    }.getOrDefault(emptyList())
    fun addRoute(from: String, to: String) {
        val routes = (listOf(from to to) + history()).distinct().take(8)
        val json = JSONArray().apply { routes.forEach { put(JSONArray().put(it.first).put(it.second)) } }
        preferences.edit().putString("routes", json.toString()).apply()
    }
    fun clearRoutes() { preferences.edit().remove("routes").apply() }
    fun recentStations(): List<String> = runCatching {
        val array = JSONArray(value("stations", "[]"))
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList())
    fun addStation(station: String) {
        preferences.edit().putString("stations", JSONArray((listOf(station) + recentStations()).distinct().take(9)).toString()).apply()
    }
}

@Composable
fun TicketTitleBar(title: String, onBack: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxWidth().height(48.dp).background(OriginColors.Blue), contentAlignment = Alignment.Center) {
        Text(title, color = Color.White, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 48.dp))
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).semantics { contentDescription = "返回" }) {
                Canvas(Modifier.size(22.dp)) {
                    val stroke = 2.dp.toPx()
                    drawLine(Color.White, Offset(size.width * .65f, size.height * .12f), Offset(size.width * .28f, size.height * .5f), stroke, StrokeCap.Round)
                    drawLine(Color.White, Offset(size.width * .28f, size.height * .5f), Offset(size.width * .65f, size.height * .88f), stroke, StrokeCap.Round)
                }
            }
        }
        Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
}

@Composable
fun TicketFullscreen(onBack: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                window.setDimAmount(0f)
                window.statusBarColor = android.graphics.Color.rgb(59, 153, 252)
                window.navigationBarColor = android.graphics.Color.WHITE
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = true
            }
        }
        Surface(Modifier.fillMaxSize(), color = Color.White, contentColor = OriginColors.TextPrimary) { content() }
    }
}

@Composable
fun TicketCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, small: Boolean = false) {
    Row(modifier.heightIn(min = if (small) 34.dp else 40.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(if (checked) R.drawable.origin_ticket_student_checked else R.drawable.origin_ticket_student_unchecked),
            contentDescription = null, modifier = Modifier.size(if (small) 13.dp else 14.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = if (small) 12.sp else 13.sp, lineHeight = 18.sp, color = OriginColors.TextSecondary, maxLines = 1)
    }
}

@Composable
fun TicketBottomNavigation(screen: String, loggedIn: Boolean, onScreen: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color.White).navigationBarsPadding().height(56.dp)) {
        listOf("home" to "首页", "tasks" to "任务", "login" to if (loggedIn) "账号" else "登录").forEach { (key, label) ->
            val selected = screen == key
            Column(Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Tab) { onScreen(key) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                when (key) {
                    "home" -> Image(painterResource(if (selected) R.drawable.origin_icon_home_pressed else R.drawable.origin_icon_home_normal), null, Modifier.size(24.dp))
                    "tasks" -> Image(painterResource(if (selected) R.drawable.origin_icon_order_pressed else R.drawable.origin_icon_order_normal), null, Modifier.size(24.dp))
                    else -> Image(painterResource(if (selected) R.drawable.origin_icon_mine_pressed else R.drawable.origin_icon_mine_normal), null, Modifier.size(24.dp))
                }
                Spacer(Modifier.height(3.dp))
                Text(label, fontSize = 12.sp, color = if (selected) OriginColors.Blue else OriginColors.TextPrimary)
            }
        }
    }
}

@Composable
fun TicketEmptyState(title: String, detail: String, onRetry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 17.sp, color = OriginColors.TextPrimary, fontWeight = FontWeight.Medium)
        Text(detail, fontSize = 13.sp, color = OriginColors.TextMuted, modifier = Modifier.padding(top = 10.dp))
        if (onRetry != null) TextButton(onClick = onRetry) { Text("重试") }
    }
}

@Composable
fun TicketActionButton(label: String, enabled: Boolean = true, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(4.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 1.dp),
        modifier = modifier.height(44.dp), contentPadding = PaddingValues(horizontal = 12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = OriginColors.Blue, disabledContainerColor = OriginColors.Blue.copy(alpha = .4f))) {
        Text(label, fontSize = 18.sp, maxLines = 1)
    }
}
