@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.railhelper.ticket

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRailway
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private enum class LoginMode {
    Password,
    Qr
}

@Composable
fun WebLoginScreen(
    user: User?,
    onLoggedIn: () -> Unit,
    onLogout: () -> Unit,
    onMessage: (String) -> Unit
) {
    NativeLoginScreen(
        user = user,
        onLoggedIn = onLoggedIn,
        onLogout = onLogout,
        onMessage = onMessage
    )
}

@Composable
private fun NativeLoginScreen(
    user: User?,
    onLoggedIn: () -> Unit,
    onLogout: () -> Unit,
    onMessage: (String) -> Unit
) {
    if (user != null) {
        LoggedInPanel(user = user, onLogout = {
            clearWebLoginCookies()
            onLogout()
        })
        return
    }

    var mode by rememberSaveable { mutableStateOf(LoginMode.Password) }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = if (mode == LoginMode.Password) 0 else 1) {
            Tab(
                selected = mode == LoginMode.Password,
                onClick = { mode = LoginMode.Password },
                text = { Text("账号登录") }
            )
            Tab(
                selected = mode == LoginMode.Qr,
                onClick = { mode = LoginMode.Qr },
                text = { Text("扫码登录") }
            )
        }

        when (mode) {
            LoginMode.Password -> PasswordLoginPanel(
                onLoggedIn = onLoggedIn,
                onMessage = onMessage,
                onSwitchToQr = { mode = LoginMode.Qr }
            )

            LoginMode.Qr -> QrFallbackLoginPanel(
                onLoggedIn = onLoggedIn,
                onMessage = onMessage,
                onBackToPassword = { mode = LoginMode.Password }
            )
        }
    }
}

@Composable
private fun LoggedInPanel(user: User, onLogout: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("已登录", style = MaterialTheme.typography.titleLarge)
                    Text(user.railwayUsername, fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = onLogout) { Text("退出登录") }
                }
            }
        }
    }
}

