@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.railhelper.ticket

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import androidx.compose.foundation.background
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

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
    var reauthenticate by remember { mutableStateOf(false) }
    if (user != null && !reauthenticate) {
        LoggedInPanel(user = user, onLogout = {
            clearWebLoginCookies()
            onLogout()
        }, onReauthenticate = { reauthenticate = true })
        return
    }
    androidx.activity.compose.BackHandler(enabled = reauthenticate) { reauthenticate = false }
    PasswordLoginPanel(onLoggedIn = {
        reauthenticate = false
        onLoggedIn()
    }, onMessage = onMessage)
}

@Composable
private fun LoggedInPanel(user: User, onLogout: () -> Unit, onReauthenticate: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().originPage(),
        contentPadding = PaddingValues(OriginPagePadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OriginCard(contentPadding = 16.dp) {
                OriginSectionHeader("已登录")
                Text(user.railwayUsername, fontWeight = FontWeight.SemiBold, color = OriginColors.TextPrimary)
                OriginPrimaryButton(onClick = onReauthenticate) { Text("重新登录") }
                OriginSecondaryButton(onClick = onLogout) { Text("退出登录") }
            }
        }
    }
}

@Composable
private fun PasswordLoginPanel(
    onLoggedIn: () -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialStore = remember(context) { SecureCredentialStore(context) }
    val initialCredentials = remember { credentialStore.load() }

    var username by rememberSaveable { mutableStateOf(initialCredentials.username) }
    var password by remember { mutableStateOf(initialCredentials.password) }
    var rememberUsername by rememberSaveable {
        mutableStateOf(initialCredentials.rememberUsername || initialCredentials.username.isNotBlank())
    }
    var rememberPassword by rememberSaveable {
        mutableStateOf(initialCredentials.rememberPassword && initialCredentials.password.isNotBlank())
    }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var challengeId by remember { mutableStateOf("") }
    var verificationMode by remember { mutableStateOf("") }
    var slideToken by remember { mutableStateOf("") }
    var availableVerifications by remember { mutableStateOf(listOf<String>()) }
    var smsCastNum by remember { mutableStateOf("") }
    var smsCode by remember { mutableStateOf("") }
    var smsSent by remember { mutableStateOf(false) }
    var smsRetryAt by remember { mutableStateOf(0L) }
    var smsCountdown by remember { mutableStateOf(0) }
    var optionsExpanded by rememberSaveable { mutableStateOf(false) }
    var showRecovery by remember { mutableStateOf(false) }
    LaunchedEffect(smsRetryAt) {
        do {
            smsCountdown = ((smsRetryAt - SystemClock.elapsedRealtime() + 999) / 1000).toInt().coerceAtLeast(0)
            if (smsCountdown > 0) delay(250)
        } while (smsCountdown > 0)
    }

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
        smsRetryAt = 0L
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
                    availableVerifications.contains("sms") -> "sms"
                    availableVerifications.contains("slide") && slideToken.isNotBlank() -> "slide"
                    else -> data.optString("verification_type")
                }
                status = data.optString("message", "请完成 12306 登录验证")
            }

            "manual_required" -> {
                resetChallenge()
                status = data.optString("message", "请在铁路12306完成账号核验后重试")
                onMessage(status)
            }

            else -> {
                status = data?.optString("message").orEmpty().ifBlank { res.message().ifBlank { "登录失败" } }
                onMessage(status)
            }
        }
    }

    fun startPasswordLogin() {
        if (loading) return
        val normalizedUsername = username.trim()
        if (normalizedUsername.isBlank() || password.isBlank()) {
            status = "请输入绑定12306的手机号和密码"
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
            // The bridge discards a failed challenge; return to a fresh login attempt.
            if (!res.success()) resetChallenge()
            handleLoginResponse(res)
        }
    }

    fun sendSmsCode() {
        if (challengeId.isBlank() || loading || smsCountdown > 0) return
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
                smsCode = ""
                smsRetryAt = SystemClock.elapsedRealtime() + 60_000L
                status = res.message().ifBlank { "短信验证码已发送" }
            } else {
                status = res.message().ifBlank { "短信验证码发送失败" }
                onMessage(status)
            }
        }
    }

    if (showRecovery) {
        PasswordRecoveryScreen(onBack = { showRecovery = false })
        return
    }

    androidx.activity.compose.BackHandler(enabled = challengeId.isNotBlank()) {
        resetChallenge()
        status = ""
    }

    Column(
        modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White)
            .imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LoginHeading(verifying = challengeId.isNotBlank())
        if (challengeId.isBlank()) {
            LoginField(username, { username = it }, "手机号 / 用户名 / 邮箱", !loading)
            LoginField(password, { password = it }, "12306 密码", !loading,
                keyboardType = KeyboardType.Password,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (passwordVisible) "隐藏密码" else "显示密码")
                    }
                })
            Text("身份验证时优先使用短信验证码", fontSize = 12.sp, color = OriginColors.TextSecondary)
            Spacer(Modifier.height(4.dp))
            LoginAction("登录", enabled = !loading && username.isNotBlank() && password.isNotBlank(),
                loading = loading, onClick = ::startPasswordLogin)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { showRecovery = true }, enabled = !loading) {
                    Text("忘记密码？", fontSize = 13.sp)
                }
                TextButton(onClick = { optionsExpanded = !optionsExpanded }, enabled = !loading) {
                    Text(if (optionsExpanded) "收起登录设置" else "登录设置", fontSize = 13.sp)
                }
            }
            if (optionsExpanded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rememberUsername, onCheckedChange = {
                        rememberUsername = it
                        if (!it) rememberPassword = false
                    }, enabled = !loading)
                    Text("记住账号", fontSize = 14.sp, color = OriginColors.TextPrimary)
                    Checkbox(checked = rememberPassword, onCheckedChange = {
                        rememberPassword = it
                        if (it) rememberUsername = true
                    }, enabled = !loading)
                    Text("记住密码", fontSize = 14.sp, color = OriginColors.TextPrimary)
                }
                TextButton(onClick = {
                    credentialStore.clear()
                    username = ""
                    password = ""
                    rememberUsername = false
                    rememberPassword = false
                    resetChallenge()
                    status = "已清除保存的账号密码"
                }, enabled = !loading) { Text("清除保存的账号密码", fontSize = 13.sp) }
            }
        } else {
            VerificationPanel(
                mode = verificationMode, availableVerifications = availableVerifications,
                slideToken = slideToken, smsCastNum = smsCastNum, smsCode = smsCode,
                smsSent = smsSent, smsCountdown = smsCountdown, loading = loading,
                onModeChange = { verificationMode = it; status = "" },
                onSmsCastNumChange = { smsCastNum = it.filterNot(Char::isWhitespace).take(4) },
                onSmsCodeChange = { smsCode = it.filter { char -> char in '0'..'9' }.take(6) },
                onSendSms = ::sendSmsCode,
                onSubmitSms = {
                    if (smsCode.isBlank()) status = "请输入短信验证码"
                    else completeVerification(JSONObject().put("type", "sms").put("randCode", smsCode.trim()))
                },
                onSlideVerified = { payload -> completeVerification(payload.put("type", "slide")) },
                onMessage = { status = it; onMessage(it) }
            )
            TextButton(onClick = { resetChallenge(); status = "" }, enabled = !loading) { Text("返回账号登录") }
        }
        if (status.isNotBlank()) {
            Text(status, fontSize = 13.sp, lineHeight = 20.sp, color = OriginColors.TextSecondary)
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = OriginColors.Divider)
        Text("12306 网页账号登录仍需密码；短信验证码用于身份验证。", fontSize = 12.sp,
            lineHeight = 19.sp, color = OriginColors.TextMuted)
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
    smsCountdown: Int,
    loading: Boolean,
    onModeChange: (String) -> Unit,
    onSmsCastNumChange: (String) -> Unit,
    onSmsCodeChange: (String) -> Unit,
    onSendSms: () -> Unit,
    onSubmitSms: () -> Unit,
    onSlideVerified: (JSONObject) -> Unit,
    onMessage: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (mode) {
            "sms" -> {
                Text("短信验证", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = OriginColors.TextPrimary)
                Text("请输入登录账号绑定的证件号后 4 位，验证码将发送至绑定手机。",
                    fontSize = 13.sp, lineHeight = 21.sp, color = OriginColors.TextSecondary)
                LoginField(smsCastNum, onSmsCastNumChange, "证件号后 4 位", !loading && smsCountdown == 0,
                    keyboardType = KeyboardType.Ascii)
                LoginField(smsCode, onSmsCodeChange, "短信验证码", !loading,
                    keyboardType = KeyboardType.Number,
                    trailingIcon = {
                        TextButton(onClick = onSendSms,
                            enabled = !loading && smsCountdown == 0 && smsCastNum.length == 4) {
                            Text(if (smsCountdown > 0) "${smsCountdown}s 后重发" else if (smsSent) "重新获取" else "获取验证码", fontSize = 13.sp)
                        }
                    })
                LoginAction("验证并登录", enabled = !loading && smsSent && smsCode.isNotBlank(),
                    loading = loading, onClick = onSubmitSms)
            }
            "slide" -> {
                Text("滑动验证", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = OriginColors.TextPrimary)
                if (slideToken.isBlank()) Text("验证暂不可用，请返回账号登录后重试", color = OriginColors.TextSecondary)
                else SlideVerificationWebView(slideToken, onSlideVerified, onMessage)
            }
            else -> Text("验证暂不可用，请返回账号登录后重试", color = OriginColors.TextSecondary)
        }
        if (availableVerifications.size > 1) {
            TextButton(onClick = { onModeChange(if (mode == "sms") "slide" else "sms") }, enabled = !loading) {
                Text(if (mode == "sms") "其他方式：滑动验证" else "使用短信验证码", fontSize = 13.sp)
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
                            onMessage("滑动验证暂不可用，请选择短信验证或稍后重试")
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
