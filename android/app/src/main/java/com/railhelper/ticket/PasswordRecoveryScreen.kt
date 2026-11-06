package com.railhelper.ticket

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

private const val RECOVERY_URL = "https://kyfw.12306.cn/otn/view/find_my_password.html"

internal fun isOfficialRecoveryUrl(url: String): Boolean = runCatching {
    val uri = java.net.URI(url)
    val host = uri.host?.lowercase().orEmpty()
    uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null &&
        (uri.port == -1 || uri.port == 443) && (host == "12306.cn" || host.endsWith(".12306.cn"))
}.getOrDefault(false)

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun PasswordRecoveryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var officialHost by remember { mutableStateOf("kyfw.12306.cn") }
    val webView = remember(context) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (isOfficialRecoveryUrl(request.url.toString())) return false
                    error = "此链接无法在密码找回页打开，请返回登录。"
                    return true
                }
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    loading = true
                    error = null
                    officialHost = Uri.parse(url).host.orEmpty()
                }
                override fun onPageFinished(view: WebView, url: String) {
                    loading = false
                    if (url.substringBefore('?') == RECOVERY_URL) {
                        // Presentation only: adapt the official form and select its phone tab.
                        // Never read fields, attach a native bridge, or submit recovery requests.
                        view.evaluateJavascript(RECOVERY_MOBILE_LAYOUT, null)
                    }
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, issue: WebResourceError) {
                    if (request.isForMainFrame) {
                        loading = false
                        error = "官方页面暂时无法加载，请检查网络后重试。"
                    }
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame && response.statusCode >= 400) {
                        loading = false
                        error = "官方页面暂时不可用，请稍后重试。"
                    }
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, issue: android.net.http.SslError) {
                    handler.cancel()
                    loading = false
                    error = "无法验证官方页面的安全连接，请稍后重试。"
                }
            }
            loadUrl(RECOVERY_URL)
        }
    }
    DisposableEffect(webView) {
        onDispose { webView.stopLoading(); webView.destroy() }
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Color.White).imePadding()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("返回登录") }
            TextButton(onClick = { error = null; webView.loadUrl(RECOVERY_URL) }) { Text("重新加载") }
        }
        Text("找回密码", fontSize = 22.sp, color = OriginColors.TextPrimary,
            modifier = Modifier.padding(horizontal = 20.dp))
        Text("12306 官方页面 · $officialHost", fontSize = 12.sp, color = OriginColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { message ->
            Text(message, color = OriginColors.Danger, fontSize = 13.sp, modifier = Modifier.padding(20.dp))
        }
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f))
    }
}

private val RECOVERY_MOBILE_LAYOUT = """
    (function() {
      var viewport = document.querySelector('meta[name="viewport"]');
      if (!viewport) { viewport = document.createElement('meta'); viewport.name = 'viewport'; document.head.appendChild(viewport); }
      viewport.content = 'width=device-width, initial-scale=1';
      var style = document.getElementById('railhelper-recovery-layout');
      if (!style) { style = document.createElement('style'); style.id = 'railhelper-recovery-layout'; document.head.appendChild(style); }
      style.textContent = `
        body{min-width:0!important;background:#fff}
        .header,.nav-box,.footer{display:none!important}
        .wrapper,.content,.find-pass{width:100%!important;min-width:0!important;margin:0!important;padding:12px!important;height:auto!important}
        .find-pass .hd{height:48px!important;border-radius:8px 8px 0 0;overflow:hidden}
        .find-pass .hd ul{display:flex;width:100%}
        .find-pass .hd li{width:33.333%!important;min-width:0;float:none!important;height:48px!important;line-height:48px!important;padding:0!important;font-size:14px!important}
        .find-pass .hd li span,.find-pass .hd li b{display:none!important}
        .find-pass .bd{height:auto!important;min-height:360px;padding:12px;border-color:#e6edf4;border-radius:0 0 8px 8px}
        .find-step{width:100%!important;margin:8px auto 16px!important}
        .find-step .step-pic{display:none}
        .find-step ul{display:flex;counter-reset:step}
        .find-step li{width:25%!important;font-size:11px;text-align:center;line-height:18px;color:#777}
        .find-step li:before{counter-increment:step;content:counter(step);display:block;margin:auto auto 5px;width:22px;border-radius:50%;background:#eef3f8;color:#777;line-height:22px}
        .find-step1 li:nth-child(1):before,.find-step2 li:nth-child(2):before,.find-step3 li:nth-child(3):before,.find-step4 li:nth-child(4):before{background:#3b99fc;color:#fff}
        .form-list{padding-top:8px!important}
        .form-item{margin:0 0 16px!important;padding:0!important;min-height:0!important}
        .form-label{float:none!important;width:auto!important;text-align:left!important;line-height:26px!important}
        .form-bd{margin-left:0!important;width:100%!important;display:flex;flex-wrap:wrap;gap:6px}
        .form-bd .input-box{width:auto!important;flex:1;min-width:120px}
        .form-bd>.sel{width:100%!important}
        .form-bd>.areaCodeDom{width:80px!important;flex:none;margin:0!important}
        .form-bd .input{width:100%!important;height:42px;font-size:16px;border-radius:6px}
        .form-bd .sel-hd,.form-bd .sel-inner{height:42px!important;line-height:40px!important}
        .form-tips{position:static!important;left:auto!important;top:auto!important;width:100%!important;line-height:20px!important;font-size:12px!important;margin-top:4px;clear:both}
        .get-qr-code{position:static!important;line-height:40px!important}
        #errorMsg_id{margin:8px 0!important;text-align:left}
        #save_btn_phone,#save_btn{width:100%!important;height:44px!important;line-height:34px!important;background:#3b99fc;border-color:#3b99fc}
      `;
      var tries = 0;
      function selectPhone() {
        var tab = document.getElementById('mobile_find_back_main');
        var page = document.getElementById('mobile_find_back_page');
        if (!tab || !page || ++tries > 30) return;
        if (getComputedStyle(page).display !== 'none') return;
        tab.click();
        setTimeout(selectPhone, 200);
      }
      selectPhone();
    })();
""".trimIndent()