@Composable
private fun PasswordLoginPanel(
    onLoggedIn: () -> Unit,
    onMessage: (String) -> Unit,
    onSwitchToQr: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialStore = remember(context) { SecureCredentialStore(context) }
    val initialCredentials = remember { credentialStore.load() }

    var username by rememberSaveable { mutableStateOf(initialCredentials.username) }
    var password by rememberSaveable { mutableStateOf(initialCredentials.password) }
    var rememberUsername by rememberSaveable {
        mutableStateOf(initialCredentials.rememberUsername || initialCredentials.username.isNotBlank())
    }
    var rememberPassword by rememberSaveable {
        mutableStateOf(initialCredentials.rememberPassword && initialCredentials.password.isNotBlank())
    }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("请输入 12306 账号和密码") }
    var challengeId by rememberSaveable { mutableStateOf("") }
    var verificationMode by rememberSaveable { mutableStateOf("") }
    var slideToken by rememberSaveable { mutableStateOf("") }
    var availableVerifications by rememberSaveable { mutableStateOf(listOf<String>()) }
    var smsCastNum by rememberSaveable { mutableStateOf("") }
    var smsCode by rememberSaveable { mutableStateOf("") }
    var smsSent by rememberSaveable { mutableStateOf(false) }

    fun saveCredentials() {
        credentialStore.save(username, password, rememberUsername, rememberPassword)
    }

    fun resetChallenge() {
        challengeId = ""
        verificationMode = ""
        slideToken = ""
        availableVerifications = emptyList()
        smsCastNum = ""
        smsCode = ""
        smsSent = false
    }

    fun handleLoginResponse(res: JSONObject) {
        val data = res.dataObject()
        if (!res.success()) {
            status = res.message().ifBlank { data?.optString("message") ?: "登录失败" }
            onMessage(status)
            return
        }

        when (data?.optString("status")) {
            "success" -> {
                saveCredentials()
                status = data.optString("message", "登录成功")
                onMessage("登录成功")
                resetChallenge()
                onLoggedIn()
            }

            "needs_verification" -> {
                challengeId = data.optString("challenge_id")
                slideToken = data.optString("slide_token")
                availableVerifications = data.optJSONArray("available_verifications").toStringList()
                verificationMode = when {
                    availableVerifications.contains("slide") && slideToken.isNotBlank() -> "slide"
                    availableVerifications.contains("sms") -> "sms"
                    else -> data.optString("verification_type")
                }
                status = data.optString("message", "请完成 12306 登录验证")
            }

            "manual_required" -> {
                resetChallenge()
                status = data.optString("message", "12306 要求额外人工验证，请切换扫码登录")
                onMessage(status)
            }

            else -> {
                status = data?.optString("message").orEmpty().ifBlank { res.message().ifBlank { "登录失败" } }
                onMessage(status)
            }
        }
    }

    fun startPasswordLogin() {
        val normalizedUsername = username.trim()
        if (normalizedUsername.isBlank() || password.isBlank()) {
            status = "请输入 12306 账号和密码"
            return
        }
        scope.launch {
            resetChallenge()
            loading = true
            status = "正在提交登录..."
            val res = PythonBridge.call("start_password_login", normalizedUsername, password)
            loading = false
            handleLoginResponse(res)
        }
    }

    fun completeVerification(payload: JSONObject) {
        if (challengeId.isBlank() || loading) return
        scope.launch {
            loading = true
            status = "正在提交验证结果..."
            val res = PythonBridge.call("complete_password_login", challengeId, payload.toString())
            loading = false
            handleLoginResponse(res)
        }
    }

    fun sendSmsCode() {
        if (challengeId.isBlank()) return
        if (smsCastNum.trim().length != 4) {
            status = "请输入证件号后 4 位"
            return
        }
        scope.launch {
            loading = true
            status = "正在发送短信验证码..."
            val res = PythonBridge.call("send_login_sms", challengeId, smsCastNum.trim())
            loading = false
            if (res.success()) {
                smsSent = true
                status = res.message().ifBlank { "短信验证码已发送" }
            } else {
                status = res.message().ifBlank { "短信验证码发送失败" }
                onMessage(status)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("12306 账号登录", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("用户名 / 邮箱 / 手机号") },
            singleLine = true,
            enabled = !loading
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("密码") },
            singleLine = true,
            enabled = !loading,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                    )
                }
            }
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = rememberUsername,
                    onCheckedChange = { checked ->
                        rememberUsername = checked
                        if (!checked) rememberPassword = false
                    },
                    enabled = !loading
                )
                Text("记住账号")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = rememberPassword,
                    onCheckedChange = { checked ->
                        rememberPassword = checked
                        if (checked) rememberUsername = true
                    },
                    enabled = !loading
                )
                Text("记住密码")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = ::startPasswordLogin, enabled = !loading, modifier = Modifier.weight(1f)) {
                if (loading && challengeId.isBlank()) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text("登录")
            }
            OutlinedButton(onClick = onSwitchToQr, enabled = !loading) {
                Text("扫码")
            }
        }

        Text(status, style = MaterialTheme.typography.bodyMedium)
        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (challengeId.isNotBlank()) {
            VerificationPanel(
                mode = verificationMode,
                availableVerifications = availableVerifications,
                slideToken = slideToken,
                smsCastNum = smsCastNum,
                smsCode = smsCode,
                smsSent = smsSent,
                loading = loading,
                onModeChange = { verificationMode = it },
                onSmsCastNumChange = { smsCastNum = it.take(4) },
                onSmsCodeChange = { smsCode = it },
                onSendSms = ::sendSmsCode,
                onSubmitSms = {
                    if (smsCode.isBlank()) {
                        status = "请输入短信验证码"
                    } else {
                        completeVerification(JSONObject().put("type", "sms").put("randCode", smsCode.trim()))
                    }
                },
                onSlideVerified = { payload -> completeVerification(payload.put("type", "slide")) },
                onMessage = {
                    status = it
                    onMessage(it)
                }
            )
        }

        TextButton(
            onClick = {
                credentialStore.clear()
                username = ""
                password = ""
                rememberUsername = false
                rememberPassword = false
                resetChallenge()
                status = "已清除保存的账号密码"
            },
            enabled = !loading
        ) {
            Text("清除保存的账号密码")
        }
    }
}

@Composable
private fun VerificationPanel(
    mode: String,
    availableVerifications: List<String>,
    slideToken: String,
    smsCastNum: String,
    smsCode: String,
    smsSent: Boolean,
    loading: Boolean,
    onModeChange: (String) -> Unit,
    onSmsCastNumChange: (String) -> Unit,
    onSmsCodeChange: (String) -> Unit,
    onSendSms: () -> Unit,
    onSubmitSms: () -> Unit,
    onSlideVerified: (JSONObject) -> Unit,
    onMessage: (String) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("登录验证", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            if (availableVerifications.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (availableVerifications.contains("slide")) {
                        OutlinedButton(onClick = { onModeChange("slide") }, enabled = !loading) {
                            Text("滑块验证")
                        }
                    }
                    if (availableVerifications.contains("sms")) {
                        OutlinedButton(onClick = { onModeChange("sms") }, enabled = !loading) {
                            Text("短信验证")
                        }
                    }
                }
            }

            when (mode) {
                "slide" -> {
                    if (slideToken.isBlank()) {
                        Text("12306 未返回滑块验证 token，请切换短信或扫码登录")
                    } else {
                        SlideVerificationWebView(
                            slideToken = slideToken,
                            onVerified = onSlideVerified,
                            onMessage = onMessage
                        )
                    }
                }

                "sms" -> {
                    OutlinedTextField(
                        value = smsCastNum,
                        onValueChange = onSmsCastNumChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("证件号后 4 位") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        enabled = !loading
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onSendSms, enabled = !loading, modifier = Modifier.weight(1f)) {
                            Text(if (smsSent) "重新发送" else "获取验证码")
                        }
                    }
                    OutlinedTextField(
                        value = smsCode,
                        onValueChange = onSmsCodeChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("短信验证码") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = !loading
                    )
                    Button(onClick = onSubmitSms, enabled = !loading && smsSent, modifier = Modifier.fillMaxWidth()) {
                        Text("提交验证码")
                    }
                }

                else -> {
                    Text("请选择验证方式")
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SlideVerificationWebView(
    slideToken: String,
    onVerified: (JSONObject) -> Unit,
    onMessage: (String) -> Unit
) {
    val html = remember(slideToken) { buildSlideHtml(slideToken) }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                configureForSlideVerification()
                addJavascriptInterface(SlideBridge(onVerified, onMessage), "RailHelperSlide")
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        if (request?.isForMainFrame == true) {
                            onMessage("滑块验证加载失败，请切换扫码登录")
                        }
                    }
                }
                tag = slideToken
                loadDataWithBaseURL("https://kyfw.12306.cn", html, "text/html", "UTF-8", null)
            }
        },
        update = { view ->
            if (view.tag != slideToken) {
                view.tag = slideToken
                view.loadDataWithBaseURL("https://kyfw.12306.cn", html, "text/html", "UTF-8", null)
            }
        }
    )
}

@Composable
private fun QrFallbackLoginPanel(
    onLoggedIn: () -> Unit,
    onMessage: (String) -> Unit,
    onBackToPassword: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("等待获取二维码") }
    var challengeId by remember { mutableStateOf("") }
    var imageBase64 by remember { mutableStateOf("") }

    fun requestQr() {
        scope.launch {
            loading = true
            status = "正在获取二维码..."
            val res = PythonBridge.call("create_login_qrcode")
            loading = false
            if (!res.success()) {
                status = res.message()
                onMessage(res.message())
                return@launch
            }
            val data = res.dataObject()
            challengeId = data?.optString("challenge_id").orEmpty()
            imageBase64 = data?.optString("image_base64").orEmpty()
            status = "请使用 12306 App 扫码确认"
        }
    }

    LaunchedEffect(challengeId) {
        while (challengeId.isNotBlank()) {
            delay(2_000)
            val res = PythonBridge.call("check_login_qrcode_status", challengeId)
            val data = res.dataObject()
            status = data?.optString("message").orEmpty().ifBlank { res.message() }
            if (data?.optBoolean("is_success") == true) {
                challengeId = ""
                onLoggedIn()
                break
            }
            val code = data?.optInt("status", -1) ?: -1
            if (code == 3 || code == 5) {
                challengeId = ""
                break
            }
        }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("12306 扫码登录", style = MaterialTheme.typography.titleLarge)
                    if (imageBase64.isNotBlank()) {
                        QrImage(imageBase64)
                    } else {
                        Icon(Icons.Default.DirectionsRailway, contentDescription = null, modifier = Modifier.size(96.dp))
                    }
                    Text(status)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = ::requestQr, enabled = !loading) {
                            Text(if (imageBase64.isBlank()) "获取二维码" else "刷新二维码")
                        }
                        OutlinedButton(
                            onClick = { shareQr(context, imageBase64, onMessage) },
                            enabled = imageBase64.isNotBlank()
                        ) { Text("分享二维码") }
                    }
                    TextButton(onClick = onBackToPassword) { Text("返回账号登录") }
                }
            }
        }
    }
}

private fun WebView.configureForSlideVerification() {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.loadsImagesAutomatically = true
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.cacheMode = WebSettings.LOAD_DEFAULT
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.setSupportMultipleWindows(false)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    }
}

private fun buildSlideHtml(slideToken: String): String {
    val token = JSONObject.quote(slideToken)
    return """
        <!doctype html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
          <style>
            html, body { margin: 0; padding: 0; background: #fff; font-family: sans-serif; }
            #wrap { padding: 16px 4px; }
            #nc { width: 100%; }
            .tips { color: #5f6368; font-size: 14px; line-height: 20px; margin: 0 0 12px; }
          </style>
          <script src="https://g.alicdn.com/sd/ncpc/nc.js?t=2015052012"></script>
        </head>
        <body>
          <div id="wrap">
            <p class="tips">请完成 12306 滑块验证</p>
            <div id="nc"></div>
          </div>
          <script>
            (function() {
              var token = $token;
              var tries = 0;
              function error(message) {
                if (window.RailHelperSlide) {
                  window.RailHelperSlide.onError(message || '滑块验证加载失败');
                }
              }
              function init() {
                if (typeof noCaptcha === 'undefined') {
                  tries += 1;
                  if (tries > 60) {
                    error('滑块验证脚本加载超时');
                    return;
                  }
                  setTimeout(init, 250);
                  return;
                }
                try {
                  var nc = new noCaptcha({
                    renderTo: '#nc',
                    appkey: 'FFFF0N000000000085DE',
                    scene: 'nc_login',
                    token: token,
                    trans: { key1: 'code0' },
                    elementID: ['usernameID'],
                    is_Opt: 0,
                    language: 'cn',
                    isEnabled: true,
                    timeout: 3000,
                    times: 5,
                    apimap: {},
                    callback: function(data) {
                      window.RailHelperSlide.onSuccess(JSON.stringify({
                        type: 'slide',
                        sessionId: data.csessionid || '',
                        sig: data.sig || '',
                        if_check_slide_passcode_token: token,
                        token: token,
                        scene: 'nc_login'
                      }));
                    }
                  });
                  if (nc && nc.upLang) {
                    nc.upLang('zh', {
                      _startTEXT: '请按住滑块，拖动到最右边',
                      _yesTEXT: '验证通过',
                      _error300: '出错了，请刷新后重试',
                      _errorNetwork: '网络不给力，请刷新后重试'
                    });
                  }
                } catch (e) {
                  error(e && e.message ? e.message : String(e));
                }
              }
              init();
            })();
          </script>
        </body>
        </html>
    """.trimIndent()
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index -> optString(index).takeIf { it.isNotBlank() } }
}

fun clearWebLoginCookies() {
    CookieManager.getInstance().removeAllCookies(null)
    CookieManager.getInstance().flush()
    WebStorage.getInstance().deleteAllData()
}

private class SlideBridge(
    private val onVerified: (JSONObject) -> Unit,
    private val onMessage: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onSuccess(payload: String?) {
        mainHandler.post {
            runCatching { onVerified(JSONObject(payload ?: "{}")) }
                .onFailure { onMessage("滑块验证结果解析失败") }
        }
    }

    @JavascriptInterface
    fun onError(message: String?) {
        mainHandler.post {
            onMessage(message ?: "滑块验证失败")
        }
    }
}
